"""Load one benchmark recording from the TV: lyric lines, word times, frames, and stereo audio.

Times: `at` is when audio was mixed (elapsedRealtime ms); `reported` is Spotify's position then.
Offset convention (as in the app): audio song time = reported + offset, so a line is heard when
reported = line time - offset.
"""
import itertools
import os
import re

import numpy as np

RATE = 48_000


class Clip:
    def __init__(self, csv_path):
        self.name = os.path.basename(csv_path)
        self.lines, self.words, self.frames, self.chunks = [], [], [], []
        with open(csv_path, encoding="utf-8", errors="replace") as fh:
            rows = fh.read().splitlines()
        for row in rows:
            p = row.rstrip("\n").split(",")
            try:
                if p[0] == "l":
                    self.lines.append((int(p[1]), int(p[2]) if p[2] else None, ",".join(p[3:]).strip()))
                elif p[0] == "w":
                    self.words.append((int(p[1]), int(p[2]), int(p[3]), ",".join(p[4:]).strip()))
                elif p[0] == "f" and len(p) == 4:
                    self.frames.append((int(p[1]), int(p[2]), float(p[3])))
                elif p[0] == "c" and len(p) == 3:
                    self.chunks.append((int(p[1]), int(p[2])))
            except ValueError:
                pass
        self.frames = np.array(self.frames, float)  # at, reported, prob
        raw_path = csv_path[:-4] + ".raw"
        data = np.fromfile(raw_path, dtype="<i2") if os.path.exists(raw_path) else np.zeros(0, "<i2")
        n = len(data) // 2
        self.stereo = data[: n * 2].reshape(-1, 2).astype(np.float32) / 32768.0
        # Mixed time of every sample, from each chunk's first-frame time.
        t = np.empty(n, float)
        k = 0
        for at, count in self.chunks:
            count = min(count, n - k)
            if count <= 0:
                break
            t[k:k + count] = at + np.arange(count) * 1000.0 / RATE
            k += count
        self.stereo, self.time = self.stereo[:k], t[:k]
        # Keep only the stretch after the last seek or restart (reported position jumps).
        f = self.frames
        jumps = np.where(np.abs(np.diff(f[:, 1]) - np.diff(f[:, 0])) > 1000)[0]
        if len(jumps):
            edges = [0] + [j + 1 for j in jumps] + [len(f)]
            a, b = max(itertools.pairwise(edges), key=lambda e: e[1] - e[0])
            self.frames = f[a:b]
            keep = (self.time >= f[a, 0]) & (self.time <= f[b - 1, 0] + 32)
            self.stereo, self.time = self.stereo[keep], self.time[keep]

    def reported_at(self, at):
        """Spotify's reported position at mixed time(s) `at`, from the frame log."""
        return np.interp(at, self.frames[:, 0], self.frames[:, 1])

    def sung_lines(self):
        return [(i, l) for i, l in enumerate(self.lines) if re.search(r"[A-Za-z]", l[2])]
