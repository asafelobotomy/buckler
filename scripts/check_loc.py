#!/usr/bin/env python3
"""Fail if any tracked source file exceeds the per-file line limit."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

MAX_LINES = 400
CHECKED_SUFFIXES = {".py", ".kt", ".kts", ".java", ".xml", ".yml", ".yaml", ".md", ".sh"}


def tracked_files(root: Path) -> list[Path]:
    out = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard"],
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    return [root / line for line in out.splitlines() if line]


def oversized(root: Path, limit: int = MAX_LINES) -> list[tuple[Path, int]]:
    bad: list[tuple[Path, int]] = []
    for path in tracked_files(root):
        if path.suffix not in CHECKED_SUFFIXES or not path.is_file():
            continue
        count = len(path.read_text(encoding="utf-8", errors="replace").splitlines())
        if count > limit:
            bad.append((path.relative_to(root), count))
    return sorted(bad, key=lambda item: -item[1])


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    bad = oversized(root)
    for rel, count in bad:
        print(f"{rel}: {count} lines (max {MAX_LINES})")
    if bad:
        print(f"{len(bad)} file(s) exceed {MAX_LINES} lines", file=sys.stderr)
        return 1
    print(f"ok: all checked files <= {MAX_LINES} lines")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
