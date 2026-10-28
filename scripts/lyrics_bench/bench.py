"""Score lyric auto-sync methods against ground truth (truth.py) on the recorded clips.

Every method feeds a per-frame signal (on the app's 32 ms frame grid) into a port of the app's
SyncEstimator and its publish rule, so only the signal differs between methods.
"""
import json
import os
import sys

import numpy as np
import scipy.signal as sg
from clip import Clip

FRAME_MS, WINDOW_MS, SEARCH_MS, EVERY_MS = 32, 45_000, 2_000, 4_000
MIN_SCORE, MIN_MARGIN, AGREE, AGREE_WITHIN, AGREE_SPAN = 0.12, 0.05, 3, 100, 12_000


def spans(lines):
    sung = [l for l in lines if l[2].strip()]
    out = []
    for i, (at, end, _) in enumerate(sung):
        nxt = sung[i + 1][0] if i + 1 < len(sung) else None
        e = end or (at + (nxt - at) * 9 // 10 if nxt else at + 3000)
        out.append((at, max(e, at + 200)))
    return np.array(out, float)


def corr(a, b):
    if len(a) < 2 or a.std() == 0 or b.std() == 0:
        return 0.0
    return float(np.corrcoef(a, b)[0, 1])


def search(pos, level, sp):
    starts = sp[:, 0]
    k = 3
    rise = np.zeros_like(level)
    rise[k:] = np.maximum(0, level[k:] - level[:-k])
    offs = np.arange(-SEARCH_MS, SEARCH_MS + 1, FRAME_MS)
    scores = []
    for off in offs:
        s = pos + off
        m = (s >= starts[0] - 2000) & (s <= sp[-1, 1])
        if m.sum() < 400:
            scores.append(-1.0)
            continue
        idx = np.searchsorted(starts, s, side="right") - 1
        act = ((idx >= 0) & (s <= sp[np.clip(idx, 0, len(sp) - 1), 1])).astype(float)
        j = np.searchsorted(starts, s)
        d = np.minimum(np.where(j < len(starts), starts[np.clip(j, 0, len(starts) - 1)] - s, 1e9),
                       np.where(j > 0, s - starts[np.clip(j - 1, 0, len(starts) - 1)], 1e9))
        on = np.exp(-0.5 * (d / 120.0) ** 2)
        scores.append(corr(level[m], act[m]) + corr(rise[m], on[m]))
    scores = np.array(scores)
    b = int(np.argmax(scores))
    rival = max((scores[i] for i in range(len(offs)) if abs(i - b) > 12), default=-1)
    return int(offs[b]), float(scores[b]), float(scores[b] - rival)


def replay(at, pos, level, sp):
    """The app's loop: estimate every 4 s on the last 45 s; publish on 3 agreeing over 12 s."""
    accepted = []
    for end in range(len(at)):
        if end == 0 or at[end] - at[0] < 13_000 or (at[end] - at[0]) % EVERY_MS >= FRAME_MS:
            continue
        lo = np.searchsorted(at, at[end] - WINDOW_MS)
        off, score, margin = search(pos[lo:end + 1], level[lo:end + 1], sp)
        if score >= MIN_SCORE and margin >= MIN_MARGIN:
            accepted.append((at[end], off))
            agree = [a for a in accepted if abs(a[1] - off) <= AGREE_WITHIN]
            if len(agree) >= AGREE and agree[-1][0] - agree[0][0] >= AGREE_SPAN:
                return int(np.median([a[1] for a in agree])), (at[end] - at[0]) / 1000
    return None, None


# ---------- per-frame signals ----------

def stft_grid(x16, at, t16):
    """Complex STFT frames (512-sample, 32 ms) starting at each frame time."""
    starts = np.clip(np.searchsorted(t16, at), 0, len(x16) - 512)
    win = np.hanning(512)
    frames = np.stack([x16[s:s + 512] * win for s in starts])
    return np.fft.rfft(frames, n=512, axis=1)


def signals(clip, vocals16=None):
    at, pos, prob = clip.frames[:, 0], clip.frames[:, 1], clip.frames[:, 2]
    L = sg.resample_poly(clip.stereo[:, 0], 1, 3)
    R = sg.resample_poly(clip.stereo[:, 1], 1, 3)
    t16 = clip.time[::3][: len(L)]
    FL, FR = stft_grid(L, at, t16), stft_grid(R, at, t16)
    freqs = np.fft.rfftfreq(512, 1 / 16000)
    band = (freqs >= 200) & (freqs <= 4000)
    mono = np.abs(FL[:, band] + FR[:, band]) ** 2
    centre = np.maximum(0, np.real(FL[:, band] * np.conj(FR[:, band])))  # energy shared by L and R
    side = np.abs(FL[:, band] - FR[:, band]) ** 2
    centre_only = np.maximum(0, mono - side)
    out = {}
    p = np.clip(prob, 1e-3, 1 - 1e-3)
    out["A silero"] = smooth(np.log(p / (1 - p)))
    out["B band mono"] = smooth(np.log(mono.sum(1) + 1e-9))
    out["C centre"] = smooth(np.log(centre_only.sum(1) + 1e-9))
    out["C2 L*R coherent"] = smooth(np.log(centre.sum(1) + 1e-9))
    out["D voicing"] = smooth(voicing((L + R) / 2, at, t16))
    if vocals16 is not None:
        tv = clip.time[0] + np.arange(len(vocals16)) * 1000 / 16000
        FV = stft_grid(vocals16, at, tv)
        out["V demucs vocals (ceiling)"] = smooth(np.log((np.abs(FV[:, band]) ** 2).sum(1) + 1e-9))
    z = {k: (v - v.mean()) / (v.std() + 1e-9) for k, v in out.items()}
    out["A+C"] = (z["A silero"] + z["C centre"]) / 2
    out["C+D"] = (z["C centre"] + z["D voicing"]) / 2
    out["A+C+D"] = (z["A silero"] + z["C centre"] + z["D voicing"]) / 3
    return at, pos, out


def smooth(x, n=5):
    return np.convolve(x, np.ones(n) / n, mode="same")


def voicing(x, at, t, lo=80, hi=1000):
    """Best normalised autocorrelation in the singing pitch range: how periodic (voiced) a frame is."""
    starts = np.clip(np.searchsorted(t, at), 0, len(x) - 1024)
    lags = np.arange(16000 // hi, 16000 // lo)
    v = np.zeros(len(starts))
    for i, s in enumerate(starts):
        f = x[s:s + 1024] - x[s:s + 1024].mean()
        e = np.dot(f, f)
        if e < 1e-6:
            continue
        ac = np.correlate(f, f, "full")[1023:1023 + lags[-1] + 1]
        v[i] = ac[lags].max() / ac[0]
    return v


def main(paths):
    results = {}
    for csv in paths:
        clip = Clip(csv)
        with open(csv[:-4] + ".truth.json") as fh:
            truth = json.load(fh)
        true = truth["offset_median"]
        vpath = csv[:-4] + ".vocals.npy"
        vocals = np.load(vpath) if os.path.exists(vpath) else None
        at, pos, sig = signals(clip, vocals)
        sp = spans(clip.lines)
        song = clip.name.split("-")[1][:28]
        results[song] = {"truth": true, "spread": truth["offset_mad"], "methods": {}}
        results[song]["methods"]["0 trust Spotify (no sync)"] = {"published": 0, "after_s": 0, "full": 0}
        for name, level in sig.items():
            pub, after = replay(at, pos, level, sp)
            full, score, margin = search(pos, level, sp)
            results[song]["methods"][name] = {"published": pub, "after_s": after, "full": full,
                                              "full_score": score, "full_margin": margin}
        print(f"{song}: truth {true:+.0f} ms (line spread {truth['offset_mad']:.0f})", flush=True)
    with open("bench_results.json", "w") as fh:
        json.dump(results, fh, indent=1)
    report(results)


def report(results):
    names = list(next(iter(results.values()))["methods"])
    print(f"\n{'method':28} {'published err per song (ms; - = abstained)':60} MAE  full-clip MAE")
    for n in names:
        errs, fulls, cells = [], [], []
        for r in results.values():
            m = r["methods"][n]
            if m["published"] is None:
                cells.append("   -")
            else:
                e = abs(m["published"] - r["truth"])
                errs.append(e)
                cells.append(f"{e:4.0f}")
            fulls.append(abs(m["full"] - r["truth"]))
        mae = f"{np.mean(errs):4.0f}" if errs else "   -"
        print(f"{n:28} {' '.join(cells):60} {mae}  {np.mean(fulls):4.0f}  ({len(errs)}/{len(results)} answered)")


if __name__ == "__main__":
    main(sys.argv[1:])
