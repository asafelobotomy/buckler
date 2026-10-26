# From Buckler (stock) → kitetvOS (custom OS)

**End goal:** kitetvOS — a full custom Android TV OS for the 2019 SHIELD Pro (`mdarcy`), with its own first-boot wizard, launcher, privacy defaults, and (later) store/OTA.

**Now:** stock SHIELD Experience + `buckler` / `buckler-home` prove the product behaviour without unlocking.

```text
┌─────────────────────────────────────────────────────────────┐
│  TODAY — stock firmware                                     │
│  buckler setup (multi-step)  →  buckler-home + ClockDream   │
│  desired-state.v1.json         →  journals/snapshot-*.json  │
└───────────────────────────┬─────────────────────────────────┘
                            │ same schema + UX copy
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  LATER — kitetvOS                                           │
│  First-boot Provisioner (system priv-app)                   │
│  reads desired-state / baked product defaults               │
│  buckler-home (or lineage_kitetv HOME) already system app     │
│  no ADB required on the couch                               │
└─────────────────────────────────────────────────────────────┘
```

## What transfers

| Stock (buckler) | kitetvOS |
|---|---|
| Package disable profiles (`privacy-core`, `speech`, …) | `PRODUCT_PACKAGES` omit / `pm disable` at boot / RRO overlays |
| `never.yaml` AI / remote / GMS rules | Product makefile + sepolicy; document AI upscaling **lost** after unlock |
| buckler-home Compose UI + first-run | Same app (or fork) as `PRODUCT_PACKAGES` HOME |
| ClockDream screensaver | Same DreamService as system |
| `desired-state.v1.json` | Provisioner input + factory reset re-run |
| `buckler doctor` / snapshot | On-device Settings tile + `adb` for CI |

## First-boot on kitetvOS (target design)

1. Device boots `lineage_kitetv` → **Provisioner** activity (LAUNCHER / setup role) before normal HOME, or a one-shot activity with higher priority until `kitetv.setup_complete=1`.
2. Steps mirror stock wizard phases (see below): welcome → privacy → speech → extras → home → alternatives → suggestions → screensaver → finish.
3. Persist completion in `Settings.Secure` / `ro.kitetv.setup_complete` so factory reset re-shows the wizard.
4. Optional: import a USB/network `desired-state.v1.json` for power users.

Factory reset on kitetvOS **does** re-enter *our* wizard (unlike stock NVIDIA restore). That is the behaviour you want long-term.

## Stock wizard phases (canonical)

Shared names so UX and code stay aligned (`src/buckler/wizard_phases.py`):

1. **connect** — ADB / (ROM: skip)  
2. **probe** — identity + safety  
3. **privacy** — telemetry / leftovers (`privacy-core` + `minimal`)  
4. **speech** — mic / assistant off  
5. **extras** — factory / SMB / OSC / welcome leftovers  
6. **home** — install + set buckler-home  
7. **alternatives** — pin an open client, or keep the stock app  
8. **suggestions** — optional apps that replace nothing  
9. **screensaver** — ClockDream, remove Backdrop  
10. **tune** — animations / compile  
11. **verify** — doctor + Chromecast + AI policy  
12. **snapshot** — write desired-state (includes optional `apps`)  
13. **finish** — remind Network debugging off (stock only)

## Risks to keep visible

- Unlocking for kitetvOS **wipes data** and typically **disables NVIDIA AI upscaling**.
- No USB boot-ROM recovery on Tegra X1+ — flash discipline from kitetvOS `docs/roadmap.md` Phase 2–3.
- GMS/Play Integrity/DRM differ on Lineage vs stock.

## Repo map

| Repo | Role |
|---|---|
| `~/Projects/buckler` | Stock path + shared schema + launcher prototype |
| `~/Projects/kitetvOS` | Lineage product, manifests, vendor/kitetv, flash/OTA |

When Phase 4 of kitetvOS starts, **move or submodule** `launcher/` into `vendor/kitetv/apps/BucklerHome` (or rename to KiteHome) and wire Provisioner to the same phase list.
