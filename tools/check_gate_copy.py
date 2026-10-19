#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Verify that docs/tools/gate.py and backend/tools/gate.py are byte-identical."""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path

WORKSPACE = Path(__file__).resolve().parents[2]
CANONICAL = WORKSPACE / "docs" / "tools" / "gate.py"
COPY = WORKSPACE / "backend" / "tools" / "gate.py"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    missing = [path for path in (CANONICAL, COPY) if not path.is_file()]
    if missing:
        for path in missing:
            print(f"[gate-copy] missing: {path}", file=sys.stderr)
        return 1
    canonical = digest(CANONICAL)
    copy = digest(COPY)
    if canonical != copy:
        print(
            "[gate-copy] docs/tools/gate.py and backend/tools/gate.py differ\n"
            f"  docs    {canonical}\n"
            f"  backend {copy}\n"
            "Copy the canonical file over the backend copy.",
            file=sys.stderr,
        )
        return 1
    print(f"[gate-copy] ok: {canonical}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
