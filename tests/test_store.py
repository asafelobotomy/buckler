"""bucklerStore from the CLI: the bundled index, and what installing puts on the TV."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

import pytest

from buckler import store
from buckler.iconpacks import load_iconpacks
from buckler.store import DEVICE_ROOT, StoreError, find, install, load_index, parse_index, remove

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / "launcher/app/src/main/assets/store_index.json"
MAIN_STAGE = ROOT / "launcher/app/src/main/java/dev/solon/bucklerhome/MainStage.kt"
SAVER_PREFS = ROOT / "launcher/app/src/main/java/dev/solon/bucklerhome/ScreensaverPrefs.kt"
APPEARANCE = ROOT / "launcher/app/src/main/java/dev/solon/bucklerhome/AppearancePrefs.kt"


@dataclass
class StoreAdb:
    """Just enough adb for the store: a pretend file system under /sdcard."""

    files: dict[str, str] = field(default_factory=dict)
    installed: list[str] = field(default_factory=list)
    versions: dict[str, str] = field(default_factory=dict)

    def connect(self) -> str:
        return "connected"

    def push(self, local: str, remote: str) -> str:
        self.files[remote] = Path(local).read_bytes().decode("latin-1")
        return "1 file pushed"

    def shell(self, *args: str, check: bool = True) -> str:
        if args[0] == "cat":
            return self.files.get(args[1], "cat: No such file or directory")
        if args[:2] == ("dumpsys", "package"):
            version = self.versions.get(args[2])
            return f"  versionName={version}\n" if version else ""
        if args[:2] == ("rm", "-f"):
            self.files.pop(args[2], None)
        if args[:2] == ("rm", "-rf"):
            self.files = {k: v for k, v in self.files.items() if not k.startswith(args[2] + "/")}
        return ""

    def install_apk(self, apk_path: str) -> str:
        self.installed.append(apk_path)
        return "Success"

    def package_present(self, package: str) -> bool:
        return True


def test_bundled_copies_are_identical() -> None:
    assert store.bundled_index_path().read_bytes() == ASSET.read_bytes()


def test_every_main_stage_is_a_store_widget() -> None:
    block = re.search(r"enum class MainStage\(.*?\{(.*?);", MAIN_STAGE.read_text(encoding="utf-8"), re.DOTALL)
    assert block
    stages = {name.lower() for name in re.findall(r"^\s+([A-Z_]+)\(", block.group(1), re.MULTILINE)}
    widgets = {item.id for item in load_index() if item.kind == "widgets"}
    assert stages == widgets


def test_every_screensaver_is_in_the_store() -> None:
    block = re.search(r"enum class ScreensaverMode\(.*?\{(.*?);", SAVER_PREFS.read_text(encoding="utf-8"), re.DOTALL)
    assert block
    modes = {"saver_" + name.lower() for name in re.findall(r"^\s+([A-Z_]+)\(", block.group(1), re.MULTILINE)}
    savers = {item.id for item in load_index() if item.kind == "screensavers"}
    assert modes == savers


def test_every_built_in_scheme_is_in_the_store() -> None:
    block = re.search(r"enum class AppTheme\(.*?\{(.*?);", APPEARANCE.read_text(encoding="utf-8"), re.DOTALL)
    assert block
    themes = {"scheme_" + name.lower() for name in re.findall(r"^\s+([A-Z_]+)\(", block.group(1), re.MULTILINE)}
    schemes = {item.id for item in load_index() if item.kind == "schemes"}
    assert themes == schemes


def test_store_icon_packs_match_the_catalog() -> None:
    catalog = {p.id: (p.package, p.url, p.sha256) for p in load_iconpacks()}
    listed = {i.id: (i.raw["package"], i.raw["url"], i.raw["sha256"]) for i in load_index() if i.kind == "iconpacks"}
    assert listed == catalog


def test_newer_schema_is_refused() -> None:
    with pytest.raises(StoreError, match="schema"):
        parse_index(json.dumps({"schema": 2}))


def test_unknown_id_is_refused() -> None:
    with pytest.raises(StoreError):
        find(load_index(), "no-such-thing")


def test_theme_install_and_remove() -> None:
    adb = StoreAdb()
    theme = find(load_index(), "sakura")
    install(adb, theme)  # type: ignore[arg-type]
    remote = f"{DEVICE_ROOT}/themes/sakura.json"
    assert json.loads(adb.files[remote])["colors"]["brand"] == theme.raw["colors"]["brand"]
    remove(adb, theme)  # type: ignore[arg-type]
    assert remote not in adb.files


def test_widgets_toggle_in_one_file() -> None:
    adb = StoreAdb()
    lyrics = find(load_index(), "lyrics")
    remove(adb, lyrics)  # type: ignore[arg-type]
    assert json.loads(adb.files[f"{DEVICE_ROOT}/widgets.json"]) == {"added": [], "removed": ["lyrics"]}
    install(adb, lyrics)  # type: ignore[arg-type]
    assert json.loads(adb.files[f"{DEVICE_ROOT}/widgets.json"]) == {"added": ["lyrics"], "removed": []}


def test_schemes_toggle_in_their_own_file() -> None:
    adb = StoreAdb()
    gruvbox = find(load_index(), "scheme_gruvbox")
    install(adb, gruvbox)  # type: ignore[arg-type]
    assert json.loads(adb.files[f"{DEVICE_ROOT}/schemes.json"]) == {"added": ["scheme_gruvbox"], "removed": []}
    remove(adb, gruvbox)  # type: ignore[arg-type]
    assert json.loads(adb.files[f"{DEVICE_ROOT}/schemes.json"]) == {"added": [], "removed": ["scheme_gruvbox"]}


def test_screensavers_toggle_in_their_own_file() -> None:
    adb = StoreAdb()
    maze = find(load_index(), "saver_maze")
    install(adb, maze)  # type: ignore[arg-type]
    assert json.loads(adb.files[f"{DEVICE_ROOT}/screensavers.json"]) == {"added": ["saver_maze"], "removed": []}
    remove(adb, maze)  # type: ignore[arg-type]
    assert json.loads(adb.files[f"{DEVICE_ROOT}/screensavers.json"]) == {"added": [], "removed": ["saver_maze"]}
    assert f"{DEVICE_ROOT}/widgets.json" not in adb.files


def test_font_install_verifies_and_pushes(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    payload = b"not really a font"
    digest = hashlib.sha256(payload).hexdigest()
    item = store.StoreItem("fonts", "lexend", "Lexend", "", {
        "files": {s: {"url": f"https://example.test/{s}.ttf", "sha256": digest} for s in ("regular", "bold")},
    })

    def fake_fetch(url: str, sha256: str, dest: Path) -> Path:
        assert sha256 == digest
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(payload)
        return dest

    monkeypatch.setattr(store, "fetch_verified", fake_fetch)
    adb = StoreAdb()
    install(adb, item, cache=tmp_path)  # type: ignore[arg-type]
    base = f"{DEVICE_ROOT}/fonts/lexend"
    assert {f"{base}/regular.ttf", f"{base}/bold.ttf", f"{base}/font.json"} <= adb.files.keys()
    assert json.loads(adb.files[f"{base}/font.json"])["revision"] == 1


def test_mismatched_download_is_refused(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def read(self, n: int = -1) -> bytes:
            data, self.data = getattr(self, "data", b"tampered"), b""
            return data

    monkeypatch.setattr(store.urllib.request, "urlopen", lambda *a, **k: Response())
    with pytest.raises(StoreError, match="sha256"):
        store.fetch_verified("https://example.test/x.ttf", "0" * 64, tmp_path / "dl" / "x.ttf")
    assert not list((tmp_path / "dl").iterdir())


def test_plain_http_is_refused(tmp_path: Path) -> None:
    with pytest.raises(StoreError, match="https"):
        store.fetch_verified("http://example.test/x.ttf", "0" * 64, tmp_path / "x.ttf")


def test_revisions_default_to_one() -> None:
    old = store.StoreItem("themes", "x", "X", "", {})
    assert (old.revision, old.changes) == (1, "")
    maze = find(load_index(), "saver_maze")
    assert maze.revision >= 1 and maze.requires


def _theme(revision: int) -> store.StoreItem:
    sakura = find(load_index(), "sakura")
    return store.StoreItem("themes", "sakura", sakura.label, sakura.about,
                           {**sakura.raw, "revision": revision, "changes": "Warmer pink"})


def test_a_scheme_behind_the_store_is_updated() -> None:
    adb = StoreAdb()
    install(adb, _theme(1))  # type: ignore[arg-type]
    newer = _theme(2)
    behind = store.outdated(adb, [newer])  # type: ignore[arg-type]
    assert [(o.item.id, o.have) for o in behind] == [("sakura", 1)]
    assert store.update(adb, [newer]) == ["Sakura: updated to revision 2: Warmer pink"]  # type: ignore[arg-type]
    assert json.loads(adb.files[f"{DEVICE_ROOT}/themes/sakura.json"])["revision"] == 2
    assert store.outdated(adb, [newer]) == []  # type: ignore[arg-type]


def _maze(revision: int, requires: str) -> store.StoreItem:
    maze = find(load_index(), "saver_maze")
    return store.StoreItem("screensavers", maze.id, maze.label, maze.about,
                           {**maze.raw, "revision": revision, "requires": requires, "changes": "Gems"})


def test_a_built_in_is_updated_with_buckler_or_needs_a_newer_one() -> None:
    adb = StoreAdb(versions={store.BUCKLER_PACKAGE: "0.23.0"})
    adb.files[f"{DEVICE_ROOT}/screensavers.json"] = json.dumps({"added": ["saver_maze"], "removed": []})
    adb.files[f"{DEVICE_ROOT}/seen.json"] = json.dumps({"saver_maze": 1})
    carried = _maze(2, "0.23.0")
    assert [(o.item.id, o.needs_buckler) for o in store.outdated(adb, [carried])] == [("saver_maze", False)]  # type: ignore[arg-type]
    assert store.update(adb, [carried]) == ["3D Maze: updated with Buckler: Gems"]  # type: ignore[arg-type]
    assert json.loads(adb.files[f"{DEVICE_ROOT}/seen.json"]) == {"saver_maze": 2}
    # A revision only a newer Buckler carries is reported, not marked seen.
    later = _maze(3, "0.24.0")
    assert store.update(adb, [later]) == ["3D Maze: update Buckler to 0.24.0 for the newest version: Gems"]  # type: ignore[arg-type]
    assert json.loads(adb.files[f"{DEVICE_ROOT}/seen.json"]) == {"saver_maze": 2}


def test_items_the_tv_does_not_have_are_never_behind() -> None:
    adb = StoreAdb(versions={store.BUCKLER_PACKAGE: "0.23.0"})
    adb.files[f"{DEVICE_ROOT}/seen.json"] = "{}"
    # 3D Maze is opt-in and not added; Sakura is not installed.
    assert store.outdated(adb, [_maze(2, "0.23.0"), _theme(2)]) == []  # type: ignore[arg-type]


def test_adding_a_built_in_records_what_buckler_carries() -> None:
    adb = StoreAdb(versions={store.BUCKLER_PACKAGE: "0.23.0"})
    adb.files[f"{DEVICE_ROOT}/seen.json"] = "{}"
    install(adb, _maze(2, "0.23.0"))  # type: ignore[arg-type]
    assert json.loads(adb.files[f"{DEVICE_ROOT}/seen.json"]) == {"saver_maze": 2}


def test_versions_compare_numerically() -> None:
    assert store.version_at_least("0.23.0", "0.23.0")
    assert store.version_at_least("0.23.1", "0.23")
    assert store.version_at_least("0.10.0", "0.9.9")
    assert not store.version_at_least("0.22.0", "0.23.0")
