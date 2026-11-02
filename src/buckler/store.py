"""bucklerStore from a computer: widgets, colour schemes, fonts and icon packs for buckler-home.

The store's catalog is index.json in github.com/asafelobotomy/bucklerStore; a copy ships with
Buckler (data/store/index.json, also bundled in the APK). Installing puts files where buckler-home
looks for them, `Android/data/dev.solon.bucklerhome/files/store/` on the TV (see StoreLibrary.kt):
`themes/<id>.json`, `fonts/<id>/{regular,bold}.ttf` with `font.json`, `widgets.json`,
`screensavers.json`, `schemes.json`, and `seen.json` (the revision of each added built-in that
people have been told about). Icon packs are apps and go through `adb install`. Every download is
checked against its sha256. Each item has a store `revision`; downloads keep theirs in their saved
files, so `installed` can say what is behind and `update` can bring it up to date.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import tempfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from buckler.adb import Adb
from buckler.app_catalog import CatalogError
from buckler.iconpacks import IconPackEntry, install_iconpack
from buckler.paths import DATA, apk_cache_dir

INDEX_URL = "https://raw.githubusercontent.com/asafelobotomy/bucklerStore/main/index.json"
DEVICE_ROOT = "/sdcard/Android/data/dev.solon.bucklerhome/files/store"
SCHEMA = 1
KINDS = ("widgets", "screensavers", "schemes", "themes", "fonts", "iconpacks")
# Widgets, screensavers and built-in colour schemes ship in Buckler; the store only records which
# are added or removed.
CHOICE_FILES = {"widgets": "widgets.json", "screensavers": "screensavers.json", "schemes": "schemes.json"}
BUILT_INS = tuple(CHOICE_FILES)
BUCKLER_PACKAGE = "dev.solon.bucklerhome"
_ID = re.compile(r"^[a-z0-9][a-z0-9_-]{1,39}$")


class StoreError(CatalogError):
    """Something the store cannot do: an unknown id, a bad index, a failed download."""


@dataclass(frozen=True)
class StoreItem:
    kind: str
    id: str
    label: str
    about: str
    raw: dict[str, Any]

    @property
    def revision(self) -> int:
        """The store's count of this item's versions; an index from before revisions means 1."""
        try:
            return max(1, int(self.raw.get("revision", 1)))
        except (TypeError, ValueError):
            return 1

    @property
    def changes(self) -> str:
        return str(self.raw.get("changes", ""))

    @property
    def requires(self) -> str:
        """For a built-in: the Buckler version that carries its current revision."""
        return str(self.raw.get("requires") or self.raw.get("since") or "0")


@dataclass(frozen=True)
class Outdated:
    """An item the TV has at an older revision than the index's; ``needs_buckler`` when only a newer Buckler brings it."""

    item: StoreItem
    have: int
    needs_buckler: bool = False


def bundled_index_path() -> Path:
    return DATA / "store" / "index.json"


def parse_index(text: str) -> list[StoreItem]:
    try:
        doc = json.loads(text)
    except json.JSONDecodeError as exc:
        raise StoreError(f"store index is not JSON: {exc}") from exc
    if not isinstance(doc, dict) or doc.get("schema") != SCHEMA:
        raise StoreError(f"store index has schema {doc.get('schema') if isinstance(doc, dict) else '?'}; "
                         f"this Buckler reads schema {SCHEMA} (update Buckler)")
    items = []
    for kind in KINDS:
        for raw in doc.get(kind, []):
            ident = str(raw.get("id", ""))
            if not _ID.match(ident):
                raise StoreError(f"store index: bad {kind} id {ident!r}")
            items.append(StoreItem(kind, ident, str(raw.get("label", ident)), str(raw.get("about", "")), raw))
    return items


def load_index(*, live: bool = False) -> list[StoreItem]:
    """The bundled index, or with ``live`` the current one from GitHub."""
    if not live:
        return parse_index(bundled_index_path().read_text(encoding="utf-8"))
    try:
        request = urllib.request.Request(INDEX_URL, headers={"User-Agent": "buckler"})
        with urllib.request.urlopen(request, timeout=30) as response:
            return parse_index(response.read().decode("utf-8"))
    except OSError as exc:
        raise StoreError(f"could not fetch the store index: {exc}") from exc


def find(items: list[StoreItem], ident: str) -> StoreItem:
    for item in items:
        if item.id == ident:
            return item
    raise StoreError(f"bucklerStore has no {ident!r} (see `buckler store list`)")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 16), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch_verified(url: str, sha256: str, dest: Path) -> Path:
    """Download [url] to [dest] (reused when already there and matching), refusing a sha256 mismatch."""
    if dest.is_file() and _sha256(dest) == sha256:
        return dest
    if not url.startswith("https://"):
        raise StoreError(f"refusing a non-https download: {url}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    partial = dest.with_name(dest.name + ".partial")
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "buckler"})
        with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as handle:
            shutil.copyfileobj(response, handle)
    except OSError as exc:
        partial.unlink(missing_ok=True)
        raise StoreError(f"download failed: {url}: {exc}") from exc
    if (digest := _sha256(partial)) != sha256:
        partial.unlink(missing_ok=True)
        raise StoreError(f"sha256 mismatch for {url} (got {digest})")
    partial.replace(dest)
    return dest


