import pytest

from buckler.release import ReleaseError, expected_digest, pick_assets

HEX = "ab" * 32


def _release(*names: str) -> dict:
    return {"tag_name": "v1", "assets": [{"name": n, "browser_download_url": f"https://x/{n}"} for n in names]}


def test_picks_the_apk_and_its_checksum() -> None:
    apk, checksum = pick_assets(_release("buckler-home-1.apk", "buckler-home-1.apk.sha256", "notes.txt"))
    assert apk["name"] == "buckler-home-1.apk"
    assert checksum["name"] == "buckler-home-1.apk.sha256"


def test_refuses_a_release_without_a_checksum() -> None:
    with pytest.raises(ReleaseError):
        pick_assets(_release("buckler-home-1.apk"))


def test_refuses_more_than_one_apk() -> None:
    with pytest.raises(ReleaseError):
        pick_assets(_release("a.apk", "b.apk", "a.apk.sha256"))


def test_reads_a_sha256sum_line() -> None:
    assert expected_digest(f"{HEX.upper()}  buckler-home-1.apk\n") == HEX


@pytest.mark.parametrize("text", ["", "nothex  x.apk", "abc  x.apk"])
def test_refuses_a_bad_checksum_file(text: str) -> None:
    with pytest.raises(ReleaseError):
        expected_digest(text)
