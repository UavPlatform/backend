#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Validate the WebSocket protocol manifest without starting Spring.

Usage:
  python tools/ws-contract.py --check
  python tools/ws-contract.py --update-sha
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
DEFAULT_ROOT = BACKEND / "spec" / "ws"
SOURCE = BACKEND / "drone-backend" / "src" / "main" / "java"
HANDLER_INTERFACE = "implements WsMessageHandler"

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def fail(message: str) -> None:
    print(f"[ws] ERROR {message}", file=sys.stderr)


def referenced_files(manifest: dict) -> set[str]:
    files: set[str] = set()
    for channel in manifest.get("channels", {}).values():
        for direction in ("inbound", "outbound"):
            for entry in channel.get(direction, []):
                for key in ("schema", "example"):
                    value = entry.get(key)
                    if isinstance(value, str):
                        files.add(value)
    return files


def check_example(entry: dict, example_path: Path) -> list[str]:
    problems = []
    try:
        example = json.loads(example_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        return [f"{example_path}: invalid JSON ({error})"]
    if example.get("type") != entry.get("type"):
        problems.append(
            f"{example_path}: type {example.get('type')!r} != manifest {entry.get('type')!r}"
        )
    expected_name = entry.get("name")
    if expected_name is not None and example.get("name") != expected_name:
        problems.append(
            f"{example_path}: name {example.get('name')!r} != manifest {expected_name!r}"
        )
    return problems


def java_handlers() -> set[str]:
    handlers = set()
    if not SOURCE.is_dir():
        return handlers
    for path in SOURCE.rglob("*.java"):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if HANDLER_INTERFACE in text:
            handlers.add(path.stem)
    return handlers


def check(root: Path, update_sha: bool) -> int:
    manifest_path = root / "ws-manifest.json"
    if not manifest_path.is_file():
        fail(f"missing manifest: {manifest_path}")
        return 1
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except ValueError as error:
        fail(f"manifest is not valid JSON: {error}")
        return 1

    problems: list[str] = []
    manifest_handlers: set[str] = set()
    for channel_name, channel in manifest.get("channels", {}).items():
        for entry in channel.get("inbound", []):
            handler = entry.get("handler")
            if not handler:
                problems.append(f"{channel_name}: inbound entry has no handler")
            else:
                manifest_handlers.add(handler)
            for key in ("schema", "example"):
                relative = entry.get(key)
                if not relative:
                    problems.append(f"{channel_name}: inbound {entry.get('type')}/{entry.get('name')} has no {key}")
                    continue
                path = root / relative
                if not path.is_file():
                    problems.append(f"{channel_name}: missing {key} {relative}")
            example = entry.get("example")
            if example and (root / example).is_file():
                problems.extend(check_example(entry, root / example))
            schema_path = entry.get("schema")
            if schema_path and (root / schema_path).is_file():
                try:
                    json.loads((root / schema_path).read_text(encoding="utf-8"))
                except ValueError as error:
                    problems.append(f"{schema_path}: invalid JSON ({error})")
        for entry in channel.get("outbound", []):
            for key in ("schema", "example"):
                relative = entry.get(key)
                if not relative:
                    problems.append(f"{channel_name}: outbound {entry.get('type')}/{entry.get('name')} has no {key}")
                    continue
                if not (root / relative).is_file():
                    problems.append(f"{channel_name}: missing {key} {relative}")
            example = entry.get("example")
            if example and (root / example).is_file():
                problems.extend(check_example(entry, root / example))

    handlers = java_handlers()
    if not handlers:
        problems.append(f"no WsMessageHandler implementations found under {SOURCE}")
    for handler in sorted(handlers - manifest_handlers):
        problems.append(f"handler {handler} has no inbound manifest entry")
    for handler in sorted(manifest_handlers - handlers):
        problems.append(f"manifest references unknown handler {handler}")

    referenced = referenced_files(manifest)
    actual = {
        path.relative_to(root).as_posix()
        for directory in ("schemas", "examples")
        for path in (root / directory).rglob("*.json")
    }
    for orphan in sorted(actual - referenced):
        problems.append(f"orphan protocol file not referenced by the manifest: {orphan}")

    if problems:
        for problem in problems:
            fail(problem)
        return 1

    if update_sha:
        lines = []
        for path in sorted(root.rglob("*")):
            if not path.is_file() or path.name == "SHA256SUMS":
                continue
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            lines.append(f"{digest}  {path.relative_to(root).as_posix()}")
        (root / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        print(f"[ws] wrote SHA256SUMS ({len(lines)} files)")
        return 0

    sums_path = root / "SHA256SUMS"
    if not sums_path.is_file():
        fail("missing SHA256SUMS (run: python tools/ws-contract.py --update-sha)")
        return 1
    expected = {}
    for line in sums_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        digest, _, relative = line.partition("  ")
        expected[relative.strip()] = digest.strip()
    for relative, digest in expected.items():
        path = root / relative
        if not path.is_file():
            problems.append(f"SHA256SUMS references missing file {relative}")
        elif hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            problems.append(f"sha256 mismatch: {relative}")
    for path in sorted(root.rglob("*")):
        if path.is_file() and path.name != "SHA256SUMS":
            relative = path.relative_to(root).as_posix()
            if relative not in expected:
                problems.append(f"file not covered by SHA256SUMS: {relative}")
    if problems:
        for problem in problems:
            fail(problem)
        return 1
    print(f"[ws] OK: {len(manifest['channels'])} channel(s), {len(handlers)} handler(s)")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ws-contract.py")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--update-sha", action="store_true")
    parser.add_argument("--root", default=str(DEFAULT_ROOT))
    args = parser.parse_args(argv)
    return check(Path(args.root), update_sha=args.update_sha)


if __name__ == "__main__":
    sys.exit(main())
