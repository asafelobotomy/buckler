#!/usr/bin/env python3
"""Build the land outline the weather map draws, from Natural Earth 1:50m land polygons.

NOTICE: Made with Natural Earth. Natural Earth data is in the public domain
(https://www.naturalearthdata.com/about/terms-of-use/); no permission or credit is required,
though the map credits it anyway.

The GeoJSON is fetched from the nvkelso/natural-earth-vector repository at a pinned commit, every
ring (outer shells and lake holes alike: the map only strokes and fills outlines, so a hole is
just one more ring) is simplified with Douglas-Peucker, and rings too small to see on a TV-sized
regional map are dropped.

Output: `launcher/app/src/main/assets/coastlines.bin`, all integers big-endian:

    magic        4 bytes  b"BCL1"
    ring count   int32
    then for each ring:
        point count  uint16  (the closing point is not repeated)
        min lat, max lat, min lon, max lon   int16 x 4   (bounding box, lets readers skip rings)
        points       point count x (lat int16, lon int16)

Coordinates are in hundredths of a degree (0.01 deg is about 1 km), so latitude spans
-9000..9000 and longitude -18000..18000, both well inside int16. Natural Earth already splits
polygons at the antimeridian, so no ring crosses it.

Usage: python scripts/gen_coastlines.py
"""

from __future__ import annotations

import json
import struct
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "launcher/app/src/main/assets/coastlines.bin"
COMMIT = "ca96624a56bd078437bca8184e78163e5039ad19"
URL = f"https://raw.githubusercontent.com/nvkelso/natural-earth-vector/{COMMIT}/geojson/ne_50m_land.geojson"

# Douglas-Peucker tolerance in degrees. The widest map spans about 40 degrees of longitude over
# ~1600 px, so 0.04 deg stays under a pixel and still roughly halves the point count.
TOLERANCE = 0.04
# Rings whose bounding box is smaller than this (degrees, both sides) are specks at map scale.
MIN_EXTENT = 0.15


def simplify(points: list[tuple[float, float]], tol: float) -> list[tuple[float, float]]:
    """Douglas-Peucker on an open polyline, iterative so long coasts don't hit the recursion cap."""
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    tol2 = tol * tol
    while stack:
        a, b = stack.pop()
        ax, ay = points[a]
        bx, by = points[b]
        dx, dy = bx - ax, by - ay
        seg2 = dx * dx + dy * dy
        worst, worst_d = -1, tol2
        for i in range(a + 1, b):
            px, py = points[i]
            if seg2 == 0:
                d = (px - ax) ** 2 + (py - ay) ** 2
            else:
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / seg2))
                d = (px - ax - t * dx) ** 2 + (py - ay - t * dy) ** 2
            if d > worst_d:
                worst, worst_d = i, d
        if worst >= 0:
            keep[worst] = True
            stack.append((a, worst))
            stack.append((worst, b))
    return [p for p, k in zip(points, keep) if k]


def simplify_ring(ring: list[list[float]]) -> list[tuple[float, float]]:
    """Simplify a closed ring by splitting it at the vertex farthest from its start."""
    pts = [(float(x), float(y)) for x, y in ring]
    if len(pts) > 1 and pts[0] == pts[-1]:
        pts = pts[:-1]
    if len(pts) < 4:
        return pts
    x0, y0 = pts[0]
    far = max(range(len(pts)), key=lambda i: (pts[i][0] - x0) ** 2 + (pts[i][1] - y0) ** 2)
    first = simplify(pts[: far + 1], TOLERANCE)
    second = simplify(pts[far:] + [pts[0]], TOLERANCE)
    return first[:-1] + second[:-1]


def rings_of(geometry: dict) -> list[list[list[float]]]:
    if geometry["type"] == "Polygon":
        return list(geometry["coordinates"])
    if geometry["type"] == "MultiPolygon":
        return [ring for polygon in geometry["coordinates"] for ring in polygon]
    return []


def encode(rings: list[list[tuple[float, float]]]) -> bytes:
    out = bytearray(b"BCL1")
    out += struct.pack(">i", len(rings))
    for ring in rings:
        lats = [round(lat * 100) for _, lat in ring]
        lons = [round(lon * 100) for lon, _ in ring]
        out += struct.pack(">H", len(ring))
        out += struct.pack(">4h", min(lats), max(lats), min(lons), max(lons))
        for lat, lon in zip(lats, lons):
            out += struct.pack(">2h", lat, lon)
    return bytes(out)


def main() -> None:
    with urllib.request.urlopen(URL, timeout=120) as response:
        data = json.load(response)
    rings: list[list[tuple[float, float]]] = []
    total_in = 0
    for feature in data["features"]:
        for ring in rings_of(feature["geometry"]):
            total_in += len(ring)
            xs = [p[0] for p in ring]
            ys = [p[1] for p in ring]
            if max(xs) - min(xs) < MIN_EXTENT and max(ys) - min(ys) < MIN_EXTENT:
                continue
            small = simplify_ring(ring)
            if len(small) < 3:
                continue
            if len(small) > 0xFFFF:
                raise SystemExit(f"ring of {len(small)} points does not fit a uint16 count")
            rings.append(small)
    blob = encode(rings)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(blob)
    total_out = sum(len(r) for r in rings)
    print(f"{len(rings)} rings, {total_in} -> {total_out} points, {len(blob) / 1024:.0f} KiB -> {OUT}")


if __name__ == "__main__":
    main()
