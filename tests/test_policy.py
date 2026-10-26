from fakes import FakeAdb

from buckler.policy import (
    AI_STACK,
    OUR_LAUNCHER,
    can_disable_home_owner,
    filter_allowed,
    verify_ai_stack,
)
from buckler.profiles import load_never


def test_never_blocks_ai_stack() -> None:
    never = load_never()
    assert "com.nvidia.NvCPLSvc" in never
    allowed, blocked = filter_allowed(
        ["com.nvidia.stats", "com.nvidia.NvCPLSvc", "com.google.android.katniss"]
    )
    assert "com.nvidia.stats" in allowed
    assert "com.google.android.katniss" in allowed
    assert "com.nvidia.NvCPLSvc" in blocked


def test_never_covers_ai_gms_settings_remote() -> None:
    never = load_never()
    for pkg in AI_STACK:
        assert pkg in never
    for pkg in (
        "com.google.android.gms",
        "com.android.bluetooth",
        "com.android.tv.settings",
        "com.google.android.tv.remote.service",
    ):
        assert pkg in never


def test_can_disable_home_refuses_without_ours() -> None:
    adb = FakeAdb(
        present={"com.google.android.tvlauncher"},
        home="com.google.android.tvlauncher/.MainActivity",
    )
    ok, reason = can_disable_home_owner(adb, "com.google.android.tvlauncher")
    assert not ok
    assert OUR_LAUNCHER in reason


def test_can_disable_home_ok_when_ours_installed() -> None:
    adb = FakeAdb(
        present={"com.google.android.tvlauncher", OUR_LAUNCHER},
        home="com.google.android.tvlauncher/.MainActivity",
    )
    ok, _ = can_disable_home_owner(adb, "com.google.android.tvlauncher")
    assert ok


def test_can_disable_non_current_stock_launcher() -> None:
    adb = FakeAdb(
        present={
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            OUR_LAUNCHER,
        },
        home=f"{OUR_LAUNCHER}/.MainActivity",
    )
    ok, _ = can_disable_home_owner(adb, "com.google.android.apps.tv.launcherx")
    assert ok


def test_can_disable_rejects_unknown_package() -> None:
    adb = FakeAdb()
    ok, reason = can_disable_home_owner(adb, "com.example.notalauncher")
    assert not ok
    assert "not a recognised" in reason


def test_verify_ai_stack_reports_missing_and_disabled() -> None:
    adb = FakeAdb(present={"com.nvidia.NvCPLSvc"}, disabled={"com.nvidia.NvCPLSvc"})
    problems = verify_ai_stack(adb)
    assert any("NvCPLSvc: DISABLED" in p for p in problems)
    assert any("MISSING" in p for p in problems)
