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
    if "new_with_extra_roots" in text and 'target_os = "android"' not in text:
        print(
            "Note: Check upstream/crates/engine/src/ftp.rs to guard "
            "new_with_extra_roots behind #[cfg(not(target_os = \"android\"))] "
            "and use Verifier::new on Android."
        )
