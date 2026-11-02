"""bucklerStore from a computer: widgets, colour schemes, fonts and icon packs for buckler-home.

The store's catalog is index.json in github.com/asafelobotomy/bucklerStore; a copy ships with
Buckler (data/store/index.json, also bundled in the APK). Installing puts files where buckler-home
looks for them, `Android/data/dev.solon.bucklerhome/files/store/` on the TV (see StoreLibrary.kt):
`themes/<id>.json`, `fonts/<id>/{regular,bold}.ttf` with `font.json`, and `widgets.json`.
Icon packs are apps and go through `adb install`. Every download is checked against its sha256.
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
KINDS = ("widgets", "themes", "fonts", "iconpacks")
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


def read_widgets(adb: Adb) -> dict[str, list[str]]:
    raw = adb.shell("cat", f"{DEVICE_ROOT}/widgets.json", check=False)
    try:
        doc = json.loads(raw)
    except json.JSONDecodeError:
        doc = {}
    return {key: sorted({str(v) for v in doc.get(key, [])}) for key in ("added", "removed")}


def set_widget(adb: Adb, ident: str, on: bool) -> None:
    state = read_widgets(adb)
    added, removed = set(state["added"]), set(state["removed"])
    (added.add if on else added.discard)(ident)
    (removed.discard if on else removed.add)(ident)
    doc = {"added": sorted(added), "removed": sorted(removed)}
    _push_text(adb, json.dumps(doc, indent=2) + "\n", f"{DEVICE_ROOT}/widgets.json")


def install(adb: Adb, item: StoreItem, cache: Path | None = None) -> str:
    """Put [item] on the TV; returns a line saying what was done."""
    adb.connect()
    if item.kind == "widgets":
        set_widget(adb, item.id, True)
        return f"{item.label}: added to the Main Stage's choices"
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
        _push_text(adb, json.dumps({"id": item.id, "label": item.label}) + "\n", f"{remote}/font.json")
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
    if item.kind == "themes":
        adb.shell("rm", "-f", f"{DEVICE_ROOT}/themes/{item.id}.json", check=False)
    elif item.kind == "fonts":
        adb.shell("rm", "-rf", f"{DEVICE_ROOT}/fonts/{item.id}", check=False)
    else:
        return adb.shell("pm", "uninstall", "--user", "0", item.raw["package"], check=False) or "uninstalled"
    return f"{item.label}: removed (Buckler goes back to a built-in choice if it was in use)"


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
    }