def _push_text(adb: Adb, text: str, remote: str) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        local = Path(tmp) / remote.rsplit("/", 1)[1]
        local.write_text(text, encoding="utf-8")
        adb.shell("mkdir", "-p", remote.rsplit("/", 1)[0], check=False)
        adb.push(str(local), remote)


def read_widgets(adb: Adb, kind: str = "widgets") -> dict[str, list[str]]:
    raw = adb.shell("cat", f"{DEVICE_ROOT}/{CHOICE_FILES[kind]}", check=False)
    try:
        doc = json.loads(raw)
    except json.JSONDecodeError:
        doc = {}
    return {key: sorted({str(v) for v in doc.get(key, [])}) for key in ("added", "removed")}


def set_widget(adb: Adb, ident: str, on: bool, kind: str = "widgets") -> None:
    state = read_widgets(adb, kind)
    added, removed = set(state["added"]), set(state["removed"])
    (added.add if on else added.discard)(ident)
    (removed.discard if on else removed.add)(ident)
    doc = {"added": sorted(added), "removed": sorted(removed)}
    _push_text(adb, json.dumps(doc, indent=2) + "\n", f"{DEVICE_ROOT}/{CHOICE_FILES[kind]}")


def _version_key(version: str) -> tuple[int, ...]:
    core = version.split("-", 1)[0].split("+", 1)[0]
    return tuple(int(p) if p.isdigit() else 0 for p in core.split("."))


def version_at_least(have: str, need: str) -> bool:
    """Whether Buckler ``have`` is ``need`` or newer (numeric parts only, as Buckler compares them)."""
    a, b = _version_key(have), _version_key(need)
    width = max(len(a), len(b))
    return a + (0,) * (width - len(a)) >= b + (0,) * (width - len(b))


def package_version(adb: Adb, package: str) -> str | None:
    """An installed app's versionName, or None when it is not installed."""
    match = re.search(r"versionName=(\S+)", adb.shell("dumpsys", "package", package, check=False))
    return match.group(1) if match else None


def read_seen(adb: Adb) -> dict[str, int] | None:
    raw = adb.shell("cat", f"{DEVICE_ROOT}/seen.json", check=False)
    try:
        doc = json.loads(raw)
    except json.JSONDecodeError:
        return None
    return {str(k): int(v) for k, v in doc.items()} if isinstance(doc, dict) else None


def write_seen(adb: Adb, seen: dict[str, int]) -> None:
    _push_text(adb, json.dumps(dict(sorted(seen.items())), indent=2) + "\n", f"{DEVICE_ROOT}/seen.json")


def _mark_seen(adb: Adb, item: StoreItem) -> None:
    """Record a built-in's current revision as told about, when the TV's Buckler carries it.

    With no seen.json yet, Buckler records everything added the next time it looks, so nothing to do.
    """
    app = package_version(adb, BUCKLER_PACKAGE)
    seen = read_seen(adb)
    if app is None or seen is None or not version_at_least(app, item.requires):
        return
    if seen.get(item.id, 0) < item.revision:
        write_seen(adb, {**seen, item.id: item.revision})


def install(adb: Adb, item: StoreItem, cache: Path | None = None) -> str:
    """Put [item] on the TV; returns a line saying what was done."""
    adb.connect()
    if item.kind == "widgets":
        set_widget(adb, item.id, True)
        _mark_seen(adb, item)
        return f"{item.label}: added to the Main Stage's choices"
    if item.kind == "screensavers":
        set_widget(adb, item.id, True, "screensavers")
        _mark_seen(adb, item)
        return f"{item.label}: added to Buckler Settings → Screensaver"
    if item.kind == "schemes":
        set_widget(adb, item.id, True, "schemes")
        _mark_seen(adb, item)
        return f"{item.label}: added to Buckler Settings → Appearance → Colour scheme"
    if item.kind == "themes":
        _push_text(adb, json.dumps(item.raw, indent=2) + "\n", f"{DEVICE_ROOT}/themes/{item.id}.json")
        return f"{item.label}: installed; choose it in Buckler Settings → Appearance"
    if item.kind == "fonts":
        folder = (cache or apk_cache_dir()) / "store-fonts" / item.id
        remote = f"{DEVICE_ROOT}/fonts/{item.id}"
        adb.shell("mkdir", "-p", remote, check=False)
        for style in ("regular", "bold"):
            spec = item.raw["files"][style]
            local = fetch_verified(spec["url"], str(spec["sha256"]).lower(), folder / f"{style}.ttf")
            adb.push(str(local), f"{remote}/{style}.ttf")
        meta = {"id": item.id, "label": item.label, "revision": item.revision}
        _push_text(adb, json.dumps(meta) + "\n", f"{remote}/font.json")
        return f"{item.label}: installed; choose it in Buckler Settings → Appearance"
    pack = IconPackEntry(
        id=item.id, label=item.label, package=item.raw["package"], version=item.raw["version"],
        url=item.raw["url"], sha256=str(item.raw["sha256"]).lower(), license=item.raw["license"], why=item.about,
    )
    install_iconpack(adb, pack, cache)
    return f"{item.label}: installed; choose it in Buckler Settings → Appearance → Icons & artwork"


