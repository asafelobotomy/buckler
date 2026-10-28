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
buckler setup                 # keeps stock apps; installs no suggestions
buckler setup --interactive   # confirm each phase; app prompts default to keep
buckler setup --swap smarttube --suggest localsend
buckler apps list
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
catalog/      open app swaps and optional suggestions (pinned sha256)
schema/       desired-state.v1.json (stock ↔ kitetvOS contract)
src/buckler/  CLI (Click + system adb)
launcher/     Compose-for-TV app (buckler-home)
covers/       Curated cover index (keyless Change cover pack)
journals/     restore journals + snapshots (gitignored JSON)
docs/         safety, OTA, setup/restore, kitetvOS bridge
tests/        policy / handover / journal / schema unit tests
```

## Launcher (buckler-home)

Compose-for-TV HOME (**0.13.0**) with a live header, a Main Stage, cover layouts, shelves, **Buckler Settings**, GitHub OTA prompts, and keyless Change cover (local + curated pack). Optional SteamGridDB / IGDB link via TV QR / device-code — not required for covers.

```bash
buckler home write                 # journals/home-layout.json
buckler home push                  # adb push → buckler-home import on resume
buckler launcher build && buckler launcher install
```

**OTA:** publish a GitHub Release tagged `vX.Y.Z` with a `buckler-home*.apk` asset. For a **private** repo, link a fine-grained PAT in Buckler Settings → Updates (Contents + Releases read). Public repos need no token. No release / no auth → background check stays quiet; Check now explains.

**Covers:** [covers/index.json](covers/index.json) and its artwork (`launcher/app/src/main/assets/covers/`, Apache-2.0 from dashboard-icons) are **bundled in the APK** — no third-party CDN at runtime. Refresh uses the GitHub Contents API when a token is linked, else raw URL, else bundled pack. Link enrichers in Settings (SteamGridDB QR; IGDB needs `TWITCH_CLIENT_ID` + Device Code Grant).

**Long-press / Menu:** Open, Change cover, Remove (unpin), Uninstall, Info, Pin, **Move** (closes the menu; ◀ ▶ slide the app along Apps, OK places it, Back cancels).

**Buckler Settings**: Home (Edit Home and Android TV shortcuts) · Main Stage · Header · News · Screensaver · Appearance · Accessibility · Covers · Updates · About. Each pane shows only the options for what is switched on: the Weather city appears with the Weather element or stage, ticker speed with news sources, media access with Now Playing, and so on.

**Main Stage** sits under the header and stays put while the shelves scroll. Small / Medium / Large (Edit Home, or Settings → Main Stage) sets how tall that band is, so the shelves keep a full row. Hold OK on it to switch News, Now Playing, Lyrics, Weather, or Suggested. **Lyrics** follows the header's Now Playing: word-timed lyrics from NetEase Cloud Music's YRC (an unofficial, keyless API; machine-split lines are treated as line-timed), else synced lyrics from [LRCLIB](https://lrclib.net) (open, no key), centred and filled letter by letter in the accent colour as each word is sung (a karaoke wipe), with a bouncing dot that lands on each word as it starts and settles on the last one. Word timings come from the lyrics when they carry them (enhanced LRC), otherwise they are spread over each line by syllable at the song's own pace, worked out from how tightly its lines are packed. Audio delay is allowed for automatically by route: Buckler checks where media is actually playing (a brief silent track, falling back to whether Android has A2DP on, since SHIELD bridges everything through an internal bus) on a background thread, then allows the platform's output latency plus 0.1 s on Bluetooth (about 0.2 s in all, calibrated by ear on SHIELD with a Bluetooth soundbar). Settings → Main Stage fine-tunes each route; ◀ ▶ on the stage nudge just the song playing, 0.1 s at a time, and are remembered for that song. Plain lyrics move through with the track, and video apps get a note instead. **Auto-sync** (opt-in, Settings → Main Stage → Lyrics) listens to what is playing through Android playback capture, runs the Silero voice detector (downloaded once, SHA-256 pinned, run with ONNX Runtime) and lines detected singing up with the lyric lines to measure each song's real offset; it applies one only when three estimates agree across 12 s of new audio, and remembers it for the song. Audio is analysed in memory on the TV and never stored or sent; Android asks for consent each time capture starts (after a restart too). It works best where the detector hears the voice; with quiet or heavily produced vocals it may not settle, and the manual ◀ ▶ still applies. Weather can be the next hours or the next 10 days, with every card on one line. OK on it opens what it is showing: News opens the story in a reader (the article text, with page scripts left behind), Now Playing opens the app that is playing, Weather opens the weather app, and Suggested opens that title's app. News rotates every 30, 60, or 90 seconds (Settings → Main Stage); ◀ ▶ step to the previous or next story. Every chosen source takes a turn, stories with a picture first, and feeds without pictures (CBS, DW, Al Jazeera…) use a text card.

**Shelves:** press ◀ on the first tile of a shelf to reveal the shelf button. App size is Thin / Small / Medium / Large. An empty Up Next row is still a stop for ▲ ▼; OK does nothing there and ◀ reveals its shelf button like any other shelf. OK on the shelf button opens *Move shelf* (▲ ▼ reorder, OK places, Back cancels) and *Hide shelf*. Up Next also offers *Sources*, and each chosen app contributes at most three cards. Edit Home and Settings are the pencil and gear in the header. ▼ on the last shelf wraps to the rightmost header icon, and ▲ in the header wraps to the last shelf. Home cannot be left without a way back: the last shelf that is always drawn cannot be hidden, and Edit Home and Settings cannot both be switched off in the header. Each opens the other (Edit Home → *Settings*, Settings → Home → *Edit Home*), so either icon reaches both. If nothing on Home leads to Settings anyway (header off, or no shelves), a **Home options** card at the end offers *Reset Home to Default* and *Open Settings*.

**Header** (Settings → Header): one quiet row — logo, clock with date and weather (☀ 14°), a **Now Playing** mini player in the middle (only while something plays), then network icon (Wi-Fi / Ethernet → network settings), VPN icon (opens your VPN app, else VPN settings; the country code shows while a VPN is up) and Edit Home / Play Store / Settings icons — with a dim news ticker underneath. Every piece can be switched off on its own, or the whole header (Home then shows the Main Stage and shelves; the pencil and gear go with the header, and the Home options card takes their place). Clock in 12 h or 24 h and six date formats; weather from [Open-Meteo](https://open-meteo.com) (pick a city, no key); news from public RSS for UK, US, Europe, or Worldwide (plus gaming, business, and tech — one source or several; several are interleaved so each story is from the next source, with that source's mark in front) at Slow / Normal / Fast. Header size Thin / Medium / Large scales the logo, clock, icons and Now Playing together; Now Playing has previous / play-pause / next for whatever app is playing and needs media access: Header → Now Playing → *Grant media access*, or `buckler launcher media-access`. Android cannot embed another app's video in the launcher, so video apps show as a controllable session; apps that support picture-in-picture still float over Home in the system PiP window.

**Screensaver** (Settings → Screensaver): drifting clock, **DVD bounce**, **Now Playing** (album art for music, artwork for films and TV, the thumbnail and channel for YouTube, a LIVE card for Twitch and TiviMate; video apps drop their session when covered, so the last thing seen in the past 30 minutes is shown as paused), retro **3D Maze**, **3D Pipes**, **Mystify** and **Starfield** (drawn in code, no bundled art), online photos from any mix of Nature, Cityscape, Seascape, Landmarks, Sky & space and Wildlife (slides take turns between the ticked categories; Wikimedia Commons featured pictures, credited on screen), or **My photos**: upload from a phone via QR (Settings → Screensaver → My photos → Upload from phone) or `buckler launcher photos *.jpg`. Each screensaver has its own page in Settings → Screensaver with *Use this screensaver*, *Preview*, and only its own options. `buckler screensaver` makes it the system screensaver. **Start after** 60 / 90 / 120 s, 5 / 10 / 15 min, 1 hour, a custom time, or Off (changing it needs a one-time `buckler launcher grant-settings`).

**Appearance** (Settings → Appearance): colour scheme (**Buckler** teal and sage — the default — Light, Dark, Gruvbox, SNES UK, SNES US, SHIELD; the app icon and TV banner follow the scheme via activity-aliases, regenerate them with `python scripts/gen_icons.py`), and font (System, Atkinson Hyperlegible, Condensed, Serif, Playful, Monospace). Home layout (Icon / Poster / Banner, shown as pictures) and app size (Thin / Small / Medium / Large, all layouts; stored as `tile_size` in the layout file) are in Edit Home.

**Accessibility** (Settings → Accessibility): high contrast, strong focus outline, reduced motion, spoken focus (text-to-speech reads the focused item), text size, and shortcuts to TalkBack and system captions; mono audio is a one-tap toggle once `grant-settings` has run.

**DNS:** `buckler dns status` (alias `get`) / `set --hostname …` / `off`.

Package id: `dev.solon.bucklerhome`. CLI and launcher both track **0.13.0**. Uses `INTERNET` for artwork, curated pack, GitHub updates, header weather/news, online screensaver photos and optional linked APIs; `ACCESS_NETWORK_STATE` for the Wi-Fi / Ethernet / VPN icons — no ads, no account telemetry. Release APKs are signed with `launcher/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; git-ignored) when present, otherwise with the machine-local debug key — OTA updates only install over an APK signed with the same key. Switching keys means one uninstall/reinstall. Backups are disabled (tokens live in encrypted prefs). Feeds and artwork must be `https://`.

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
