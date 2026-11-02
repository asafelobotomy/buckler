#!/usr/bin/env python3
"""Build the curated comic strips the Comics stage shows panel by panel.

The strips are the 1930 Mickey Mouse dailies on the Internet Archive (items
mickey-mouse-comic-strip-1930-01 .. -12, uploaded under the Public Domain Mark; restored reprint
scans with solid panel frames and white gutters). Each strip is fetched at 1600 px wide through
the Archive's IIIF image service, its panels are found from their frame lines, and the result is
written for the app with the panel boxes as fractions of the strip:

    launcher/app/src/main/assets/comics_mickey1930.json

    {"credit": "...", "strips": [{"item": ..., "file": ..., "date": "1930-05-05",
                                 "panels": [[x0, y0, x1, y1], ...]}, ...]}

`panels` is empty when the strip is to be shown whole. A strip is split only when its frame lines
give 2 to 8 panels of a sensible shape that between them hold nearly all of its ink; anything
less certain is shown whole. February's strips are laid out in two rows, so they are left out.

Every split is meant to be looked at once. The script draws contact sheets (12 strips each, the
boxes in red, whole strips in blue) into the cache folder; mark any wrong one in
`scripts/comic_panels_review.json` as {"1930-11-06": "whole"} (or "skip" to drop it) and run the
script again. Downloads are cached, so a rerun only redoes the detection.

Needs Pillow (`pip install pillow`) besides the project's own dependencies.

Usage: python scripts/gen_comic_panels.py [--cache DIR]
"""

from __future__ import annotations

import argparse
import io
import itertools
import json
import time
import urllib.parse
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "launcher/app/src/main/assets/comics_mickey1930.json"
REVIEW = Path(__file__).with_name("comic_panels_review.json")
ITEMS = [f"mickey-mouse-comic-strip-1930-{m:02d}" for m in range(1, 13)]
WIDTH = 1600
CREDIT = "Mickey Mouse · Walt Disney Studio · public domain (Internet Archive)"
UA = {"User-Agent": "buckler-gen-comic-panels/1 (https://github.com/asafelobotomy/buckler)"}


def fetch(url: str, tries: int = 6) -> bytes:
    """GET with patient retries: the IIIF service's first render of a large scan can time out."""
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
                return r.read()
        except Exception:
            if attempt == tries - 1:
                raise
            time.sleep(10 * (attempt + 1))
    raise AssertionError


def iiif(item: str, name: str) -> str:
    ident = urllib.parse.quote(f"{item}/{name}", safe="")
    return f"https://iiif.archive.org/image/iiif/3/{ident}/full/{WIDTH},/0/default.jpg"


def strip_files(item: str) -> list[str]:
    meta = json.loads(fetch(f"https://archive.org/metadata/{item}"))
    names = [f["name"] for f in meta["files"] if f.get("source") == "original" and f["name"].lower().endswith(".jpg")]
    return sorted(n for n in names if "mickey mouse comic" in n.lower())


def otsu(values: list[int]) -> int:
    hist = [0] * 256
    for v in values:
        hist[v] += 1
    total = len(values)
    weighted = sum(i * h for i, h in enumerate(hist))
    below = count = 0
    best, threshold = 0.0, 128
    for i in range(256):
        count += hist[i]
        if count == 0 or count == total:
            continue
        below += i * hist[i]
        mean_low = below / count
        mean_high = (weighted - below) / (total - count)
        spread = count * (total - count) * (mean_low - mean_high) ** 2
        if spread > best:
            best, threshold = spread, i
    return threshold


def longest_run(flags: list[bool], tolerance: int = 6) -> int:
    """The longest stretch of ink, bridging gaps up to [tolerance] (scanned lines break)."""
    best = 0
    start = last = None
    for i, on in enumerate(flags):
        if on:
            if start is None or i - last > tolerance:
                if start is not None:
                    best = max(best, last - start + 1)
                start = i
            last = i
    if start is not None:
        best = max(best, last - start + 1)
    return best


def detect(image: Image.Image) -> list[tuple[float, float, float, float]]:
    """Panel boxes as fractions of the strip, or [] to show it whole."""
    gray = image.convert("L")
    w, h = gray.size
    px = list(gray.get_flattened_data() if hasattr(gray, "get_flattened_data") else gray.getdata())
    t = otsu(px)
    ink = [v < t for v in px]
    rows = [sum(ink[y * w:(y + 1) * w]) for y in range(h)]
    inked = [y for y in range(h) if rows[y] > w * 0.004]
    if not inked:
        return []
    top, bottom = inked[0], inked[-1]
    height = bottom - top + 1
    # Frame edges: columns whose ink runs nearly the strip's full height.
    edges = [x for x in range(w) if longest_run([ink[y * w + x] for y in range(top, bottom + 1)]) >= 0.75 * height]
    clusters: list[list[int]] = []
    for x in edges:
        if clusters and x - clusters[-1][1] <= 4:
            clusters[-1][1] = x
        else:
            clusters.append([x, x])
    panels = []
    for left, right in itertools.pairwise(clusters):
        if right[0] - left[1] > 0.06 * w:  # wide: a panel; narrow: a gutter between two frames
            panels.append((left[0], right[1]))
    if not 2 <= len(panels) <= 8:
        return []
    # Even widths: only the first panel may be narrow (it is often a caption box).
    widths = sorted(b - a for a, b in panels)
    median = widths[len(widths) // 2]
    if any((b - a) < (0.25 if i == 0 else 0.5) * median for i, (a, b) in enumerate(panels)):
        return []
    # A frame-like line well inside a box means a missed gutter (two panels in one) or a tall
    # drawn line the frames were confused with: either way, not a split to trust.
    margin = 0.03 * w
    for a, b in panels:
        for x in range(int(a + margin), int(b - margin)):
            if longest_run([ink[y * w + x] for y in range(top, bottom + 1)]) >= 0.6 * height:
                return []
    if any(not 0.25 <= (b - a) / height <= 2.2 for a, b in panels):
        return []
    # The panels must hold nearly all of the strip's ink, or one was missed.
    cols = [sum(ink[y * w + x] for y in range(top, bottom + 1, 2)) for x in range(w)]
    total = sum(cols)
    held = sum(sum(cols[a:b + 1]) for a, b in panels)
    if total == 0 or held / total < 0.95:
        return []
    return [(round(a / w, 4), round(top / h, 4), round((b + 1) / w, 4), round((bottom + 1) / h, 4)) for a, b in panels]


def sheet(entries: list[tuple[str, Image.Image, list, str]], path: Path) -> None:
    tile_w = 1200
    tiles = []
    for date, img, panels, status in entries:
        scaled = img.convert("RGB").resize((tile_w, round(img.size[1] * tile_w / img.size[0])))
        draw = ImageDraw.Draw(scaled)
        sw, sh = scaled.size
        if panels:
            for x0, y0, x1, y1 in panels:
                draw.rectangle((x0 * sw, y0 * sh, x1 * sw - 1, y1 * sh - 1), outline=(220, 30, 30), width=4)
        else:
            draw.rectangle((0, 0, sw - 1, sh - 1), outline=(30, 90, 220), width=6)
        label = Image.new("RGB", (tile_w, 28), (30, 30, 30))
        ImageDraw.Draw(label).text((8, 6), f"{date}  {status}", fill=(255, 255, 255))
        tiles += [label, scaled]
    out = Image.new("RGB", (tile_w, sum(t.size[1] for t in tiles)), (30, 30, 30))
    y = 0
    for t in tiles:
        out.paste(t, (0, y))
        y += t.size[1]
    out.save(path)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--cache", type=Path, default=Path.home() / ".cache/buckler/comics")
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    review = json.loads(REVIEW.read_text()) if REVIEW.exists() else {}
    strips, pending = [], []
    sheets = 0
    for item in ITEMS:
        if item.endswith("-02"):
            continue  # two rows of panels a day: not a band-shaped strip
        for name in strip_files(item):
            date = Path(name).name[:10]
            cached = args.cache / f"{date}.jpg"
            if not cached.exists():
                try:
                    cached.write_bytes(fetch(iiif(item, name)))
                except Exception as e:  # noqa: BLE001 - left out, and said so
                    print(date, f"left out: {e}", flush=True)
                    continue
                time.sleep(1)
            img = Image.open(io.BytesIO(cached.read_bytes()))
            if img.size[0] / img.size[1] < 2.2:
                continue
            verdict = review.get(date)
            if verdict == "skip":
                continue
            panels = [] if verdict == "whole" else detect(img)
            status = f"{len(panels)} panels" if panels else "whole"
            if verdict:
                status += f" (review: {verdict})"
            strips.append({"item": item, "file": name, "date": date, "panels": [list(p) for p in panels]})
            pending.append((date, img, panels, status))
            if len(pending) == 12:
                sheets += 1
                sheet(pending, args.cache / f"sheet-{sheets:02d}.png")
                pending = []
            print(date, status, flush=True)
    if pending:
        sheets += 1
        sheet(pending, args.cache / f"sheet-{sheets:02d}.png")
    OUT.write_text(json.dumps({"credit": CREDIT, "strips": strips}, separators=(",", ":")) + "\n")
    split = sum(1 for s in strips if s["panels"])
    print(f"{len(strips)} strips ({split} split into panels) -> {OUT.relative_to(ROOT)}")
    print(f"contact sheets: {args.cache}/sheet-01.png .. sheet-{sheets:02d}.png")


if __name__ == "__main__":
    main()
