"""Open icon packs Buckler can install on the TV (catalog/iconpacks.yaml), verified by sha256."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import yaml

from buckler.adb import Adb
from buckler.app_catalog import CatalogEntry, CatalogError
from buckler.paths import catalog_dir
from buckler.wizard_apps import fetch_verified_apk


@dataclass(frozen=True)
class IconPackEntry:
    id: str
    label: str
    package: str
    version: str
    url: str
    sha256: str
    license: str
    why: str


def load_iconpacks(path: Path | None = None) -> list[IconPackEntry]:
    raw = yaml.safe_load((path or catalog_dir() / "iconpacks.yaml").read_text(encoding="utf-8")) or {}
    items = raw.get("entries", []) if isinstance(raw, dict) else None
    if not isinstance(items, list):
        raise CatalogError("iconpacks.yaml: expected a mapping with an `entries` list")
    fields = set(IconPackEntry.__dataclass_fields__)
    packs = []
    for item in items:
        if not isinstance(item, dict) or set(item) != fields:
            raise CatalogError(f"iconpacks.yaml: each entry needs exactly {sorted(fields)}")
        pack = IconPackEntry(**{key: str(value) for key, value in item.items()})
        sha = pack.sha256.lower()
        if not pack.url.startswith("https://") or len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha):
            raise CatalogError(f"icon pack {pack.id}: needs an https url and a hex sha256")
        packs.append(IconPackEntry(**{**pack.__dict__, "sha256": sha}))
    return packs


def require(packs: list[IconPackEntry], pack_id: str) -> IconPackEntry:
    for pack in packs:
        if pack.id == pack_id:
            return pack
    known = ", ".join(p.id for p in packs)
    raise CatalogError(f"unknown icon pack {pack_id!r} (known: {known})")


def install_iconpack(adb: Adb, pack: IconPackEntry, cache: Path | None = None) -> str:
    """Download (cached, checked against the pinned sha256) and install the pack on the TV."""
    entry = CatalogEntry(id=pack.id, label=pack.label, group="iconpacks", dock="append",
                         package=pack.package, version=pack.version, url=pack.url, sha256=pack.sha256)
    apk = fetch_verified_apk(entry, cache)
    adb.connect()
    out = adb.install_apk(str(apk))
    if not adb.package_present(pack.package):
        raise CatalogError(f"{pack.label} did not install: {out}")
    return out
