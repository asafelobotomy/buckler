"""Guards against drift between Python tooling and the Kotlin launcher."""

from __future__ import annotations

import json
import re
from pathlib import Path

from buckler.home_layout import DEFAULT_DOCK

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "launcher" / "app" / "src" / "main" / "java" / "dev" / "solon" / "bucklerhome"


def test_default_dock_matches_kotlin() -> None:
    text = (KOTLIN / "Apps.kt").read_text(encoding="utf-8")
    block = re.search(r"DEFAULT_DOCK_PACKAGES[^=]*=\s*listOf\((.*?)\)", text, re.DOTALL)
    assert block, "DEFAULT_DOCK_PACKAGES not found"
    kotlin = re.findall(r'"([^"]+)"', block.group(1))
    assert kotlin == DEFAULT_DOCK


def test_bundled_covers_match_repo_index() -> None:
    repo = (ROOT / "covers" / "index.json").read_text(encoding="utf-8")
    asset = (
        ROOT / "launcher" / "app" / "src" / "main" / "assets" / "covers_index.json"
    ).read_text(encoding="utf-8")
    assert repo == asset


def test_curated_covers_are_bundled_assets() -> None:
    index = json.loads((ROOT / "covers" / "index.json").read_text(encoding="utf-8"))
    prefix = "file:///android_asset/"
    assets = ROOT / "launcher" / "app" / "src" / "main" / "assets"
    for pkg, entry in index["apps"].items():
        for kind in ("icon", "poster", "banner"):
            url = entry.get(kind)
            if url is None:
                continue
            assert url.startswith(prefix), f"{pkg}.{kind} must not use a remote URL: {url}"
            assert (assets / url.removeprefix(prefix)).is_file(), f"{pkg}.{kind} missing asset"
