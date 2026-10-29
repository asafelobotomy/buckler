"""Build a Buckler release and, with --publish, put it on GitHub Releases.

Builds the signed launcher APK and the CLI wheel into dist/release/:
  buckler-home.apk          the same name every release, so
                            https://github.com/<repo>/releases/latest/download/buckler-home.apk
                            (and a Downloader code made from it) always gets the newest one
  buckler-home.apk.sha256   checked by `buckler launcher install` before installing
  buckler-<v>-py3-none-any.whl, buckler-<v>.tar.gz

The APK is signed with launcher/keystore.properties (written from secrets by
.github/workflows/release.yml, which runs this on every new version) and its certificate is
checked against RELEASE_CERT before anything is published, so a build that fell back to the debug
key cannot ship.

Usage:
  python scripts/release.py              build and check only
  python scripts/release.py --publish    then tag v<version>, push the tag and create the release
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LAUNCHER = ROOT / "launcher"
OUT = ROOT / "dist" / "release"
REPO = "asafelobotomy/buckler"
# SHA-256 of the release signing certificate (alias "buckler", made 2026-10-29).
RELEASE_CERT = "ed2c0df423e5428f219484e887b5187d30c9714aeac6bb19e9d106745b9f87c8"


def run(*cmd: str | Path, cwd: Path = ROOT, capture: bool = False) -> str:
    result = subprocess.run(
        [str(c) for c in cmd], cwd=cwd, check=False, text=True, capture_output=capture
    )
    if result.returncode != 0:
        detail = (result.stdout or "") + (result.stderr or "") if capture else ""
        sys.exit(f"failed: {' '.join(str(c) for c in cmd)}\n{detail[-3000:]}")
    return result.stdout if capture else ""


def versions() -> tuple[str, str]:
    """The CLI's version and the launcher's versionName; a release needs them equal."""
    cli = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))["project"]["version"]
    gradle = (LAUNCHER / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    return cli, match.group(1) if match else ""


def apksigner() -> Path:
    sdk = Path(os.environ.get("ANDROID_HOME") or Path.home() / "Android" / "Sdk")
    tools = sorted((sdk / "build-tools").glob("*/apksigner"))
    if not tools:
        sys.exit(f"apksigner not found under {sdk}/build-tools")
    return tools[-1]


def signer_digest(apk: Path) -> str:
    out = run(apksigner(), "verify", "--print-certs", apk, capture=True)
    match = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", out)
    if not match:
        sys.exit(f"could not read the signer of {apk}:\n{out}")
    return match.group(1)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def preflight(tag: str, publish: bool) -> None:
    cli, app = versions()
    if cli != app:
        sys.exit(f"versions differ: pyproject {cli}, launcher {app}")
    if not (LAUNCHER / "keystore.properties").is_file():
        sys.exit("launcher/keystore.properties is missing: the APK would be debug-signed")
    if not publish:
        return
    if run("git", "status", "--porcelain", capture=True).strip():
        sys.exit("the working tree has changes: commit them first")
    if run("git", "branch", "--show-current", capture=True).strip() != "main":
        sys.exit("release from main")
    if run("git", "tag", "--list", tag, capture=True).strip():
        sys.exit(f"tag {tag} already exists")


def build() -> list[Path]:
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    run(LAUNCHER / "gradlew", ":app:testDebugUnitTest", ":app:assembleRelease", cwd=LAUNCHER)
    built = LAUNCHER / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    got = signer_digest(built)
    if got != RELEASE_CERT:
        sys.exit(f"the APK is signed by {got}, not the release key {RELEASE_CERT}")
    apk = OUT / "buckler-home.apk"
    shutil.copy2(built, apk)
    checksum = OUT / "buckler-home.apk.sha256"
    checksum.write_text(f"{sha256(apk)}  {apk.name}\n", encoding="utf-8")
    run("uv", "build", "--out-dir", OUT)
    return sorted(p for p in OUT.iterdir() if not p.name.startswith("."))


def notes(version: str) -> str:
    previous = run("git", "describe", "--tags", "--abbrev=0", capture=True).strip()
    log = run("git", "log", "--format=- %s", f"{previous}..HEAD", capture=True).strip()
    base = f"https://github.com/{REPO}/releases/download/v{version}"
    return (
        f"## Install on the TV\n\n"
        f"With **Downloader** on the TV, open `{base}/buckler-home.apk`, or from a computer:\n\n"
        f"```\npipx install {base}/buckler-{version}-py3-none-any.whl\n"
        f"buckler --host <tv-ip> setup\n```\n\n"
        f"Buckler's setup wizard opens on first run. See the README for details.\n\n"
        f"## Changes since {previous}\n\n{log}\n"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--publish", action="store_true", help="tag, push the tag and release")
    args = parser.parse_args()
    version, _ = versions()
    tag = f"v{version}"
    preflight(tag, args.publish)
    files = build()
    for f in files:
        print(f"  {f.relative_to(ROOT)}  {f.stat().st_size // 1024} KB")
    print(f"signed by the release key ({RELEASE_CERT[:16]}…)")
    if not args.publish:
        print(f"built {tag}; nothing published (add --publish)")
        return
    body = OUT.parent / "release-notes.md"
    body.write_text(notes(version), encoding="utf-8")
    run("git", "tag", "-a", tag, "-m", f"Buckler {version}")
    run("git", "push", "origin", tag)
    run("gh", "release", "create", tag, *files, "--title", f"Buckler {version}",
        "--notes-file", body, "--verify-tag")
    print(f"published https://github.com/{REPO}/releases/tag/{tag}")


if __name__ == "__main__":
    main()
