"""Core operations: probe, audit, apply, restore, check."""

from __future__ import annotations

from dataclasses import dataclass, field
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
from buckler.profiles import SETUP_PROFILES, list_profiles, load_profile


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
    third_party: list[str] = field(default_factory=list)


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
        third_party=adb.list_packages("-3"),
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

    adb.connect()
    profile = load_profile(profile_name)
    allowed, blocked = filter_allowed(profile.packages)
    journal = Journal(host=adb.host, profile=profile_name)
    skipped_missing: list[str] = []
    skipped_already: list[str] = []
    changed: list[str] = []

    home_pkg = adb.home_package()

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
    """Compare desired disabled set (from journals/profiles) to device.

    Returns (drifted_should_be_disabled, ai_problems).
    Default desired set is SETUP_PROFILES (same as ``buckler setup``).
    """
    adb.connect()
    desired: set[str] = set()
    if profile_name:
        desired.update(load_profile(profile_name).packages)
    else:
        for name in SETUP_PROFILES:
            desired.update(load_profile(name).packages)

    for path in iter_journals():
        j = Journal.load(path)
        for e in j.entries:
            if e.action == "disable-user":
                desired.add(e.package)

    allowed, _ = filter_allowed(sorted(desired))
    drifted: list[str] = []
    for pkg in allowed:
        if not adb.package_present(pkg):
            continue
        if not adb.package_disabled(pkg):
            drifted.append(pkg)
            if fix:
                if pkg in KNOWN_STOCK_LAUNCHERS:
                    ok, _reason = can_disable_home_owner(adb, pkg)
                    if not ok:
                        continue
                    if adb.home_package() == pkg and not adb.package_present(OUR_LAUNCHER):
                        continue
                adb.disable_user(pkg)
                adb.force_stop(pkg)

    ai = verify_ai_stack(adb)
    return drifted, ai


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
