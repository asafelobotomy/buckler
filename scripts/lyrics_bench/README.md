# Lyrics sync benchmark

Measures how far lyric timestamps are from the audio Spotify actually plays on the TV, and scores
auto-sync methods against that ground truth.

## Record

The recorder lived in the app's lyrics auto-sync, which this benchmark retired (it lost to a
fixed correction, below). To record again, build the launcher from commit `15c61ae` with
`recorder.patch` applied (`git checkout 15c61ae && git apply scripts/lyrics_bench/recorder.patch`),
turn on auto-sync in Settings → Main Stage, then switch on the recorders:

```bash
adb shell touch /sdcard/Android/data/dev.solon.bucklerhome/files/autosync-dump /sdcard/Android/data/dev.solon.bucklerhome/files/autosync-audio
```

With the Lyrics stage on screen, play each song from the start for ~90 s. Each song leaves a
`sync-*.csv` (lyric lines, frames, estimates) and `sync-*.raw` (48 kHz stereo s16le) in that
folder. Pull them, then delete them from the TV. Songs without timed lyrics leave nothing.

## Ground truth (PC, GPU recommended)

`truth.py` separates the vocals with Demucs `htdemucs`, force-aligns the known lyric words with
torchaudio's MMS aligner, and writes `<clip>.truth.json`: per line, the true offset (lyric time
minus Spotify's reported position when the line is really sung) and the song's median.

```bash
python truth.py recordings/*.csv     # needs torch, torchaudio, demucs, soundfile, scipy
```

## Score

`bench.py` replays the app's estimator (45 s windows every 4 s, same acceptance and agreement
rules) with each candidate signal and reports the error per song against the truth.

```bash
python bench.py recordings/*.csv
```

## Results, 28 Oct 2026 (SHIELD, Spotify, Bluetooth soundbar)

Five songs: Ancient Lullaby (reggae/hip-hop), Atlantis (folk, mono), Harlem Shuffle (soul),
Swing Set (hip-hop), You Know You're Right (grunge). True offsets: −191, −169, −219, −111, −230 ms;
lines inside a song scatter by 30–180 ms around their song's value.

| Method | Mean error |
| --- | --- |
| Fixed −185 ms correction | ~35 ms |
| Separated vocals (Demucs; PC only) | 71 ms |
| Trust Spotify as reported | 184 ms |
| Silero VAD, search narrowed to −600…+250 ms | 173 ms |
| Silero VAD, ±2 s (the app's auto-sync) | 763 ms (two songs ~1.7 s off) |
| Vocal-band / centre-channel energy | 608 ms (±2 s), 221 ms (narrowed) |
| Pitch voicing and combinations | 850–1180 ms (±2 s) |

Raw numbers in `results-2026-10-28.json` (±2 s search) and `results-2026-10-28-narrowed.json`.

The app now applies the fixed correction for Spotify (`LyricsPrefs.playerShift`); re-measure it
here if Spotify, the player list, or the TV changes.
