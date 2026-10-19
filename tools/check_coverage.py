#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Coverage ratchet for backend/drone-backend.

Compares JaCoCo line coverage against tools/coverage-baseline.json.
The baseline is written only from a full, no-skip run:

  python tools/check_coverage.py --update --reason "..."

CI refuses --update.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
MODULE = BACKEND / "drone-backend"
DEFAULT_REPORT = MODULE / "target" / "site" / "jacoco" / "jacoco.xml"
DEFAULT_BASELINE = BACKEND / "tools" / "coverage-baseline.json"
SUREFIRE = MODULE / "target" / "surefire-reports"
FAILSAFE = MODULE / "target" / "failsafe-reports"

OVERALL_TOLERANCE = 0.002
PACKAGE_TOLERANCE = 0.005
PACKAGE_MIN_LINES = 200
MIN_CLASSES = 250

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def line_counter(element: ET.Element) -> tuple[int, int]:
    for counter in element.findall("counter"):
        if counter.get("type") == "LINE":
            return int(counter.get("covered", "0")), int(counter.get("missed", "0"))
    return 0, 0


def ratio(covered: int, missed: int) -> float:
    total = covered + missed
    return covered / total if total else 0.0


def read_report(report: Path) -> tuple[dict, dict, int]:
    tree = ET.parse(report)
    root = tree.getroot()
    covered, missed = line_counter(root)
    overall = {"covered": covered, "missed": missed, "total": covered + missed, "ratio": ratio(covered, missed)}
    packages = {}
    for package in root.findall("package"):
        name = package.get("name", "")
        pkg_covered, pkg_missed = line_counter(package)
        packages[name] = {
            "covered": pkg_covered,
            "missed": pkg_missed,
            "total": pkg_covered + pkg_missed,
            "ratio": ratio(pkg_covered, pkg_missed),
        }
    classes = len(root.findall(".//class"))
    return overall, packages, classes


def test_counts(directory: Path) -> tuple[int, int]:
    tests = skipped = 0
    if not directory.is_dir():
        return 0, 0
    for path in directory.glob("TEST-*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        tests += int(root.get("tests", "0") or 0)
        skipped += int(root.get("skipped", "0") or 0)
    return tests, skipped


def guard_inputs(report: Path, require_no_skips: bool) -> list[str]:
    problems = []
    if not report.is_file():
        return [f"missing JaCoCo report: {report} (run: mvnw clean verify)"]
    if report.stat().st_size == 0:
        problems.append(f"empty JaCoCo report: {report}")
    exec_file = report.parent.parent.parent / "jacoco.exec"
    if not exec_file.is_file() or exec_file.stat().st_size == 0:
        problems.append(f"missing or empty {exec_file}; coverage did not run")
    unit_tests, _ = test_counts(SUREFIRE)
    it_tests, it_skipped = test_counts(FAILSAFE)
    if unit_tests == 0:
        problems.append("no Surefire tests (unit layer did not run)")
    if it_tests == 0:
        problems.append("no Failsafe tests (integration layer did not run)")
    if require_no_skips and it_skipped > 0:
        problems.append(f"{it_skipped} skipped Failsafe test(s); coverage baseline is not comparable")
    return problems


def load_baseline(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="check_coverage.py")
    parser.add_argument("--report", default=str(DEFAULT_REPORT))
    parser.add_argument("--baseline", default=str(DEFAULT_BASELINE))
    parser.add_argument("--update", action="store_true")
    parser.add_argument("--reason", default="")
    parser.add_argument("--require-no-skips", action="store_true")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    report = Path(args.report)
    baseline_path = Path(args.baseline)

    problems = guard_inputs(report, args.require_no_skips)
    if problems:
        for problem in problems:
            print(f"[coverage] ERROR {problem}", file=sys.stderr)
        return 1

    overall, packages, classes = read_report(report)
    if classes < MIN_CLASSES:
        print(f"[coverage] ERROR only {classes} classes measured (expected >= {MIN_CLASSES})", file=sys.stderr)
        return 1
    if overall["total"] == 0 or overall["covered"] == 0:
        print("[coverage] ERROR coverage counters are empty", file=sys.stderr)
        return 1

    if args.update:
        if os.environ.get("CI"):
            print("[coverage] refusing --update in CI", file=sys.stderr)
            return 1
        payload = {
            "schema": 1,
            "updated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "reason": args.reason,
            "environment": {"jacoco": "0.8.15", "require_no_skips": bool(args.require_no_skips)},
            "overall": overall,
            "packages": packages,
        }
        baseline_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(f"[coverage] baseline updated: overall {overall['ratio']:.2%}")
        return 0

    if not baseline_path.is_file():
        print(f"[coverage] ERROR missing baseline {baseline_path}; run a full no-skip verify then --update", file=sys.stderr)
        return 1

    baseline = load_baseline(baseline_path)
    failures = []
    base_overall = float(baseline.get("overall", {}).get("ratio", 0.0))
    if overall["ratio"] < base_overall - OVERALL_TOLERANCE:
        failures.append(
            f"overall {overall['ratio']:.2%} below baseline {base_overall:.2%} "
            f"(tolerance {OVERALL_TOLERANCE:.1%})"
        )
    base_packages = baseline.get("packages", {})
    for name, current in sorted(packages.items()):
        if current["total"] < PACKAGE_MIN_LINES:
            continue
        if name not in base_packages:
            if not args.quiet:
                print(f"[coverage] WARN new package not in baseline: {name} ({current['ratio']:.2%})")
            continue
        base_ratio = float(base_packages[name].get("ratio", 0.0))
        if current["ratio"] < base_ratio - PACKAGE_TOLERANCE:
            failures.append(
                f"{name}: {current['ratio']:.2%} below baseline {base_ratio:.2%} "
                f"(tolerance {PACKAGE_TOLERANCE:.1%})"
            )

    if failures:
        for failure in failures:
            print(f"[coverage] ERROR {failure}", file=sys.stderr)
        print("[coverage] improve coverage or run --update after a reviewed, full no-skip verify", file=sys.stderr)
        return 1
    if not args.quiet:
        print(
            f"[coverage] OK overall {overall['ratio']:.2%} "
            f"({overall['covered']}/{overall['total']} lines, {classes} classes)"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
