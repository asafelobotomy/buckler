"""Snappiness, DNS, screensaver, doctor helpers."""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from buckler.adb import Adb
from buckler.journal import Journal, journaled_packages
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


_HOSTNAME = re.compile(r"^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}$")


def set_private_dns(adb: Adb, hostname: str) -> None:
    """Point Private DNS at [hostname]; refuses anything that is not a plain DNS name."""
    if not _HOSTNAME.match(hostname):
        raise ValueError(f"Not a DNS hostname: {hostname!r}")
    adb.connect()
    adb.shell("settings", "put", "global", "private_dns_mode", "hostname")
    adb.shell("settings", "put", "global", "private_dns_specifier", hostname)


def clear_private_dns(adb: Adb) -> None:
    adb.connect()
    adb.shell("settings", "put", "global", "private_dns_mode", "off")


def get_private_dns(adb: Adb) -> tuple[str, str]:
    """Return (private_dns_mode, private_dns_specifier)."""
    adb.connect()
    mode = adb.shell("settings", "get", "global", "private_dns_mode", check=False).strip()
    spec = adb.shell(
        "settings", "get", "global", "private_dns_specifier", check=False
    ).strip()
    return mode or "null", spec or "null"


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
        # Journal first: a stop between the two steps still leaves a way back.
        journal.add(
            BACKDROP,
            before_disabled=False,
            after_disabled=True,
            reason="replaced by buckler-home ClockDream",
        )
        journal.save(journals_dir() / "screensaver.json", merge=True)
        adb.disable_user(BACKDROP)
    elif adb.package_present(BACKDROP) and BACKDROP not in journaled_packages():
        journal.add(
            BACKDROP,
            before_disabled=False,
            after_disabled=True,
            reason="found disabled; recorded so restore re-enables it",
        )
    journal.notes.append(f"screensaver={OUR_DREAM}")
    journal.save(journals_dir() / "screensaver.json", merge=True)
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
    # The last column of `ps -A` is the process name: "pkg" or "pkg:service". A substring test
    # would match com.nvidia.factory inside com.nvidia.factorybundling.
    running = {line.split()[-1].split(":")[0] for line in adb.shell("ps", "-A", check=False).splitlines()[1:] if line.split()}
    for pkg in sorted(disabled & running):
        sticky.append(pkg)
        adb.force_stop(pkg)
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
