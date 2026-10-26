from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def _load_checker():
    spec = importlib.util.spec_from_file_location("check_loc", ROOT / "scripts" / "check_loc.py")
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_no_file_exceeds_line_limit() -> None:
    checker = _load_checker()
    assert checker.oversized(ROOT) == []
