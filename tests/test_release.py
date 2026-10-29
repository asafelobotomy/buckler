import hashlib
import io
import json
from pathlib import Path

import pytest

import buckler.release as release_mod
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


class _Resp(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *_a):
        self.close()


def _serve(monkeypatch: pytest.MonkeyPatch, apk: bytes, digest: str, fail: bool = False) -> list[str]:
    calls: list[str] = []
    release = {
        "tag_name": "v1",
        "assets": [
            {"name": "buckler-home.apk", "browser_download_url": "https://x/apk"},
            {"name": "buckler-home.apk.sha256", "browser_download_url": "https://x/sum"},
        ],
    }

    def get(url: str, timeout: int = 60) -> _Resp:
        calls.append(url)
        if fail:
            raise OSError("offline")
        if url == release_mod.API:
            return _Resp(json.dumps(release).encode())
        if url.endswith("sum"):
            return _Resp(f"{digest}  buckler-home.apk\n".encode())
        return _Resp(apk)

    monkeypatch.setattr(release_mod, "_get", get)
    return calls


def test_latest_apk_downloads_checks_and_reuses(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    apk = b"apk-bytes"
    calls = _serve(monkeypatch, apk, hashlib.sha256(apk).hexdigest())
    first = release_mod.latest_apk(tmp_path)
    assert first.read_bytes() == apk
    calls.clear()
    assert release_mod.latest_apk(tmp_path) == first
    assert "https://x/apk" not in calls  # the cached, checked copy is reused


def test_latest_apk_rejects_a_mismatch(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    _serve(monkeypatch, b"tampered", HEX)
    cache = tmp_path / "cache"
    cache.mkdir()
    with pytest.raises(ReleaseError, match="mismatch"):
        release_mod.latest_apk(cache)
    assert list(cache.iterdir()) == []


def test_latest_apk_wraps_network_errors(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    _serve(monkeypatch, b"", HEX, fail=True)
    with pytest.raises(ReleaseError, match="could not read"):
        release_mod.latest_apk(tmp_path)
