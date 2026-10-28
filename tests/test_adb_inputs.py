import pytest

from buckler.adb import Adb, AdbError, checked_package
from buckler.tune import set_private_dns


@pytest.mark.parametrize("name", ["com.nvidia.ota", "dev.solon.bucklerhome", "com.google.android.tv", "a.b_c.D9"])
def test_real_package_names_pass(name: str) -> None:
    assert checked_package(name) == name


@pytest.mark.parametrize("name", ["", "nodots", "com.x;reboot", "com.x && rm -rf /", "com.x\nreboot", "1com.x", "com..x", "$(id).x"])
def test_shell_syntax_is_refused(name: str) -> None:
    with pytest.raises(AdbError):
        checked_package(name)


def _offline_adb() -> Adb:
    """An Adb that never runs adb (and needs none installed): every check below comes first."""
    adb = object.__new__(Adb)
    adb.host, adb.adb_bin = "192.0.2.1:5555", "adb"
    return adb


def test_adb_refuses_bad_names_before_running_anything() -> None:
    adb = _offline_adb()
    with pytest.raises(AdbError, match="Not a package name"):
        adb.disable_user("com.x;reboot")
    with pytest.raises(AdbError, match="Not a component name"):
        adb.set_home_activity("dev.solon.bucklerhome/.Main;reboot")


@pytest.mark.parametrize("hostname", ["x;reboot", "dns.google;id", "localhost", "-bad.example.com", "a b.com"])
def test_private_dns_takes_only_hostnames(hostname: str) -> None:
    with pytest.raises(ValueError):
        set_private_dns(_offline_adb(), hostname)
