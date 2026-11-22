#!/usr/bin/env python3
"""Apply Android portability patches from Issue #411 to upstream crates."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENGINE_TOML = ROOT / "upstream" / "crates" / "engine" / "Cargo.toml"
FTP_RS = ROOT / "upstream" / "crates" / "engine" / "src" / "ftp.rs"
TLS_RS = ROOT / "upstream" / "crates" / "engine" / "src" / "tls.rs"

if ENGINE_TOML.exists():
    text = ENGINE_TOML.read_text(encoding="utf-8")
    android_block = (
        '\n[target.\x27cfg(target_os = "android")\x27.dependencies]\n'
        'rquickjs = { version = "*", features = ["bindgen"] }\n'
    )
    if 'target_os = "android"' not in text:
        ENGINE_TOML.write_text(text + android_block, encoding="utf-8")
        print(f"Patched {ENGINE_TOML}")
    else:
        print(f"Already patched: {ENGINE_TOML}")
else:
    print(f"Warning: {ENGINE_TOML} not found. Did you add the upstream submodule?")

# --- tls.rs: trust Mozilla roots (pure-Rust WebPKI) on Android ---------------------------
# Why: reqwest's tls_certs_merge builds rustls-platform-verifier, which on Android needs
# JNI initialisation plus a Kotlin helper class (org.rustls.platformverifier
# .CertificateVerifier) this app does not ship, and fails at client-build time with
# "rustls-platform-verifier could not load extra certs" (seen on device 2026-11-22: every
# HTTP task failed instantly, 0 B/s). The WebPKI verifier with Mozilla's bundled roots
# needs neither JNI nor the Kotlin class.
# Note: use cfg!(...) (not #[cfg] attributes) for the early returns, so the code after
# them is not flagged unreachable; #[cfg] is still needed where a name only exists on
# one platform (Verifier in ftp.rs).
if TLS_RS.exists():
    text = TLS_RS.read_text(encoding="utf-8")
    patched = False

    # 1. Add webpki_config() helper before mozilla_certificates().
    # build() returns Arc<WebPkiServerVerifier>, so pass it directly (no Arc::new).
    webpki_fn = """/// A TLS client configuration trusting Mozilla's roots (plus `extra` DER certificates),
/// checked purely in Rust (WebPKI). Android uses this instead of rustls-platform-verifier:
/// the platform verifier needs JNI initialisation plus a Kotlin helper class this app
/// does not ship, and fails at runtime ("could not load extra certs").
#[cfg(target_os = "android")]
pub(crate) fn webpki_config(extra: &[Vec<u8>]) -> rustls::ClientConfig {
    let mut store = rustls::RootCertStore::empty();
    for cert in mozilla_roots() {
        let _ = store.add(cert);
    }
    for der in extra {
        let _ = store.add(rustls::pki_types::CertificateDer::from(der.clone()));
    }
    let verifier = rustls::client::WebPkiServerVerifier::builder_with_provider(
        Arc::new(store),
        provider(),
    )
    .build()
    .expect("Mozilla roots are valid certificates");
    rustls::ClientConfig::builder_with_provider(provider())
        .with_safe_default_protocol_versions()
        .expect("the default protocol versions are supported")
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_no_client_auth()
}

