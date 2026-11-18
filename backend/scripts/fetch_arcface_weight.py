#!/usr/bin/env python3
"""Fetch the ArcFace 512-d recognition weight used by the F1 face engine.

The 174 MB ONNX file is intentionally NOT committed. Download it on the host
before starting the backend (Docker Compose mounts this directory read-only):

    python3 backend/scripts/fetch_arcface_weight.py

The download is verified against a pinned SHA-256 and written atomically, so a
truncated or replaced mirror response can never be loaded by the engine. The
engine falls back to SFace 128-d when the file is absent — verification still
works, but 1:1 references harvested under that head must be reset on swap.

License: insightface buffalo_l weights are for non-commercial research. Check
the license before production use; AdaFace (MIT) is the production substitute.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import sys
import urllib.request
from pathlib import Path

URL = (
    "https://huggingface.co/facefusion/models-3.0.0/resolve/main/"
    "arcface_w600k_r50.onnx"
)
SHA256 = "f1f79dc3b0b79a69f94799af1fffebff09fbd78fd96a275fd8f0cbbea23270d1"
DEST = (
    Path(__file__).resolve().parents[1]
    / "app"
    / "models"
    / "weights"
    / "arcface_w600k_r50.onnx"
)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="re-download even if the pinned file exists")
    args = parser.parse_args()

    if DEST.exists() and not args.force and _sha256(DEST) == SHA256:
        print(f"ArcFace weight already present: {DEST}")
        return 0

    DEST.parent.mkdir(parents=True, exist_ok=True)
    part = DEST.with_suffix(".onnx.part")
    print(f"Downloading {URL} -> {part} (174 MB)...")
    try:
        urllib.request.urlretrieve(URL, part)
        actual = _sha256(part)
        if actual != SHA256:
            print(f"ERROR: checksum mismatch (expected {SHA256}, got {actual})", file=sys.stderr)
            return 1
        os.replace(part, DEST)
    except Exception as exc:
        print(f"ERROR: download failed: {exc}", file=sys.stderr)
        part.unlink(missing_ok=True)
        return 1

    print(f"Verified and installed: {DEST}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
