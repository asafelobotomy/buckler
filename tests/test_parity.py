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


def test_unused_apps_never_flags_the_never_list() -> None:
    import yaml

    never = yaml.safe_load((ROOT / "profiles" / "never.yaml").read_text(encoding="utf-8"))["packages"]
    text = (KOTLIN / "UnusedApps.kt").read_text(encoding="utf-8")
    block = re.search(r"ALWAYS_KEEP = setOf\((.*?)\)", text, re.DOTALL)
    assert block, "ALWAYS_KEEP not found"
    kotlin = set(re.findall(r'"([^"]+)"', block.group(1)))
    assert kotlin == {"dev.solon.bucklerhome", *never}


def _kotlin_enum(name: str) -> list[str]:
    text = (KOTLIN / "AppearancePrefs.kt").read_text(encoding="utf-8")
    block = re.search(rf"enum class {name}\(.*?\{{(.*?);", text, re.DOTALL)
    assert block, f"{name} not found"
    return re.findall(r"^\s+([A-Z_]+)\(", block.group(1), re.MULTILINE)


def test_icon_schemes_match_themes_and_manifest() -> None:
    import importlib.util

    spec = importlib.util.spec_from_file_location("gen_icons", ROOT / "scripts" / "gen_icons.py")
    assert spec and spec.loader
    gen = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gen)
    themes = _kotlin_enum("AppTheme")
    assert sorted(gen.SCHEMES) == sorted(t.lower() for t in themes)
    manifest = (ROOT / "launcher" / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
    for theme in themes:
        suffix = "".join(part.capitalize() for part in theme.split("_"))
        for kind in ("Launcher", "Settings"):
            assert f'android:name=".{kind}{suffix}"' in manifest, f"{kind}{suffix} alias missing"
        assert (ROOT / "launcher/app/src/main/res/drawable" / f"ic_mark_{theme.lower()}.xml").is_file()


def test_bundled_fonts_match_font_choices() -> None:
    text = (ROOT / "scripts" / "gen_fonts.py").read_text(encoding="utf-8")
    block = re.search(r"FAMILIES = \{(.*?)\n\}", text, re.DOTALL)
    assert block
    script = re.findall(r'^\s+"(\w+)": \(', block.group(1), re.MULTILINE)
    fonts = (KOTLIN / "Fonts.kt").read_text(encoding="utf-8")
    bundled = re.findall(r"R\.font\.(\w+)_regular to R\.font\.\w+_bold", fonts)
    assert sorted(script) == sorted(bundled)
    for name in script:
        for style in ("regular", "bold"):
            assert (ROOT / "launcher/app/src/main/res/font" / f"{name}_{style}.ttf").is_file()


def test_cli_and_launcher_share_a_version() -> None:
    from buckler import __version__

    pyproject = (ROOT / "pyproject.toml").read_text(encoding="utf-8")
    gradle = (ROOT / "launcher" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert re.search(r'^version = "([^"]+)"', pyproject, re.MULTILINE).group(1) == __version__
    assert re.search(r'versionName = "([^"]+)"', gradle).group(1) == __version__


def test_bundled_shield_profiles_are_current() -> None:
    import importlib.util

    spec = importlib.util.spec_from_file_location("gen_profiles", ROOT / "scripts" / "gen_profiles.py")
    assert spec and spec.loader
    gen = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gen)
    bundled = (ROOT / "launcher/app/src/main/assets/shield_profiles.json").read_text(encoding="utf-8")
    assert bundled == gen.render(), "run: python scripts/gen_profiles.py"
