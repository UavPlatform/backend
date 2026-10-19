#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""File-size ratchet for hand-written backend sources.

Rules:
  - existing baseline files may not grow
  - new files may not exceed --limit (default 400 lines)
  - no file may exceed --hard-limit (default 800 lines)

--update is one-way: it only lowers existing entries and refuses new files
above the hard limit.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
MODULE = BACKEND / "drone-backend"
SOURCE = MODULE / "src" / "main" / "java"
DEFAULT_BASELINE = BACKEND / "tools" / "file-size-baseline.json"

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def line_count(path: Path) -> int:
    with path.open("rb") as handle:
        return sum(1 for _ in handle)


def collect() -> dict[str, int]:
    if not SOURCE.is_dir():
        return {}
    return {
        path.relative_to(MODULE).as_posix(): line_count(path)
        for path in SOURCE.rglob("*.java")
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="check_file_size.py")
    parser.add_argument("--baseline", default=str(DEFAULT_BASELINE))
    parser.add_argument("--limit", type=int, default=400)
    parser.add_argument("--hard-limit", type=int, default=800)
    parser.add_argument("--update", action="store_true")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    current = collect()
    if not current:
        print(f"[size] ERROR no Java sources under {SOURCE}", file=sys.stderr)
        return 1

    baseline_path = Path(args.baseline)
    baseline = {"schema": 1, "limit": args.limit, "hard_limit": args.hard_limit, "files": {}}
    if baseline_path.is_file():
        baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    tracked = baseline.get("files", {})

    if args.update:
        updated = {}
        for path, lines in current.items():
            if path in tracked:
                updated[path] = min(int(tracked[path]), lines)
            elif lines > args.limit:
                if lines > args.hard_limit:
                    print(
                        f"[size] ERROR refusing to baseline {path} at {lines} lines "
                        f"(hard limit {args.hard_limit}); split it first",
                        file=sys.stderr,
                    )
                    return 1
                updated[path] = lines
        # Existing entries that fell to or below the limit no longer need a waiver.
        updated = {path: lines for path, lines in updated.items() if lines > args.limit}
        payload = {
            "schema": 1,
            "limit": args.limit,
            "hard_limit": args.hard_limit,
            "files": dict(sorted(updated.items())),
        }
        baseline_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(f"[size] baseline updated: {len(updated)} file(s) above {args.limit} lines")
        return 0

    failures = []
    for path, lines in sorted(current.items()):
        if lines > args.hard_limit:
            failures.append(f"{path}: {lines} lines exceeds hard limit {args.hard_limit}")
            continue
        if path in tracked:
            maximum = int(tracked[path])
            if lines > maximum:
                failures.append(f"{path}: {lines} lines exceeds frozen baseline {maximum}")
            continue
        if lines > args.limit:
            failures.append(f"{path}: new file with {lines} lines exceeds limit {args.limit}")

    if failures:
        for failure in failures:
            print(f"[size] ERROR {failure}", file=sys.stderr)
        print("[size] split the file, or run --update only after an intentional, reviewed change", file=sys.stderr)
        return 1
    if not args.quiet:
        print(f"[size] OK {len(current)} file(s), {len(tracked)} baseline waiver(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
