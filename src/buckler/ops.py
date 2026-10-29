"""Core operations: probe, audit, apply, restore, check."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from buckler.adb import Adb
from buckler.journal import Journal, iter_journals
from buckler.policy import (
    KNOWN_STOCK_LAUNCHERS,
    OUR_LAUNCHER,
    can_disable_home_owner,
    filter_allowed,
    verify_ai_stack,
)
from buckler.profiles import SETUP_PROFILES, Profile, list_profiles, load_profile


@dataclass
class ProbeResult:
    host: str
    model: str
    device: str
    release: str
    sdk: str
    build: str
    security_patch: str
    home: str
    package_count: int
    disabled: list[str]
    ai_problems: list[str]


def probe(adb: Adb) -> ProbeResult:
    adb.connect()
    disabled = adb.list_packages("-d")
    return ProbeResult(
        host=adb.host,
        model=adb.getprop("ro.product.model"),
        device=adb.getprop("ro.product.device"),
        release=adb.getprop("ro.build.version.release"),
        sdk=adb.getprop("ro.build.version.sdk"),
        build=adb.getprop("ro.build.display.id"),
        security_patch=adb.getprop("ro.build.version.security_patch"),
        home=adb.resolve_home(),
        package_count=len(adb.list_packages()),
        disabled=disabled,
        ai_problems=verify_ai_stack(adb),
    )


@dataclass
class AuditRow:
    package: str
    profile: str
    state: str  # ENABLED / DISABLED / MISSING / BLOCKED


def audit(adb: Adb) -> list[AuditRow]:
    adb.connect()
    rows: list[AuditRow] = []
    for name in list_profiles() + ["never"]:
        profile = load_profile(name)
        for pkg in profile.packages:
            if name == "never":
                state = (
                    "MISSING"
                    if not adb.package_present(pkg)
                    else ("DISABLED" if adb.package_disabled(pkg) else "ENABLED")
                )
                rows.append(AuditRow(pkg, "never", state))
                continue
            if not adb.package_present(pkg):
                rows.append(AuditRow(pkg, name, "MISSING"))
            elif adb.package_disabled(pkg):
                rows.append(AuditRow(pkg, name, "DISABLED"))
            else:
                rows.append(AuditRow(pkg, name, "ENABLED"))
    return rows


@dataclass
class ApplyResult:
    journal: Journal
    skipped_missing: list[str]
    skipped_already: list[str]
    blocked: list[str]
    changed: list[str]
    dry_run: bool


def apply_profile(
    adb: Adb,
    profile_name: str,
    *,
    dry_run: bool = False,
) -> ApplyResult:
    if profile_name == "home-clean":
        raise ValueError("Use `buckler launcher handover` for home-clean")
    if profile_name == "never":
        raise ValueError("The never profile cannot be applied")
    return apply_packages(adb, load_profile(profile_name), dry_run=dry_run)


def apply_packages(adb: Adb, profile: Profile, *, dry_run: bool = False) -> ApplyResult:
    """Disable a profile's packages (from YAML, or built in code), journaled for restore."""
    profile_name = profile.name
    adb.connect()
    allowed, blocked = filter_allowed(profile.packages)
    journal = Journal(host=adb.host, profile=profile_name)
    skipped_missing: list[str] = []
    skipped_already: list[str] = []
    changed: list[str] = []

    home_pkg = adb.home_package()

    try:
        _apply_packages(adb, allowed, home_pkg, profile, profile_name, dry_run, journal,
                        blocked, skipped_missing, skipped_already, changed)
    except BaseException:
        # Any stop (adb error, Ctrl-C) keeps a record of what was already disabled, so restore works.
        if journal.entries and not dry_run:
            journal.notes.append("apply stopped early; entries cover what changed")
            journal.save()
        raise

    for pkg in blocked:
        journal.notes.append(f"blocked by never list: {pkg}")

    # Already-disabled profile pkgs can still leave sticky processes (GameStream/OSC).
    if not dry_run:
        for pkg in skipped_already:
            adb.force_stop(pkg)

    if not dry_run:
        journal.save()
    else:
        journal.notes.append("dry-run: journal not written")

    return ApplyResult(
        journal=journal,
        skipped_missing=skipped_missing,
        skipped_already=skipped_already,
        blocked=blocked,
        changed=changed,
        dry_run=dry_run,
    )


def _apply_packages(
    adb: Adb,
    allowed: list[str],
    home_pkg: str,
    profile: Profile,
    profile_name: str,
    dry_run: bool,
    journal: Journal,
    blocked: list[str],
    skipped_missing: list[str],
    skipped_already: list[str],
    changed: list[str],
) -> None:
    for pkg in allowed:
        if not adb.package_present(pkg):
            skipped_missing.append(pkg)
            continue
        if pkg in KNOWN_STOCK_LAUNCHERS or pkg == home_pkg:
            ok, reason = can_disable_home_owner(adb, pkg)
            if not ok:
                blocked.append(pkg)
                journal.notes.append(f"skipped HOME-related {pkg}: {reason}")
                continue
        before = adb.package_disabled(pkg)
        if before:
            skipped_already.append(pkg)
            continue
        if dry_run:
            journal.add(pkg, before_disabled=False, after_disabled=True, reason=profile.description)
            changed.append(pkg)
            continue
        adb.disable_user(pkg)
        after = adb.package_disabled(pkg)
        journal.add(
            pkg,
            before_disabled=False,
            after_disabled=after,
            reason=profile.description or profile_name,
        )
        if after:
            changed.append(pkg)
            adb.force_stop(pkg)


