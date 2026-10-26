# Device dogfood baseline

Use this after profile or launcher changes, and after SHIELD Experience OTAs.

## Baseline (this unit)

| Field | Value |
|---|---|
| Device | SHIELD TV Pro (`mdarcy`) |
| Host | `BUCKLER_HOST` / `SHIELD_HOST` (default `192.168.178.23:5555`) |
| HOME | `dev.solon.bucklerhome/.MainActivity` |
| Screensaver | `dev.solon.bucklerhome/.ClockDream` |
| Profiles | `privacy-core`, `speech`, `minimal`, `extras` + handover + screensaver |

Golden snapshots live under `journals/snapshot-*.json` (gitignored). Re-export after intentional changes:

```bash
buckler snapshot
buckler check
buckler doctor
```

## Quick pass

```bash
export BUCKLER_HOST=<IP>:5555
buckler probe
buckler audit
buckler check          # should report drift: none when healthy
buckler doctor         # HOME + AI hard-fail; sticky pkgs force-stopped
buckler launcher verify
buckler home write && buckler home push   # layout defaults → import on HOME resume
```

On TV: first-run picks cover layout + shelves; **Edit Home** in the footer adjusts later.

### buckler-home 0.5.0 smoke

- Header shows Buckler mark + `buckler-home`
- Footer **Settings** → Buckler Settings (not only stock); stock Settings still reachable inside
- Long-press / Menu: Open, Change cover, Remove, Uninstall (user apps), Info
- Change cover works **without** linking accounts (icon / banner / curated)
- Settings → Covers: Connect SteamGridDB (QR) / Connect IGDB (device code when `TWITCH_CLIENT_ID` set)
- Update dialog appears when a newer GitHub release has an APK asset; Snooze / Dismiss work
- Release APK naming: prefer `buckler-home*.apk` on the GitHub Release

## Known sticky processes

Some NVIDIA packages stay running after `pm disable-user` (GameStream `nvgamecast`, OSC, app messaging). `apply` / `check --fix` / `doctor` call `am force-stop`; they may respawn until reboot. After a reboot they should stay down if still disabled.

## After profile edits

```bash
buckler apply --profile privacy-core
buckler apply --profile speech
buckler apply --profile minimal
buckler apply --profile extras
# or: buckler check --fix
buckler snapshot
```
