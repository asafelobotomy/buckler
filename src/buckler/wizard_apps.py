"""Install catalog apps and rewrite the dock. Never disables stock packages."""

from __future__ import annotations

import hashlib
import json
import shutil
import urllib.request
from collections.abc import Callable
from pathlib import Path
from typing import Protocol

from buckler.adb import Adb
from buckler.app_catalog import CatalogEntry, CatalogError, apply_dock, load_catalog
from buckler.home_layout import (
    DEFAULT_DOCK,
    default_home_layout,
    default_layout_path,
    push_home_layout,
    write_home_layout,
)
from buckler.paths import apk_cache_dir
from buckler.wizard_phases import banner_for

Echo = Callable[[str], None]
Ask = Callable[[str, bool], bool]


class Reporter(Protocol):
    def add(
        self,
        name: str,
        ok: bool,
        detail: str = "",
        *,
        skipped: bool = False,
    ) -> None: ...


def run_app_phases(
    adb: Adb,
    report: Reporter,
    *,
    swaps: tuple[str, ...] = (),
    suggestions: tuple[str, ...] = (),
    dry_run: bool = False,
    ask: Ask | None = None,
    echo: Echo | None = None,
    push_layout: bool = False,
) -> dict[str, list[str]]:
    """Prompt or honor flags, install chosen APKs, and pin them when asked.

    A failed app is one failed step. The rest of the wizard continues.
    ``ask is None`` installs only the ids passed in (batch default: nothing).
    """
    empty: dict[str, list[str]] = {"swaps": [], "suggestions": []}
    try:
        catalog = load_catalog()
        catalog.require_known(swaps, suggestions)
    except CatalogError as exc:
        report.add("alternatives", False, str(exc))
        report.add("suggestions", False, "skipped after catalog error", skipped=True)
        _echo(echo, f"  {exc}")
        return empty

    chosen_swaps = _choose_group(
        adb,
        report,
        catalog.swaps,
        explicit=swaps,
        phase_id="alternatives",
        step_prefix="swap",
        kept_detail="kept stock",
        dry_run=dry_run,
        ask=ask,
        echo=echo,
        intro="Play Store, Play Services, Chromecast, the phone remote, and Settings stay.",
    )
    chosen_suggestions = _choose_group(
        adb,
        report,
        catalog.suggestions,
        explicit=suggestions,
        phase_id="suggestions",
        step_prefix="suggest",
        kept_detail="skipped",
        dry_run=dry_run,
        ask=ask,
        echo=echo,
        intro="",
    )
    chosen = chosen_swaps + chosen_suggestions
    _push_dock(adb, report, chosen, dry_run=dry_run, push_layout=push_layout, echo=echo)
    return {
        "swaps": [entry.id for entry in chosen_swaps],
        "suggestions": [entry.id for entry in chosen_suggestions],
    }


def fetch_verified_apk(entry: CatalogEntry, cache: Path | None = None) -> Path:
    if not entry.url:
        raise CatalogError(f"{entry.id} has no apk")
    folder = cache if cache is not None else apk_cache_dir()
    folder.mkdir(parents=True, exist_ok=True)
    dest = folder / f"{entry.id}-{entry.sha256}.apk"
    if dest.is_file() and _sha256(dest) == entry.sha256:
        return dest
    partial = dest.with_suffix(".partial")
    try:
        request = urllib.request.Request(entry.url, headers={"User-Agent": "buckler"})
        with (
            urllib.request.urlopen(request, timeout=300) as response,
            partial.open("wb") as handle,
        ):
            shutil.copyfileobj(response, handle)
        digest = _sha256(partial)
        if digest != entry.sha256:
            raise CatalogError(f"{entry.id}: sha256 mismatch (got {digest})")
        partial.replace(dest)
    except CatalogError:
        partial.unlink(missing_ok=True)
        raise
    except Exception as exc:
        partial.unlink(missing_ok=True)
        raise CatalogError(f"{entry.id}: download failed: {exc}") from exc
    return dest


