"""Open app catalog: stock swaps and optional suggestions.

Installing an entry never disables its stock package. Dock actions are
replace-pin, unpin-only, or append.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from buckler.paths import catalog_dir
from buckler.profiles import load_never

DOCK_ACTIONS = frozenset({"replace-pin", "unpin-only", "append"})
_SHA256_LEN = 64


class CatalogError(ValueError):
    """Raised when the catalog or a requested id is invalid."""


@dataclass(frozen=True)
class CatalogEntry:
    id: str
    label: str
    group: str
    dock: str
    stock_label: str = ""
    stock_package: str = ""
    package: str = ""
    version: str = ""
    url: str = ""
    sha256: str = ""
    note: str = ""

    def prompt(self) -> str:
        detail = f" {self.note}" if self.note else ""
        if self.dock == "replace-pin":
            return f"Pin {self.label} instead of {self.stock_label}?{detail}"
        if self.dock == "unpin-only":
            return f"Unpin {self.stock_label} from the dock?{detail}"
        return f"Install {self.label}?{detail}"


@dataclass(frozen=True)
class Catalog:
    swaps: tuple[CatalogEntry, ...]
    suggestions: tuple[CatalogEntry, ...]

    @property
    def entries(self) -> tuple[CatalogEntry, ...]:
        return self.swaps + self.suggestions

    def require(self, group: str, app_id: str) -> CatalogEntry:
        pool = self.swaps if group == "swaps" else self.suggestions
        for entry in pool:
            if entry.id == app_id:
                return entry
        kind = "alternative" if group == "swaps" else "suggestion"
        known = ", ".join(entry.id for entry in pool) or "(none)"
        raise CatalogError(f"unknown {kind} {app_id!r} (known: {known})")

    def require_known(self, swaps: tuple[str, ...], suggestions: tuple[str, ...]) -> None:
        for app_id in swaps:
            self.require("swaps", app_id)
        for app_id in suggestions:
            self.require("suggestions", app_id)


def apply_dock(dock: list[str], chosen: list[CatalogEntry]) -> list[str]:
    """Return a new dock. Stock packages are unpinned, never marked for disable."""
    result = list(dock)
    for entry in chosen:
        if entry.dock == "replace-pin":
            if entry.stock_package in result:
                index = result.index(entry.stock_package)
                result[index] = entry.package
            elif entry.package not in result:
                result.append(entry.package)
        elif entry.dock == "unpin-only":
            result = [pkg for pkg in result if pkg != entry.stock_package]
        elif entry.package not in result:
            result.append(entry.package)
    return result


def load_catalog(root: Path | None = None) -> Catalog:
    base = root if root is not None else catalog_dir()
    swaps = _load_group(base / "alternatives.yaml", "swaps")
    suggestions = _load_group(base / "suggestions.yaml", "suggestions")
    ids = [entry.id for entry in swaps + suggestions]
    if len(ids) != len(set(ids)):
        raise CatalogError("duplicate catalog id")
    blocked = load_never()
    for entry in swaps + suggestions:
        if entry.stock_package and entry.stock_package in blocked:
            raise CatalogError(
                f"{entry.id}: stock package {entry.stock_package} is on the never-list"
            )
        if entry.package and entry.package in blocked:
            raise CatalogError(f"{entry.id}: package {entry.package} is on the never-list")
    return Catalog(swaps=tuple(swaps), suggestions=tuple(suggestions))


def _load_group(path: Path, group: str) -> list[CatalogEntry]:
    if not path.is_file():
        raise CatalogError(f"missing catalog file: {path}")
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise CatalogError(f"{path.name} must be a mapping")
    raw_entries = data.get("entries")
    if not isinstance(raw_entries, list):
        raise CatalogError(f"{path.name} entries must be a list")
    return [_parse_entry(item, group, path.name) for item in raw_entries]


def _parse_entry(item: Any, group: str, filename: str) -> CatalogEntry:
    if not isinstance(item, dict):
        raise CatalogError(f"{filename} entry must be a mapping")
    app_id = _text(item, "id", filename)
    dock = _text(item, "dock", filename)
    if dock not in DOCK_ACTIONS:
        raise CatalogError(f"{app_id}: dock must be replace-pin, unpin-only, or append")
    note = str(item.get("note") or item.get("why") or "").strip()
    if not note:
        raise CatalogError(f"{app_id}: note is required")
    entry = CatalogEntry(
        id=app_id,
        label=_text(item, "label", filename),
        group=group,
        dock=dock,
        stock_label=str(item.get("stock_label") or "").strip(),
        stock_package=str(item.get("stock_package") or "").strip(),
        package=str(item.get("package") or "").strip(),
        version=str(item.get("version") or "").strip(),
        url=str(item.get("url") or "").strip(),
        sha256=str(item.get("sha256") or "").strip().lower(),
        note=note,
    )
    _validate_entry(entry)
    return entry


def _validate_entry(entry: CatalogEntry) -> None:
    if entry.dock == "unpin-only":
        if not entry.stock_package:
            raise CatalogError(f"{entry.id}: unpin-only requires stock_package")
        if entry.url or entry.sha256 or entry.package:
            raise CatalogError(f"{entry.id}: unpin-only has no apk")
        return
    if not entry.package:
        raise CatalogError(f"{entry.id}: package is required")
    if not entry.url.startswith("https://"):
        raise CatalogError(f"{entry.id}: url must be https")
    if len(entry.sha256) != _SHA256_LEN or any(c not in "0123456789abcdef" for c in entry.sha256):
        raise CatalogError(f"{entry.id}: sha256 must be 64 hex chars")
    if entry.dock == "replace-pin" and not entry.stock_package:
        raise CatalogError(f"{entry.id}: replace-pin requires stock_package")


def _text(item: dict[str, Any], key: str, filename: str) -> str:
    value = item.get(key)
    if not isinstance(value, str) or not value.strip():
        raise CatalogError(f"{filename}: {key} must be a non-empty string")
    return value.strip()
