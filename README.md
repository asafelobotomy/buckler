# Buckler

ADB CLI and custom Android TV HOME launcher for **NVIDIA SHIELD TV Pro** (`mdarcy`).  
Command: `buckler` · Project: `~/Projects/buckler` · Path to **kitetvOS**.

Inspired by [cleanyourtv](https://github.com/Gathin23/cleanyourtv)’s safety model (disable-user, journals, never leave the box without HOME) — implemented as our own tooling and **shieldhome** launcher, not a fork.

## What it does

- **probe / audit** — identity, HOME owner, package state, AI upscaling health
- **apply / restore / check** — journaled `pm disable-user` profiles; OTA drift detection
- **setup / snapshot / restore-wizard** — guided stock path aligned with future kitetvOS first-boot
- **launcher** — build, install, verify, and safely hand HOME to `dev.solon.shieldhome`

## Device policy (this project’s defaults)

| Keep | Disable |
|---|---|
| NVIDIA AI upscaling (`NvCPLSvc` + audio/Dolby) | Google Assistant (`katniss`) |
| Play Services / Store / remote / BT | `tvrecommendations` (no phone remote) |
| Settings / core Shield services | Tier A telemetry & GameStream leftovers |
| Chromecast (`mediashell`) | Stock HOME(s) after shieldhome handover |

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
launcher/     Compose-for-TV app (no INTERNET permission)
journals/     restore journals + snapshots (gitignored JSON)
docs/         safety, OTA, setup/restore, kitetvOS bridge
tests/        policy / handover / journal / schema unit tests
```

## Launcher (shieldhome)

Lean Compose-for-TV HOME: clock, preferred dock (Jellyfin, LiveSubs, SonicSound, …), full app library, Settings tile. One-time on-TV welcome is informational only (does not run host profiles). No network permission. Package id: `dev.solon.shieldhome`.

Release APKs are **debug-keystore signed** for personal sideload; replace signing before any wider distribution.

## Out of scope (for now)

- GUI wrapper (CLI-first)
- kitetvOS flash / unlock
- Disabling Chromecast / Netflix / YouTube (add an opt-in profile if needed)