def restore_journal(adb: Adb, path: Path) -> list[str]:
    adb.connect()
    journal = Journal.load(path)
    restored: list[str] = []
    for entry in journal.entries:
        if entry.action != "disable-user":
            continue
        if not adb.package_present(entry.package):
            continue
        if not adb.package_disabled(entry.package):
            continue
        adb.enable(entry.package)
        restored.append(entry.package)
    journal.mark_restored(restored)
    return restored


def restore_all_journals(adb: Adb) -> list[tuple[Path, list[str]]]:
    """Re-enable packages from every disable journal (skips snapshot-*.json)."""
    adb.connect()
    results: list[tuple[Path, list[str]]] = []
    for path in iter_journals():
        restored = restore_journal(adb, path)
        results.append((path, restored))
    return results


def check_drift(
    adb: Adb,
    *,
    profile_name: str | None = None,
    fix: bool = False,
) -> tuple[list[str], list[str]]:
    """Compare the desired disabled set to the device.

    Returns (drifted_should_be_disabled, ai_problems). Without ``profile_name`` the desired
    set is what Buckler actually disabled: every journal entry, plus the full package list of
    each setup profile that has a journal (so OTA re-enables of packages that were already off
    still count). Packages whose latest journal event is a restore are left alone, and profiles
    you skipped in setup are not added.
    """
    adb.connect()
    disables, restores, applied = _journal_events()
    desired: set[str] = set(disables)
    if profile_name:
        desired.update(load_profile(profile_name).packages)
    else:
        for name in SETUP_PROFILES:
            if name in applied:
                desired.update(load_profile(name).packages)
    desired = {p for p in desired if restores.get(p, "") <= disables.get(p, "")}

    allowed, _ = filter_allowed(sorted(desired))
    drifted: list[str] = []
    # Some of these were off before any profile ran, so no journal holds them yet.
    fixed = Journal(host=adb.host, profile="drift-fix")
    try:
        _fix_drift(adb, allowed, fix, drifted, fixed)
    finally:
        if fixed.entries:
            fixed.save()

    ai = verify_ai_stack(adb)
    return drifted, ai


def _fix_drift(adb: Adb, allowed: list[str], fix: bool, drifted: list[str], fixed: Journal) -> None:
    ours_is_home = None
    for pkg in allowed:
        if not adb.package_present(pkg):
            continue
        if adb.package_disabled(pkg):
            continue
        drifted.append(pkg)
        if not fix:
            continue
        if pkg in KNOWN_STOCK_LAUNCHERS:
            # Only with buckler-home enabled and resolved as HOME; otherwise the TV has none.
            if ours_is_home is None:
                ours_is_home = (
                    adb.home_package() == OUR_LAUNCHER and not adb.package_disabled(OUR_LAUNCHER)
                )
            if not ours_is_home:
                continue
        fixed.add(pkg, before_disabled=False, after_disabled=True, reason="re-disabled after drift (OTA)")
        adb.disable_user(pkg)
        adb.force_stop(pkg)


def _journal_events() -> tuple[dict[str, str], dict[str, str], set[str]]:
    """Latest disable time and latest restore time per package, and profiles with a journal."""
    disables: dict[str, str] = {}
    restores: dict[str, str] = {}
    applied: set[str] = set()
    for path in iter_journals():
        j = Journal.load(path)
        applied.add(j.profile)
        for e in j.entries:
            if e.action != "disable-user":
                continue
            disables[e.package] = max(disables.get(e.package, ""), j.created_at or "0")
            if e.restored_at:
                restores[e.package] = max(restores.get(e.package, ""), e.restored_at)
    return disables, restores, applied


def format_probe(p: ProbeResult) -> str:
    lines = [
        f"host:            {p.host}",
        f"model:           {p.model}",
        f"device:          {p.device}",
        f"android:         {p.release} (API {p.sdk})",
        f"build:           {p.build}",
        f"security_patch:  {p.security_patch}",
        f"home:            {p.home}",
        f"packages:        {p.package_count}",
        f"disabled ({len(p.disabled)}):",
    ]
    for pkg in p.disabled:
        lines.append(f"  - {pkg}")
    if p.ai_problems:
        lines.append("AI stack PROBLEMS:")
        for item in p.ai_problems:
            lines.append(f"  ! {item}")
    else:
        lines.append("AI stack:        OK (NvCPLSvc / audio / Dolby enabled)")
    return "\n".join(lines)
