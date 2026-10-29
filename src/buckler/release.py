"""The launcher APK from Buckler's GitHub Releases, for an installed CLI with no source to build."""

from __future__ import annotations

import hashlib
import json
import shutil
import urllib.request
from pathlib import Path

from buckler.paths import apk_cache_dir

REPO = "asafelobotomy/buckler"
API = f"https://api.github.com/repos/{REPO}/releases/latest"


class ReleaseError(RuntimeError):
    pass


def _get(url: str, timeout: int = 60):
    request = urllib.request.Request(url, headers={"User-Agent": "buckler", "Accept": "*/*"})
    return urllib.request.urlopen(request, timeout=timeout)


def pick_assets(release: dict) -> tuple[dict, dict]:
    """The release's APK and its `.sha256` file; both are required."""
    assets = {a["name"]: a for a in release.get("assets", [])}
    apks = [a for name, a in assets.items() if name.endswith(".apk")]
    if len(apks) != 1:
        raise ReleaseError(f"release {release.get('tag_name')} has {len(apks)} APKs, expected 1")
    checksum = assets.get(apks[0]["name"] + ".sha256")
    if checksum is None:
        raise ReleaseError(f"release {release.get('tag_name')} has no {apks[0]['name']}.sha256")
    return apks[0], checksum


def expected_digest(text: str) -> str:
    """The hash from a `sha256sum` line ("<hex>  <name>")."""
    digest = text.strip().split()[0].lower() if text.strip() else ""
    if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
        raise ReleaseError("checksum file is not a sha256sum line")
    return digest


def latest_apk(cache: Path | None = None) -> Path:
    """Downloads the latest release's APK (once; cached by name) and checks it against its sha256."""
    try:
        with _get(API) as response:
            release = json.load(response)
        apk, checksum = pick_assets(release)
        with _get(checksum["browser_download_url"]) as response:
            digest = expected_digest(response.read().decode("utf-8", "replace"))
    except ReleaseError:
        raise
    except Exception as exc:
        raise ReleaseError(f"could not read the latest release: {exc}") from exc

    folder = cache if cache is not None else apk_cache_dir()
    dest = folder / apk["name"]
    if dest.is_file() and _sha256(dest) == digest:
        return dest
    partial = dest.with_suffix(".partial")
    try:
        with _get(apk["browser_download_url"], timeout=300) as response, partial.open("wb") as out:
            shutil.copyfileobj(response, out)
        got = _sha256(partial)
        if got != digest:
            raise ReleaseError(f"{apk['name']}: sha256 mismatch (got {got})")
        partial.replace(dest)
    except ReleaseError:
        partial.unlink(missing_ok=True)
        raise
    except Exception as exc:
        partial.unlink(missing_ok=True)
        raise ReleaseError(f"{apk['name']}: download failed: {exc}") from exc
    return dest


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()
