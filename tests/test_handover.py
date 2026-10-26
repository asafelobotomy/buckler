from pathlib import Path

from fakes import FakeAdb

import buckler.launcher_ops as lo
from buckler.launcher_ops import handover
from buckler.policy import OUR_LAUNCHER


def test_handover_disables_all_known_stock_launchers(journals_dir: Path) -> None:
    adb = FakeAdb(
        present={
            OUR_LAUNCHER,
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher",
        },
        home="com.google.android.tvlauncher/.MainActivity",
    )

    result = handover(adb)
    assert result.success
    assert "com.google.android.tvlauncher" in adb.disabled
    assert "com.google.android.apps.tv.launcherx" in adb.disabled
    assert "com.google.android.leanbacklauncher" in adb.disabled
    assert result.journal_path is not None
    assert result.journal_path.exists()
    assert result.journal_path.parent == journals_dir


def test_handover_rolls_back_if_home_not_ours(journals_dir: Path) -> None:
    adb = FakeAdb(
        present={OUR_LAUNCHER, "com.google.android.tvlauncher"},
        home="com.google.android.tvlauncher/.MainActivity",
    )

    def set_home(_component: str) -> str:
        return "noop"

    adb.set_home_activity = set_home  # type: ignore[method-assign]

    result = handover(adb)
    assert not result.success
    assert "com.google.android.tvlauncher" not in adb.disabled
    assert "stock re-enabled" in result.message
    assert journals_dir.is_dir()


def test_handover_refuses_without_our_launcher(journals_dir: Path) -> None:
    adb = FakeAdb(
        present={"com.google.android.tvlauncher"},
        home="com.google.android.tvlauncher/.MainActivity",
    )
    original = lo.install_launcher

    def no_install(adb_arg, apk=None):
        return "skipped"

    lo.install_launcher = no_install  # type: ignore[assignment]
    try:
        result = handover(adb)
    finally:
        lo.install_launcher = original  # type: ignore[assignment]

    assert not result.success
    assert "failed to install" in result.message
    assert journals_dir.is_dir()
