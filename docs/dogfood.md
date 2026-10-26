# Device dogfood baseline

Use this after profile or launcher changes, and after SHIELD Experience OTAs.

## Baseline (this unit)

| Field | Value |
|---|---|
| Device | SHIELD TV Pro (`mdarcy`) |
| Host | `BUCKLER_HOST` / `SHIELD_HOST` (default `192.168.178.23:5555`) |
| HOME | `dev.solon.shieldhome/.MainActivity` |
| Screensaver | `dev.solon.shieldhome/.ClockDream` |
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
```

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
