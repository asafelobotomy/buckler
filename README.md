# shieldctl

ADB CLI and custom Android TV HOME launcher for **NVIDIA SHIELD TV Pro** (`mdarcy`).

Inspired by [cleanyourtv](https://github.com/Gathin23/cleanyourtv)’s safety model (disable-user, journals, never leave the box without HOME) — implemented as our own tooling and **shieldhome** launcher, not a fork.

## What it does

- **probe / audit** — identity, HOME owner, package state, AI upscaling health
- **apply / restore / check** — journaled `pm disable-user` profiles; OTA drift detection
- **launcher** — build, install, verify, and safely hand HOME to `dev.solon.shieldhome`

## Device policy (this project’s defaults)

| Keep | Disable |
|---|---|
| NVIDIA AI upscaling (`NvCPLSvc` + audio/Dolby) | Google Assistant (`katniss`) |
| Play Services / Store / remote / BT | `tvrecommendations` (no phone remote) |
| Settings / core Shield services | Tier A telemetry & GameStream leftovers |
| | Stock `tvlauncher` (after shieldhome handover) |

See [docs/safety.md](docs/safety.md).

## Setup

```bash
cd ~/Projects/shieldctl
uv venv .venv --python /usr/bin/python3
uv pip install -e '.[dev]' --python .venv/bin/python
export ANDROID_HOME=~/Android/Sdk   # for launcher builds
```

Enable **Network debugging** on the Shield (Developer options). Default host is `192.168.178.23`.

```bash
export SHIELD_HOST=192.168.178.23:5555
.venv/bin/shieldctl probe
```

## Typical flow

```bash
shieldctl probe
shieldctl audit
shieldctl apply --profile privacy-core
shieldctl apply --profile speech      # mics / hotword (keeps Chromecast)
shieldctl apply --profile minimal
shieldctl apply --profile extras      # factory/SMB/OSC leftovers
shieldctl launcher build
shieldctl launcher install
shieldctl launcher handover          # disables stock HOME after verify
shieldctl screensaver                # ClockDream + disable Backdrop
shieldctl tune                       # anim 0.5 + speed-compile
shieldctl doctor
shieldctl check                      # after OTAs: check --fix
# Optional Private DNS (you choose the DoT host):
# shieldctl dns set --hostname family.cloudflare-dns.com
```

After firmware updates: [docs/ota-check.md](docs/ota-check.md).  
Setup/restore product direction (no proprietary decompile): [docs/setup-restore.md](docs/setup-restore.md).

## Layout

```text
profiles/     privacy-core, minimal, home-clean, never
src/shieldctl CLI (Click + system adb)
launcher/     Compose-for-TV app (no INTERNET permission)
journals/     restore journals (gitignored JSON)
docs/         safety + OTA notes
```

## Launcher (shieldhome)

Lean Compose-for-TV HOME: clock, preferred dock (Jellyfin, LiveSubs, SonicSound, …), full app library, Settings tile. No network permission. Package id: `dev.solon.shieldhome`.

## Out of scope (for now)

- GUI wrapper (CLI-first)
- kitetvOS flash / unlock
- Disabling Chromecast / Netflix / YouTube (add an opt-in profile if needed)