"""
    anchor = "/// [`mozilla_roots`] as reqwest takes them, made once."
    if "fn webpki_config(" in text:
        print(f"Already patched (webpki_config): {TLS_RS}")
    elif anchor in text:
        text = text.replace(anchor, webpki_fn + anchor, 1)
        patched = True
    else:
        print(f"Warning: Could not find webpki_config anchor in {TLS_RS}")

    # 2. configure_with: Android uses the preconfigured WebPKI config, never tls_certs_merge.
    old_cw = """fn configure_with(
    builder: reqwest::ClientBuilder,
    has_roots: bool,
    fallback: Vec<reqwest::Certificate>,
) -> reqwest::ClientBuilder {
    if has_roots {"""
    new_cw = """fn configure_with(
    builder: reqwest::ClientBuilder,
    has_roots: bool,
    fallback: Vec<reqwest::Certificate>,
) -> reqwest::ClientBuilder {
    if cfg!(target_os = "android") {
        return builder.tls_backend_preconfigured(webpki_config(&[]));
    }
    if has_roots {"""
    if "return builder.tls_backend_preconfigured(webpki_config(&[]));" in text:
        print(f"Already patched (configure_with): {TLS_RS}")
    elif old_cw in text:
        text = text.replace(old_cw, new_cw, 1)
        patched = True
    else:
        print(f"Warning: Could not find configure_with pattern in {TLS_RS}")

    # 3. system_has_roots: always true on Android (Mozilla roots are bundled in).
    old_shr = """pub fn system_has_roots() -> bool {
    static FOUND: AtomicBool = AtomicBool::new(false);"""
    new_shr = """pub fn system_has_roots() -> bool {
    // Android always has roots: webpki_config bundles Mozilla's list (no platform verifier).
    if cfg!(target_os = "android") {
        return true;
    }
    static FOUND: AtomicBool = AtomicBool::new(false);"""
    if 'if cfg!(target_os = "android") {\n        return true;\n    }\n    static FOUND' in text:
        print(f"Already patched (system_has_roots): {TLS_RS}")
    elif old_shr in text:
        text = text.replace(old_shr, new_shr, 1)
        patched = True
    else:
        print(f"Warning: Could not find system_has_roots pattern in {TLS_RS}")

    if patched:
        TLS_RS.write_text(text, encoding="utf-8")
        print(f"Patched {TLS_RS}: WebPKI Mozilla-roots TLS on Android")
else:
    print(f"Warning: {TLS_RS} not found.")

# --- ftp.rs: client_config uses webpki_config on Android ---------------------------------
# The old approach (Verifier::new on Android) still needs the platform verifier's JNI init
# and Kotlin class at handshake time, so it fails the same way. Bypass it entirely.
# The Verifier name only exists on non-Android, so its uses must be #[cfg]-gated:
# an early return alone does not help, unreachable code is still compiled.
if FTP_RS.exists():
    text = FTP_RS.read_text(encoding="utf-8")
    patched = False

    old_sig = """pub(crate) fn client_config(extra: &[Vec<u8>]) -> Result<rustls::ClientConfig, rustls::Error> {
    use rustls_platform_verifier::Verifier;"""
    new_sig = """pub(crate) fn client_config(extra: &[Vec<u8>]) -> Result<rustls::ClientConfig, rustls::Error> {
    // Android: rustls-platform-verifier needs JNI init + a Kotlin helper class this app
    // does not ship; its verifier fails at runtime ("could not load extra certs").
    // Trust Mozilla's roots directly (pure Rust, no platform verifier).
    if cfg!(target_os = "android") {
        return Ok(crate::tls::webpki_config(extra));
    }
    #[cfg(not(target_os = "android"))]
    use rustls_platform_verifier::Verifier;"""
    if "return Ok(crate::tls::webpki_config(extra));" in text:
        print(f"Already patched (early return): {FTP_RS}")
    elif old_sig in text:
        text = text.replace(old_sig, new_sig, 1)
        patched = True
    else:
        print(f"Warning: Could not find client_config signature in {FTP_RS}")

    old_tail = """    let provider = crate::tls::provider();
    let extra = extra.iter().map(|c| CertificateDer::from(c.clone()));
    let verifier =
        Verifier::new_with_extra_roots(crate::tls::mozilla_roots().chain(extra), provider.clone())?;
    Ok(rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(verifier))
        .with_no_client_auth())"""
    new_tail = """    #[cfg(not(target_os = "android"))]
    let provider = crate::tls::provider();
    #[cfg(not(target_os = "android"))]
    let extra = extra.iter().map(|c| CertificateDer::from(c.clone()));
    #[cfg(not(target_os = "android"))]
    {
        let verifier = Verifier::new_with_extra_roots(
            crate::tls::mozilla_roots().chain(extra),
            provider.clone(),
        )?;
        return Ok(rustls::ClientConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()?
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(verifier))
            .with_no_client_auth());
    }
    #[cfg(target_os = "android")]
    unreachable!("Android returns early via webpki_config")"""
    if 'unreachable!("Android returns early via webpki_config")' in text:
        print(f"Already patched (tail): {FTP_RS}")
    elif old_tail in text:
        text = text.replace(old_tail, new_tail, 1)
        patched = True
    else:
        print(f"Warning: Could not find client_config tail in {FTP_RS}")

    # CertificateDer is only used by the cfg-gated `let extra` line above; gate the
    # import too so Android builds have no unused-import warning.
    old_import = "use rustls::pki_types::CertificateDer;"
    new_import = '#[cfg(not(target_os = "android"))]\nuse rustls::pki_types::CertificateDer;'
    if new_import in text:
        print(f"Already patched (import): {FTP_RS}")
    elif old_import in text:
        text = text.replace(old_import, new_import, 1)
        patched = True
    else:
        print(f"Warning: Could not find CertificateDer import in {FTP_RS}")

    if patched:
        FTP_RS.write_text(text, encoding="utf-8")
        print(f"Patched {FTP_RS}: webpki_config on Android")
else:
    print(f"Warning: {FTP_RS} not found.")
