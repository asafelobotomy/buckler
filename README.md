# Buckler

ADB CLI and custom Android TV HOME launcher for **NVIDIA SHIELD TV Pro** (`mdarcy`).  
Command: `buckler` · Project: `~/Projects/buckler` · Path to **kitetvOS**.

Inspired by [cleanyourtv](https://github.com/Gathin23/cleanyourtv)’s safety model (disable-user, journals, never leave the box without HOME) — implemented as our own tooling and **buckler-home** launcher, not a fork.

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

## Setup

```bash
cd ~/Projects/buckler
uv venv .venv --python /usr/bin/python3
uv pip install -e '.[dev]' --python .venv/bin/python
export ANDROID_HOME=~/Android/Sdk   # for launcher builds
```

Enable **Network debugging** on the Shield (Developer options). Default host is `192.168.178.23`.

```bash
export BUCKLER_HOST=192.168.178.23:5555   # or SHIELD_HOST (alias)
.venv/bin/buckler probe
```

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
# Optional Private DNS (you choose the DoT host):
# buckler dns set --hostname family.cloudflare-dns.com
```

After firmware updates: [docs/ota-check.md](docs/ota-check.md).  
Device dogfood checklist: [docs/dogfood.md](docs/dogfood.md).

### Guided setup / restore

```bash
buckler setup                 # multi-step wizard (confirm / --yes)
buckler setup --interactive   # confirm each phase
buckler setup --dry-run
buckler snapshot              # desired-state.v1 JSON (schema-validated)
buckler restore-wizard --all  # undo all setup journals + doctor
# or: buckler restore --all
```

Phases are shared with future **kitetvOS** first-boot: [docs/kitetvos-bridge.md](docs/kitetvos-bridge.md).  
Stock factory reset still needs a manual `setup` afterward; kitetvOS will re-enter our wizard automatically.

## Layout

```text
profiles/     privacy-core, speech, minimal, extras, home-clean, never
schema/       desired-state.v1.json (stock ↔ kitetvOS contract)
src/buckler/  CLI (Click + system adb)
launcher/     Compose-for-TV app (buckler-home)
covers/       Curated cover index (keyless Change cover pack)
journals/     restore journals + snapshots (gitignored JSON)
docs/         safety, OTA, setup/restore, kitetvOS bridge
tests/        policy / handover / journal / schema unit tests
```

## Launcher (buckler-home)

Compose-for-TV HOME (**0.5.0**) with cover layouts, shelves, **Buckler Settings**, GitHub OTA prompts, and keyless Change cover (local + curated pack). Optional SteamGridDB / IGDB link via TV QR / device-code — not required for covers.

```bash
buckler home write                 # journals/home-layout.json
buckler home push                  # adb push → buckler-home import on resume
buckler launcher build && buckler launcher install
```

**OTA:** publish a GitHub Release tagged `vX.Y.Z` with a `buckler-home*.apk` asset. For a **private** repo, link a fine-grained PAT in Buckler Settings → Updates (Contents + Releases read). Public repos need no token. No release / no auth → background check stays quiet; Check now explains.

**Covers:** [covers/index.json](covers/index.json) is **bundled in the APK**. Refresh uses the GitHub Contents API when a token is linked, else raw URL, else bundled pack. Link enrichers in Settings (SteamGridDB QR; IGDB needs `TWITCH_CLIENT_ID` + Device Code Grant).

**Long-press / Menu:** Open, Change cover, Remove (unpin), Uninstall, Info, Pin / move.

**DNS:** `buckler dns status` (alias `get`) / `set --hostname …` / `off`.

Package id: `dev.solon.bucklerhome`. CLI and launcher both track **0.5.0**. Uses `INTERNET` for artwork, curated pack, GitHub updates, and optional linked APIs — no ads. Release APKs are signed with `launcher/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; git-ignored) when present, otherwise with the machine-local debug key — OTA updates only install over an APK signed with the same key. Switching keys means one uninstall/reinstall. Backups are disabled (tokens live in encrypted prefs). Feeds and artwork must be `https://`.

## Development

```bash
pytest -q && ruff check src tests scripts   # Python
python scripts/check_loc.py                 # every source file <= 400 lines
(cd launcher && ./gradlew :app:testDebugUnitTest)   # Kotlin unit tests
```

**Dev tip:** run tests with the project venv (`uv run pytest` or `.venv/bin/python -m pytest` — `pythonpath = ["src"]` is set in `pyproject.toml`).

## Out of scope (for now)

- GUI wrapper (CLI-first)
- kitetvOS flash / unlock
- Disabling Chromecast / Netflix / YouTube (add an opt-in profile if needed)