def _choose_group(
    adb: Adb,
    report: Reporter,
    pool: tuple[CatalogEntry, ...],
    *,
    explicit: tuple[str, ...],
    phase_id: str,
    step_prefix: str,
    kept_detail: str,
    dry_run: bool,
    ask: Ask | None,
    echo: Echo | None,
    intro: str,
) -> list[CatalogEntry]:
    _echo(echo, banner_for(phase_id))
    if intro:
        _echo(echo, f"  {intro}")
    wanted = set(explicit)
    chosen: list[CatalogEntry] = []
    for entry in pool:
        take = entry.id in wanted
        if not take and ask is not None:
            take = ask(entry.prompt(), False)
        name = f"{step_prefix}:{entry.id}"
        if not take:
            report.add(name, True, kept_detail, skipped=True)
            _echo(echo, f"  {entry.label}: {kept_detail}")
            continue
        if _install_entry(adb, report, entry, name=name, dry_run=dry_run, echo=echo):
            chosen.append(entry)
    return chosen


def _install_entry(
    adb: Adb,
    report: Reporter,
    entry: CatalogEntry,
    *,
    name: str,
    dry_run: bool,
    echo: Echo | None,
) -> bool:
    if entry.dock == "unpin-only":
        detail = f"unpin {entry.stock_package}"
        if dry_run:
            report.add(name, True, f"would {detail}", skipped=True)
            _echo(echo, f"  {entry.label}: would {detail}")
            return True
        report.add(name, True, detail)
        _echo(echo, f"  {entry.label}: {detail}")
        return True
    if dry_run:
        report.add(name, True, f"would install {entry.package}", skipped=True)
        _echo(echo, f"  {entry.label}: would install {entry.package}")
        return True
    try:
        if adb.package_present(entry.package):
            detail = f"already installed {entry.package}"
        else:
            apk = fetch_verified_apk(entry)
            adb.install_apk(str(apk))
            detail = f"installed {entry.package}"
    except Exception as exc:  # noqa: BLE001 — one app must not abort the wizard
        report.add(name, False, str(exc))
        _echo(echo, f"  {entry.label}: FAIL {exc}")
        return False
    report.add(name, True, detail)
    _echo(echo, f"  {entry.label}: {detail}")
    return True


def _push_dock(
    adb: Adb,
    report: Reporter,
    chosen: list[CatalogEntry],
    *,
    dry_run: bool,
    push_layout: bool,
    echo: Echo | None,
) -> None:
    if not chosen:
        return
    updated = apply_dock(_base_dock(), chosen)
    if updated == _base_dock():
        return
    shown = " ".join(updated)
    if dry_run or not push_layout:
        why = "dry-run" if dry_run else "home layout not pushed"
        report.add("home_layout_apps", True, f"skipped ({why}): {shown}", skipped=True)
        _echo(echo, f"  dock skipped ({why})")
        return
    try:
        local = _write_dock(updated)
        remote = push_home_layout(adb, local)
    except Exception as exc:  # noqa: BLE001
        report.add("home_layout_apps", False, str(exc))
        _echo(echo, f"  dock FAIL {exc}")
        return
    report.add("home_layout_apps", True, remote)
    _echo(echo, f"  dock → {remote}")


def _base_dock() -> list[str]:
    path = default_layout_path()
    if not path.is_file():
        return list(DEFAULT_DOCK)
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return list(DEFAULT_DOCK)
    for shelf in doc.get("shelves", []):
        if isinstance(shelf, dict) and shelf.get("id") == "apps":
            packages = shelf.get("packages") or []
            if isinstance(packages, list) and all(isinstance(pkg, str) for pkg in packages):
                return list(packages)
    return list(DEFAULT_DOCK)


def _write_dock(packages: list[str]) -> Path:
    path = default_layout_path()
    if path.is_file():
        doc = json.loads(path.read_text(encoding="utf-8"))
        for shelf in doc.get("shelves", []):
            if isinstance(shelf, dict) and shelf.get("id") == "apps":
                shelf["packages"] = packages
        return write_home_layout(path, doc)
    return write_home_layout(path, default_home_layout(packages=packages))


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _echo(echo: Echo | None, msg: str) -> None:
    if echo:
        echo(msg)


# Re-export so callers can catch catalog failures without a second import.
__all__ = ["Ask", "CatalogError", "fetch_verified_apk", "run_app_phases"]
