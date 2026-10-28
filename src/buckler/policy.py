"""Safety policy: never-list, HOME protection."""

from __future__ import annotations

from buckler.adb import Adb
from buckler.profiles import load_never

KNOWN_STOCK_LAUNCHERS = frozenset(
    {
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
        "com.mitv.tvhome",
    }
)

OUR_LAUNCHER = "dev.solon.bucklerhome"
OUR_HOME_ACTIVITY = "dev.solon.bucklerhome/.MainActivity"

# Packages that must remain enabled for AI upscaling / display
AI_STACK = frozenset(
    {
        "com.nvidia.NvCPLSvc",
        "com.nvidia.nvaudiosvc",
        "com.nvidia.avsync",
        "com.dolby.android.audio.service",
    }
)


def filter_allowed(packages: list[str]) -> tuple[list[str], list[str]]:
    never = load_never()
    allowed: list[str] = []
    blocked: list[str] = []
    for pkg in packages:
        if pkg in never:
            blocked.append(pkg)
        else:
            allowed.append(pkg)
    return allowed, blocked


def can_disable_home_owner(adb: Adb, package: str) -> tuple[bool, str]:
    """Return whether it is safe to disable this package as a HOME owner."""
    if package not in KNOWN_STOCK_LAUNCHERS:
        return False, f"{package} is not a recognised stock launcher"
    home = adb.home_package()
    if home == OUR_LAUNCHER:
        return True, f"HOME already resolved to {OUR_LAUNCHER}"
    if home != package:
        # Disabling a stock launcher that is not current HOME is fine
        return True, f"current HOME is {home}, not {package}"
    # Current HOME is stock — only OK if our launcher is installed
    if not adb.package_present(OUR_LAUNCHER):
        return False, f"{OUR_LAUNCHER} is not installed; refusing to disable HOME owner {package}"
    return True, f"{OUR_LAUNCHER} is installed; will disable stock after set-home"


def verify_ai_stack(adb: Adb) -> list[str]:
    """Return AI-stack packages that are missing or disabled (problems)."""
    problems: list[str] = []
    for pkg in sorted(AI_STACK):
        if not adb.package_present(pkg):
            problems.append(f"{pkg}: MISSING")
        elif adb.package_disabled(pkg):
            problems.append(f"{pkg}: DISABLED")
    return problems
