from fakes import FakeAdb

from buckler.policy import OUR_LAUNCHER
from buckler.tune import OUR_DREAM, doctor


def _ai_present() -> set[str]:
    return {
        "com.nvidia.NvCPLSvc",
        "com.nvidia.nvaudiosvc",
        "com.nvidia.avsync",
        "com.dolby.android.audio.service",
        OUR_LAUNCHER,
        "com.google.android.apps.mediashell",
    }


def test_doctor_hard_fail_on_wrong_home() -> None:
    adb = FakeAdb(
        present=_ai_present(),
        home="com.google.android.tvlauncher/.MainActivity",
        settings={("secure", "screensaver_components"): OUR_DREAM},
    )
    report = doctor(adb)
    assert not report.ok


def test_doctor_ok_with_screensaver_warning_only() -> None:
    adb = FakeAdb(
        present=_ai_present(),
        home=f"{OUR_LAUNCHER}/.MainActivity",
        settings={("secure", "screensaver_components"): "com.google.android.backdrop/.Dream"},
    )
    report = doctor(adb)
    assert report.ok
    assert any("screensaver" in w for w in report.warnings)


def test_doctor_fails_if_mediashell_disabled() -> None:
    adb = FakeAdb(
        present=_ai_present(),
        disabled={"com.google.android.apps.mediashell"},
        home=f"{OUR_LAUNCHER}/.MainActivity",
        settings={("secure", "screensaver_components"): OUR_DREAM},
    )
    report = doctor(adb)
    assert not report.ok
