#!/usr/bin/env python3
"""Apply Android portability patches from Issue #411 to upstream crates."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENGINE_TOML = ROOT / "upstream" / "crates" / "engine" / "Cargo.toml"
FTP_RS = ROOT / "upstream" / "crates" / "engine" / "src" / "ftp.rs"

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

if FTP_RS.exists():
    text = FTP_RS.read_text(encoding="utf-8")
    # Fix: Verifier::new_with_extra_roots doesn't exist on Android in rustls-platform-verifier 0.7.1
    # Use cfg attributes to use Verifier::new on Android, new_with_extra_roots elsewhere.
    # Upstream refactored client_config: the old if/else block (Verifier::new when extra is
    # empty, else new_with_extra_roots) is gone; current code always calls
    # new_with_extra_roots with mozilla_roots chained. Handle both shapes so the patch
    # survives upstream refactors.
    patched = False
    new_shape_old = """    let verifier =
        Verifier::new_with_extra_roots(crate::tls::mozilla_roots().chain(extra), provider.clone())?;"""
    new_shape_new = """    #[cfg(not(target_os = "android"))]
    let verifier =
        Verifier::new_with_extra_roots(crate::tls::mozilla_roots().chain(extra), provider.clone())?;
    #[cfg(target_os = "android")]
    let verifier = Verifier::new(provider.clone())?;"""
    if new_shape_new in text:
        print(f"Already patched (new shape): {FTP_RS}")
    elif new_shape_old in text:
        text = text.replace(new_shape_old, new_shape_new)
        patched = True
    old_code = """    let provider = crate::tls::provider();
    let verifier = if extra.is_empty() {
        Verifier::new(provider.clone())
    } else {
        Verifier::new_with_extra_roots(
            extra.iter().map(|c| CertificateDer::from(c.clone())),
            provider.clone(),
        )
    }?;"""
    new_code = """    let provider = crate::tls::provider();
    #[cfg(not(target_os = "android"))]
    let verifier = if extra.is_empty() {
        Verifier::new(provider.clone())
    } else {
        Verifier::new_with_extra_roots(
            extra.iter().map(|c| CertificateDer::from(c.clone())),
            provider.clone(),
        )
    }?;
    #[cfg(target_os = "android")]
    let verifier = Verifier::new(provider.clone())?;"""
    if new_code in text:
        print(f"Already patched (old shape): {FTP_RS}")
    elif old_code in text:
        text = text.replace(old_code, new_code)
        patched = True
    if patched:
        FTP_RS.write_text(text, encoding="utf-8")
        print(f"Patched {FTP_RS}: guarded new_with_extra_roots for Android")
    elif "new_with_extra_roots" in text and 'target_os = "android"' in text:
        print(f"Already patched: {FTP_RS}")
    else:
        print(f"Warning: Could not find expected code pattern in {FTP_RS}")
else:
    print(f"Warning: {FTP_RS} not found.")
