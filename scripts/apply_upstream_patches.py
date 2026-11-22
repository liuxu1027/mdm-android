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
# CAREFUL: this crate is compiled for the HOST too (uniffi-bindgen runs on the host
# to generate the Kotlin bindings), not only for Android. cfg!(...) only yields a
# boolean; the code inside still compiles on every target, so a cfg! branch that
# mentions an Android-only name (webpki_config) breaks the host build with E0425
# (this killed build #27). Use real #[cfg] attributes wherever a branch references
# a name that exists on one platform only. The unreachable-code warnings that
# #[cfg]-gated returns cause on Android are harmless.
# cfg!(...) is safe only where the branch body uses no platform-only names
# (system_has_roots below: its body is just `return true;`).
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
    #[cfg(target_os = "android")]
    return builder.tls_backend_preconfigured(webpki_config(&[]));
    if has_roots {"""
    if "return builder.tls_backend_preconfigured(webpki_config(&[]));" in text:
        print(f"Already patched (configure_with): {TLS_RS}")
    elif old_cw in text:
