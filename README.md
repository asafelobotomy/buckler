# Buckler

ADB CLI and custom Android TV HOME launcher for **NVIDIA SHIELD TV Pro** (`mdarcy`).
Command: `buckler` · the stock-Android path toward **kitetvOS**.

Inspired by [cleanyourtv](https://github.com/Gathin23/cleanyourtv)’s safety model (disable-user, journals, never leave the box without HOME) — implemented as our own tooling and **buckler-home** launcher, not a fork.

**Installing Buckler on your TV:** see [docs/install.md](docs/install.md) (on the TV with
Downloader, or from a computer). The rest of this page is for working on Buckler itself.

## What it does

- **probe / audit** — identity, HOME owner, package state, AI upscaling health
- **apply / restore / check** — journaled `pm disable-user` profiles; OTA drift detection
- **setup / snapshot / restore-wizard** — guided stock path aligned with future kitetvOS first-boot
- **launcher** — build, install, verify, and safely hand HOME to `dev.solon.bucklerhome`

## Device policy (this project’s defaults)

| Keep | Disable |
|---|---|
| NVIDIA AI upscaling (`NvCPLSvc` + audio/Dolby) | Google Assistant (`katniss`) |
| Play Services / Store / remote / BT | `tvrecommendations` (no phone remote) |
| Settings / core Shield services | Tier A telemetry & GameStream leftovers |
| Chromecast (`mediashell`) | Stock HOME(s) after buckler-home handover |

See [docs/safety.md](docs/safety.md).

## Development setup

```bash
uv sync --extra dev                 # .venv from uv.lock, with pytest and ruff
export ANDROID_HOME=~/Android/Sdk   # for launcher builds
```

Enable **Network debugging** on the Shield (Developer options). Buckler asks for the TV once: pass `--host <tv-ip>` and it is remembered (`~/.config/buckler/host`).

```bash
export BUCKLER_HOST=<tv-ip>:5555   # optional; or SHIELD_HOST, or --host once
uv run buckler probe
```

## Releasing

Bump the version in `pyproject.toml`, `src/buckler/__init__.py` and `launcher/app/build.gradle.kts`
(`versionCode` and `versionName`) and push to main. The `release` workflow sees a version with no
tag yet and runs `scripts/release.py --publish`: it builds `buckler-home.apk`, its `.sha256` and the
CLI wheel, refuses an APK not signed by the release key (its certificate SHA-256 is pinned in the
script), tags `v<version>` and creates the GitHub release. It needs two repository secrets,
`BUCKLER_KEYSTORE_B64` (the keystore, base64) and `BUCKLER_KEYSTORE_PASSWORD`. Running
`python scripts/release.py` locally builds and checks the same files without publishing.

## Typical flow

```bash
buckler probe
buckler audit
buckler apply --profile privacy-core
buckler apply --profile speech      # mics / hotword (keeps Chromecast)
buckler apply --profile minimal
buckler apply --profile extras      # factory/SMB/OSC leftovers
buckler launcher build
buckler launcher install
buckler launcher handover          # disables known stock HOMEs after verify
buckler screensaver                # ClockDream + disable Backdrop
buckler tune                       # anim 0.5 + speed-compile
buckler doctor
buckler check                      # after OTAs: check --fix
buckler unused                     # apps Buckler found unused; --apply disables them
buckler clean                      # free app cache space on the TV
buckler launcher iconpack install lawnicons   # an open icon pack (or arcticons)
# Optional Private DNS (you choose the DoT host):
# buckler dns set --hostname family.cloudflare-dns.com
```

After firmware updates: [docs/ota-check.md](docs/ota-check.md).  
Device dogfood checklist: [docs/dogfood.md](docs/dogfood.md).

### Guided setup / restore

```bash
buckler setup                 # keeps stock apps; installs no suggestions
buckler setup --interactive   # confirm each phase; app prompts default to keep
buckler setup --swap smarttube --suggest localsend
buckler apps list
buckler setup --dry-run
buckler snapshot              # desired-state.v1 JSON (schema-validated)
buckler restore-wizard --all  # undo all setup journals + doctor
# or: buckler restore --all
```

Phases are shared with future **kitetvOS** first-boot: [docs/kitetvos-bridge.md](docs/kitetvos-bridge.md).  
Stock factory reset still needs a manual `setup` afterward; kitetvOS will re-enter our wizard automatically.

## Layout

```text
src/buckler/  CLI (Click + system adb)
  data/profiles/  privacy-core, speech, minimal, extras, home-clean, never
  data/catalog/   open app swaps, suggestions and icon packs (pinned sha256)
  data/schema/    desired-state.v1.json (stock ↔ kitetvOS contract)
launcher/     Compose-for-TV app (buckler-home)
covers/       Curated cover index (keyless Change cover pack)
journals/     restore journals + snapshots (gitignored JSON; ~/.local/state/buckler when installed)
docs/         install guide, safety, OTA, setup/restore, kitetvOS bridge
tests/        policy / handover / journal / schema unit tests
```

## Launcher (buckler-home)

A Compose-for-TV Home with a live header and Side Stage, a Main Stage (News, Now Playing, Lyrics,
Weather, Suggested, At a Glance, Calendar, Weather map, Companion), shelves in three cover layouts,
icon packs, Buckler Settings, a setup wizard that makes the SHIELD changes from the TV itself, and
GitHub updates. The full reference is [docs/launcher.md](docs/launcher.md).

## Development

```bash
pytest -q && ruff check src tests scripts   # Python
python scripts/check_loc.py                 # every source file <= 400 lines
(cd launcher && ./gradlew :app:testDebugUnitTest)   # Kotlin unit tests
(cd launcher && ./gradlew :app:assembleOptimized)   # release-fast build, signed with the debug key
```

For a TV used day to day, install the **optimized** build rather than debug: it is the release
build (shrunk, optimised, not debuggable; about 8.7 MB against 50 MB) signed with this machine's
debug key, so it installs over a debug install without losing settings. On SHIELD it holds about
40% less memory. It stays profileable, so CPU and frames can still be measured; use the debug
build when you need `run-as` or a debugger.

Most `scripts/` run with the project's own dependencies. `gen_fonts.py` also needs `fontTools`, and
`scripts/lyrics_bench/` (a retired benchmark, kept for its results) needs `torch` and `demucs`.

**Dev tip:** run tests with the project venv (`uv run pytest` or `.venv/bin/python -m pytest` — `pythonpath = ["src"]` is set in `pyproject.toml`).

## Out of scope (for now)

- GUI wrapper (CLI-first)
- kitetvOS flash / unlock
- Disabling Chromecast / Netflix / YouTube (add an opt-in profile if needed)

## License and credits

Buckler is MIT-licensed ([LICENSE](LICENSE)). It builds on open data and work that it credits:
- **Weather** from [Open-Meteo](https://open-meteo.com) (CC BY 4.0, no key), and coastlines from [Natural Earth](https://www.naturalearthdata.com) (public domain).
- **Lyrics** from [LRCLIB](https://lrclib.net), and word timing from NetEase Cloud Music's lyric service.
- **Cover icons** from [dashboard-icons](https://github.com/homarr-labs/dashboard-icons) (Apache-2.0, with its licence bundled under `launcher/app/src/main/assets/covers/`).
- **Fonts** under the SIL Open Font License, with each licence in `launcher/branding/`.

