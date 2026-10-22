#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Turn a PMD CPD report into the excludeFromFailureFile baseline.

Usage:
  python tools/gen_cpd_baseline.py --cpd drone-backend/target/cpd.xml \
      --out tools/cpd-baseline.txt
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="gen_cpd_baseline.py")
    parser.add_argument("--cpd", default=str(BACKEND / "drone-backend" / "target" / "cpd.xml"))
    parser.add_argument("--out", default=str(BACKEND / "tools" / "cpd-baseline.txt"))
    args = parser.parse_args(argv)

    report = Path(args.cpd)
    if not report.is_file():
        print(f"[cpd] missing report: {report} (run mvnw pmd:cpd-check)", file=sys.stderr)
        return 1
    root = ET.parse(report).getroot()
    # PMD 7 writes a default XML namespace; strip it so tag lookups stay simple.
    for element in root.iter():
        if "}" in element.tag:
            element.tag = element.tag.split("}", 1)[1]
    lines = []
    for duplication in root.findall("duplication"):
        names = set()
        for element in duplication.findall("file"):
            path = element.get("path", "")
            marker = "src\\main\\java\\" if "\\" in path else "src/main/java/"
            if marker not in path:
                continue
            relative = path.split(marker, 1)[1]
            names.add(relative.replace("\\", ".").replace("/", ".").removesuffix(".java"))
        if names:
            lines.append(",".join(sorted(names)))
    lines = sorted(set(lines))
    output = Path(args.out)
    output.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8", newline="\n")
    print(f"[cpd] wrote {len(lines)} duplication group(s) to {output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
