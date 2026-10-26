# Setup / restore experience

Original work in `buckler` + `shieldhome` — **not** a decompile of NVIDIA setup/restore APKs.

## Why not decompile stock setup?

Packages like `tungsten.setupwraith`, `com.nvidia.shield.welcome`, and `com.nvidia.shield.registration` are proprietary. Shipping a refactored derivative of their code is off-limits. We build our own flows instead.

## Host CLI

```bash
# Multi-step setup (confirm prompt; use --yes to skip):
buckler --host <IP> setup
buckler --host <IP> setup --interactive   # ask before each mutating phase

# Preview profile changes only:
buckler --host <IP> setup --dry-run

# Reuse an already-built APK:
buckler --host <IP> setup --skip-build

# Export desired-state JSON (schema/desired-state.v1.json, validated on write):
buckler --host <IP> snapshot

# Prefer after a full setup — undo every disable journal, then doctor:
buckler --host <IP> restore-wizard --all
buckler --host <IP> restore --all

# Surgical: one journal only
buckler --host <IP> restore --journal journals/<file>.json
```

### What `setup` does (phase IDs)

`connect` → `probe` → `privacy` → `speech` → `extras` → `home` → `screensaver` → `tune` → `verify` → `snapshot` → `finish`

Same IDs are the contract for **kitetvOS Provisioner** first-boot ([kitetvos-bridge.md](kitetvos-bridge.md)).

**Important:** A stock Shield factory restore still runs NVIDIA’s OOBE, not this wizard. After stock restore, enable Network debugging and run `buckler setup` again. On kitetvOS, factory reset will re-open *our* wizard.

### Snapshot schema

JSON with device identity, policy flags (keep AI / Chromecast, disable mics), profile list, desired vs actual disabled packages. Written only after `validate_desired_state`. Use after OTAs with `buckler check --fix`.

## On-TV first-run

`shieldhome` shows a one-time welcome (SharedPreferences `first_run_done`). It is **informational only** — it does not apply host profiles or talk to `buckler`.

- Explains dock / library / screensaver / host tooling  
- **Continue to Home** or **Open Android TV Settings**  
- No network permission; no registration telemetry  

Clear the flag to see it again:

```bash
adb shell run-as dev.solon.shieldhome true 2>/dev/null || true
# debug builds / until run-as works, uninstall+reinstall or:
adb shell pm clear dev.solon.shieldhome   # clears prefs (re-triggers welcome)
```

Prefer after install: launch HOME once to see the welcome.

## Disaster path

1. `restore-wizard --all` (or `restore --all`) to undo all setup disables  
2. Or `setup` again to re-apply the full clean stack  
3. Keep snapshots under `journals/` (gitignored) for your unit  
