# Safety invariants

**Buckler** (`buckler`) is designed so a bad list or a partial failure cannot soft-brick a SHIELD TV Pro.

## Hard rules

1. **Disable, do not uninstall.** All profiles use `pm disable-user --user 0`. Restore is `pm enable`. There is no uninstall path in the CLI.
2. **Never-list is absolute.** Packages in [`profiles/never.yaml`](../profiles/never.yaml) are refused even if duplicated in another profile. This includes NVIDIA AI upscaling (`NvCPLSvc`, audio/Dolby), Play Services, keychain, `com.android.se`, remote/Bluetooth, and Settings.
3. **Never leave the box without HOME.** Stock launchers (`tvlauncher`, `launcherx`, leanback, Xiaomi PatchWall) are only disabled when `dev.solon.shieldhome` is installed. `launcher handover` disables every present known stock launcher and re-enables them if HOME does not resolve to shieldhome afterward.
4. **AI stack check after mutations.** `apply` and `check` fail if any AI-stack package is missing or disabled.
5. **Journals are first-class.** Every successful apply/handover/screensaver writes JSON under `journals/` with restore commands. Desired-state snapshots use `snapshot-*.json` and are excluded from restore globs.

## Profiles (this unit)

| Profile | Intent |
|---|---|
| `privacy-core` | Google Assistant + recommendations (no phone remote) |
| `minimal` | Telemetry, partner auto-installs, GameStream leftovers, trivial bloat |
| `speech` | Mics / hotword / on-device speech (keeps Chromecast) |
| `extras` | Factory, SMB, OSC, welcome/registration leftovers |
| `home-clean` | Action profile via `buckler launcher handover` (not `apply`) |
| `never` | Not applyable — blocklist only |

Default setup + OTA `check` desired set: `privacy-core`, `speech`, `minimal`, `extras` (`SETUP_PROFILES`).

## Doctor exit semantics

| Severity | Examples | Exit |
|---|---|---|
| Hard fail | HOME ≠ shieldhome, AI stack broken, Chromecast mediashell disabled | `1` |
| Warning | Screensaver not ClockDream, Network debugging reminder | `0` |

## OTA drift

SHIELD Experience updates can re-enable packages. After any system update:

```bash
buckler --host <IP> check
buckler --host <IP> check --fix
```

## Network debugging

Leave Network debugging on only while working. When finished, turn it off in Developer options on the Shield.

## Restore

After a full `buckler setup`, prefer restoring **all** disable journals:

```bash
buckler --host <IP> restore --all
# or guided:
buckler --host <IP> restore-wizard --all
```

Single journal (legacy / surgical):

```bash
buckler --host <IP> restore --journal journals/<file>.json
# or re-enable stock HOME after uninstalling shieldhome:
adb shell pm enable com.google.android.tvlauncher
```
