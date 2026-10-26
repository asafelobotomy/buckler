"""Snappiness, DNS, screensaver, doctor helpers."""

from __future__ import annotations

from dataclasses import dataclass, field

from buckler.adb import Adb
from buckler.journal import Journal
from buckler.paths import journals_dir
from buckler.policy import OUR_LAUNCHER, verify_ai_stack

OUR_DREAM = "dev.solon.bucklerhome/.ClockDream"
BACKDROP = "com.google.android.backdrop"


@dataclass
class DoctorReport:
    """Health check result.

    ``ok`` is False only for hard failures (HOME, AI stack, Chromecast policy).
    Soft issues (screensaver mismatch, Network debugging reminder) go in
    ``warnings`` and do not fail the exit code.
    """

    lines: list[str]
    ok: bool
    warnings: list[str] = field(default_factory=list)


def set_animation_scales(adb: Adb, scale: float = 0.5) -> None:
    adb.connect()
    value = str(scale)
    for key in (
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale",
    ):
        adb.shell("settings", "put", "global", key, value)


def compile_launcher_speed(adb: Adb) -> str:
    adb.connect()
    return adb.shell("cmd", "package", "compile", "-m", "speed", "-f", OUR_LAUNCHER)


def set_private_dns(adb: Adb, hostname: str) -> None:
    adb.connect()
    adb.shell("settings", "put", "global", "private_dns_mode", "hostname")
    adb.shell("settings", "put", "global", "private_dns_specifier", hostname)


def clear_private_dns(adb: Adb) -> None:
    adb.connect()
    adb.shell("settings", "put", "global", "private_dns_mode", "off")


def set_our_screensaver(adb: Adb) -> None:
    """Point secure screensaver settings at buckler-home ClockDream."""
    adb.connect()
    if not adb.package_present(OUR_LAUNCHER):
        raise RuntimeError(f"{OUR_LAUNCHER} not installed")
    adb.shell("settings", "put", "secure", "screensaver_components", OUR_DREAM)
    adb.shell("settings", "put", "secure", "screensaver_default_component", OUR_DREAM)


def disable_backdrop(adb: Adb) -> Journal:
    adb.connect()
    set_our_screensaver(adb)
    journal = Journal(host=adb.host, profile="screensaver")
    if adb.package_present(BACKDROP) and not adb.package_disabled(BACKDROP):
        adb.disable_user(BACKDROP)
        journal.add(
            BACKDROP,
            before_disabled=False,
            after_disabled=True,
            reason="replaced by buckler-home ClockDream",
        )
    journal.notes.append(f"screensaver={OUR_DREAM}")
    journal.save(journals_dir() / "screensaver.json")
    return journal


def doctor(adb: Adb) -> DoctorReport:
    adb.connect()
    lines: list[str] = []
    warnings: list[str] = []
    ok = True

    home = adb.resolve_home()
    lines.append(f"home: {home}")
    if OUR_LAUNCHER not in home:
        ok = False
        lines.append("  ! HOME is not buckler-home")

    ai = verify_ai_stack(adb)
    if ai:
        ok = False
        lines.extend(f"  ! AI {p}" for p in ai)
    else:
        lines.append("AI stack: OK")

    # Force-stop sticky disabled packages that still show a process
    disabled = set(adb.list_packages("-d"))
    sticky = []
    ps = adb.shell("ps", "-A", check=False)
    for pkg in sorted(disabled):
        if pkg in ps:
            sticky.append(pkg)
            adb.shell("am", "force-stop", pkg, check=False)
    if sticky:
        lines.append(f"force-stopped sticky disabled pkgs ({len(sticky)}):")
        for pkg in sticky:
            lines.append(f"  - {pkg}")
    else:
        lines.append("sticky disabled processes: none")

    dream = adb.shell("settings", "get", "secure", "screensaver_components", check=False)
    lines.append(f"screensaver: {dream}")
    if OUR_DREAM not in dream:
        msg = "screensaver is not buckler-home ClockDream"
        warnings.append(msg)
        lines.append(f"  ~ {msg}")

    mediashell = "com.google.android.apps.mediashell"
    if adb.package_present(mediashell) and adb.package_disabled(mediashell):
        ok = False
        lines.append("  ! Chromecast mediashell is disabled (policy: keep)")
    else:
        lines.append("Chromecast mediashell: kept")

    anim = adb.shell("settings", "get", "global", "window_animation_scale", check=False)
    lines.append(f"window_animation_scale: {anim}")

    dns = adb.shell("settings", "get", "global", "private_dns_mode", check=False)
    lines.append(f"private_dns_mode: {dns}")

    reminder = "turn Network debugging OFF in Developer options when finished"
    warnings.append(reminder)
    lines.append(f"reminder: {reminder}")
    return DoctorReport(lines=lines, ok=ok, warnings=warnings)
