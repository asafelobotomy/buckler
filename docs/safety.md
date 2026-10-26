# Safety invariants

`shieldctl` is designed so a bad list or a partial failure cannot soft-brick a SHIELD TV Pro.

## Hard rules

1. **Disable, do not uninstall.** All profiles use `pm disable-user --user 0`. Restore is `pm enable`.
2. **Never-list is absolute.** Packages in [`profiles/never.yaml`](../profiles/never.yaml) are refused even if duplicated in another profile. This includes NVIDIA AI upscaling (`NvCPLSvc`, audio/Dolby), Play Services, keychain, `com.android.se`, remote/Bluetooth, and Settings.
3. **Never leave the box without HOME.** Stock launchers (`tvlauncher`, `launcherx`, leanback, Xiaomi PatchWall) are only disabled when `dev.solon.shieldhome` is installed. `launcher handover` re-enables stock if HOME does not resolve to shieldhome after the change.
4. **AI stack check after mutations.** `apply` and `check` fail if any AI-stack package is missing or disabled.
5. **Journals are first-class.** Every successful apply/handover writes JSON under `journals/` with restore commands.

## Profiles (this unit)

| Profile | Intent |
|---|---|
| `privacy-core` | Google Assistant + recommendations (no phone remote) |
| `minimal` | Telemetry, partner auto-installs, GameStream leftovers, trivial bloat |
| `home-clean` | Action profile via `shieldctl launcher handover` |
| `never` | Not applyable — blocklist only |

## OTA drift

SHIELD Experience updates can re-enable packages. After any system update:

```bash
shieldctl --host <IP> check
shieldctl --host <IP> check --fix
```

## Network debugging

Leave Network debugging on only while working. When finished, turn it off in Developer options on the Shield.

## Restore

```bash
shieldctl --host <IP> restore --journal journals/<file>.json
# or re-enable stock HOME after uninstalling shieldhome:
adb shell pm enable com.google.android.tvlauncher
```
