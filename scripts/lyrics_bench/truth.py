"""Ground truth for a recording: Demucs vocals + MMS forced alignment of the known lyrics.

Writes <clip>.truth.json: per aligned word, the reported position when it is really sung, and per
line the true offset (line time - reported position of its first sung word).
"""
import json
import re
import sys

import numpy as np
import torch
import torchaudio
from clip import RATE, Clip
from demucs.apply import apply_model
from demucs.pretrained import get_model

dev = "cuda" if torch.cuda.is_available() else "cpu"


def vocals(clip):
    model = get_model("htdemucs").to(dev).eval()
    wav = torch.from_numpy(clip.stereo.T.copy())
    wav = torchaudio.functional.resample(wav, RATE, model.samplerate)
    ref = wav.mean(0)
    wav = (wav - ref.mean()) / (ref.std() + 1e-8)
    with torch.no_grad():
        out = apply_model(model, wav[None].to(dev), split=True, overlap=0.25, progress=False)[0]
    v = out[model.sources.index("vocals")].mean(0).cpu()
    return torchaudio.functional.resample(v, model.samplerate, 16_000)


def norm_word(w):
    return re.sub(r"[^a-z']", "", w.lower().replace("’", "'"))


def align(v16, clip):
    bundle = torchaudio.pipelines.MMS_FA
    model = bundle.get_model(with_star=False).to(dev).eval()
    dictionary = bundle.get_dict(star=None)
    # Lines that should be inside the clip: by lyric time, within the reported span (+-3 s slack).
    rep = clip.reported_at(clip.time[[0, -1]])
    chosen = [(i, l) for i, l in clip.sung_lines() if rep[0] + 1500 <= l[0] <= rep[1] - 6000]
    words, owner = [], []
    for i, l in chosen:
        for w in l[2].split():
            n = norm_word(w)
            if n:
                words.append(n)
                owner.append(i)
    tokens = [dictionary[c] for w in words for c in w]
    with torch.inference_mode():
        emission, _ = model(v16[None].to(dev))
    targets = torch.tensor([tokens], dtype=torch.int32, device=dev)
    labels, scores = torchaudio.functional.forced_align(emission, targets, blank=0)
    spans = torchaudio.functional.merge_tokens(labels[0], scores[0].exp())
    frame_s = v16.shape[0] / emission.shape[1] / 16_000
    out, k = [], 0
    for w, i in zip(words, owner):
        s = spans[k:k + len(w)]
        k += len(w)
        start_s = s[0].start * frame_s
        conf = float(np.mean([x.score for x in s]))
        at = clip.time[0] + start_s * 1000  # mixed time of the word's start
        out.append({"word": w, "line": i, "at": at, "reported": float(clip.reported_at(at)), "conf": conf})
    return chosen, out


def main(csv):
    clip = Clip(csv)
    v16 = vocals(clip)
    np.save(csv[:-4] + ".vocals.npy", v16.numpy())
    chosen, words = align(v16, clip)
    lines = []
    for i, l in chosen:
        ws = [w for w in words if w["line"] == i]
        if not ws:
            continue
        first = ws[0]
        lines.append({"line": i, "text": l[2], "lyric_ms": l[0], "sung_reported": first["reported"],
                      "offset": l[0] - first["reported"], "conf": float(np.mean([w["conf"] for w in ws]))})
    good = [x["offset"] for x in lines if x["conf"] > 0.05]
    truth = {"clip": clip.name, "lines": lines, "words": words,
             "offset_median": float(np.median(good)) if good else None,
             "offset_mad": float(np.median(np.abs(np.array(good) - np.median(good)))) if good else None,
             "n_lines": len(good)}
    with open(csv[:-4] + ".truth.json", "w") as fh:
        json.dump(truth, fh, indent=1)
    print(f"{clip.name}: {len(good)}/{len(lines)} confident lines, true offset {truth['offset_median']} ms, "
          f"line spread (MAD) {truth['offset_mad']} ms")


if __name__ == "__main__":
    for path in sys.argv[1:]:
        main(path)
