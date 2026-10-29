# Setup / restore experience

Original work in `buckler` + `buckler-home` — **not** a decompile of NVIDIA setup/restore APKs.

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

# Export desired-state JSON (src/buckler/data/schema/desired-state.v1.json, validated on write):
buckler --host <IP> snapshot

# Prefer after a full setup — undo every disable journal, then doctor:
buckler --host <IP> restore-wizard --all
buckler --host <IP> restore --all

# Surgical: one journal only
buckler --host <IP> restore --journal journals/<file>.json
```

### What `setup` does (phase IDs)

`connect` → `probe` → `privacy` → `speech` → `extras` → `home` → `alternatives` → `suggestions` → `screensaver` → `tune` → `verify` → `snapshot` → `finish`

`alternatives` asks, per stock app, whether to pin an open client or keep the stock app. `suggestions` offers optional apps that replace nothing. Batch setup (no `--interactive`) keeps every stock dock app and installs nothing from either page. Opt in with `--swap` / `--suggest`, or answer the prompts. A swap installs and pins. It does not disable or uninstall the stock package. YouTube stays installed either way so Cast still works. Restore does not remove these apps.

```bash
buckler apps list
buckler setup --swap smarttube --suggest localsend
buckler apps apply --swap moonlight --dry-run
```

Same IDs are the contract for **kitetvOS Provisioner** first-boot ([kitetvos-bridge.md](kitetvos-bridge.md)).

**Important:** A stock Shield factory restore still runs NVIDIA’s OOBE, not this wizard. After stock restore, enable Network debugging and run `buckler setup` again. On kitetvOS, factory reset will re-open *our* wizard.

### Snapshot schema

JSON with device identity, policy flags (keep AI / Chromecast, disable mics), profile list, desired vs actual disabled packages. Written only after `validate_desired_state`. Use after OTAs with `buckler check --fix`.

## On-TV first-run

`buckler-home` opens its setup wizard the first time it starts, and again from Settings → Setup → *Run setup wizard*. It is the on-TV counterpart of `buckler setup`: *Simple* or *Advanced*, then a *Review* of only what will change, applied over the TV's own Network debugging (the same profiles and never-list as the CLI, journaled so Settings → Setup → *Restore everything* undoes them). See [install.md](install.md) for the steps. No account, no registration telemetry.

## Disaster path

1. `restore-wizard --all` (or `restore --all`) to undo all setup disables  
2. Or `setup` again to re-apply the full clean stack  
3. Keep snapshots under `journals/` (gitignored) for your unit  