def remove(adb: Adb, item: StoreItem) -> str:
    adb.connect()
    if item.kind == "widgets":
        set_widget(adb, item.id, False)
        return f"{item.label}: removed from the Main Stage's choices (one on screen stays until changed)"
    if item.kind == "screensavers":
        set_widget(adb, item.id, False, "screensavers")
        return f"{item.label}: removed from Buckler Settings → Screensaver (the one in use stays until changed)"
    if item.kind == "schemes":
        set_widget(adb, item.id, False, "schemes")
        return f"{item.label}: removed from Buckler Settings → Appearance (the one in use stays until changed)"
    if item.kind == "themes":
        adb.shell("rm", "-f", f"{DEVICE_ROOT}/themes/{item.id}.json", check=False)
    elif item.kind == "fonts":
        adb.shell("rm", "-rf", f"{DEVICE_ROOT}/fonts/{item.id}", check=False)
    else:
        return adb.shell("pm", "uninstall", "--user", "0", item.raw["package"], check=False) or "uninstalled"
    return f"{item.label}: removed (Buckler goes back to a built-in choice if it was in use)"


def _saved_revision(adb: Adb, remote: str) -> int | None:
    """The revision in a saved scheme or font.json; None when the file is not there."""
    raw = adb.shell("cat", remote, check=False)
    try:
        doc = json.loads(raw)
    except json.JSONDecodeError:
        return None
    return max(1, int(doc.get("revision", 1))) if isinstance(doc, dict) else None


def _added(adb: Adb, item: StoreItem) -> bool:
    state = read_widgets(adb, item.kind)
    if item.id in state["removed"]:
        return False
    return item.id in state["added"] or bool(item.raw.get("default", True))


def outdated(adb: Adb, items: list[StoreItem]) -> list[Outdated]:
    """What the TV has at an older revision than [items] list, the way Buckler works it out."""
    app = package_version(adb, BUCKLER_PACKAGE) or "0"
    seen = read_seen(adb) or {}
    out = []
    for item in items:
        if item.kind in BUILT_INS:
            have = seen.get(item.id)
            if not _added(adb, item) or (have is not None and have >= item.revision):
                continue
            if not version_at_least(app, item.requires):
                out.append(Outdated(item, have or 0, needs_buckler=True))
            elif have is not None:
                out.append(Outdated(item, have))
        elif item.kind == "themes":
            have = _saved_revision(adb, f"{DEVICE_ROOT}/themes/{item.id}.json")
            if have is not None and have < item.revision:
                out.append(Outdated(item, have))
        elif item.kind == "fonts":
            have = _saved_revision(adb, f"{DEVICE_ROOT}/fonts/{item.id}/font.json")
            if have is not None and have < item.revision:
                out.append(Outdated(item, have))
        else:
            version = package_version(adb, item.raw["package"])
            if version is not None and version != item.raw.get("version"):
                out.append(Outdated(item, 0))
    return out


def update(adb: Adb, items: list[StoreItem], ids: tuple[str, ...] = (), cache: Path | None = None) -> list[str]:
    """Bring outdated items up to date (only [ids] when given): downloads again, built-ins marked as seen."""
    adb.connect()
    lines = []
    for entry in outdated(adb, items):
        item = entry.item
        if ids and item.id not in ids:
            continue
        what = f": {item.changes}" if item.changes else ""
        if entry.needs_buckler:
            lines.append(f"{item.label}: update Buckler to {item.requires} for the newest version{what}")
        elif item.kind in BUILT_INS:
            seen = read_seen(adb) or {}
            write_seen(adb, {**seen, item.id: item.revision})
            lines.append(f"{item.label}: updated with Buckler{what}")
        else:
            install(adb, item, cache)
            lines.append(f"{item.label}: updated to revision {item.revision}{what}")
    return lines or ["Everything from the store is up to date"]


def installed(adb: Adb) -> dict[str, list[str]]:
    """What the TV has from the store, by kind."""
    adb.connect()

    def names(path: str, suffix: str = "") -> list[str]:
        out = adb.shell("ls", f"{DEVICE_ROOT}/{path}", check=False)
        return sorted(n.removesuffix(suffix) for n in out.split() if n.endswith(suffix) and "No such" not in out)

    return {
        "themes": names("themes", ".json"),
        "fonts": names("fonts"),
        "widgets added": read_widgets(adb)["added"],
        "widgets removed": read_widgets(adb)["removed"],
        "screensavers added": read_widgets(adb, "screensavers")["added"],
        "screensavers removed": read_widgets(adb, "screensavers")["removed"],
        "schemes added": read_widgets(adb, "schemes")["added"],
        "schemes removed": read_widgets(adb, "schemes")["removed"],
        "behind the store": [
            f"{o.item.id} (needs Buckler {o.item.requires})" if o.needs_buckler else f"{o.item.id} (revision {o.have} → {o.item.revision})"
            for o in outdated(adb, load_index())
        ],
    }
