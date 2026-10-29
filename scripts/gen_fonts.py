#!/usr/bin/env python3
"""Build the bundled fonts for Settings → Appearance → Font from Google Fonts (SIL OFL).

Each family is fetched from the google/fonts repository at a pinned commit, cut to a Regular
(400) and a Bold (700) instance when it is a variable font, and trimmed to Latin (with the
accented letters of European languages) so the app stays small; anything else falls back to the
system font. Output: `launcher/app/src/main/res/font/<name>_regular.ttf` / `_bold.ttf`, and each
family's licence as `launcher/branding/<Family>-OFL.txt`. Needs fontTools.

Usage: python scripts/gen_fonts.py
"""

from __future__ import annotations

import io
import urllib.request
from pathlib import Path

from fontTools import subset
from fontTools.otlLib.optimize.gpos import compact
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = Path(__file__).resolve().parent.parent
FONT_DIR = ROOT / "launcher/app/src/main/res/font"
LICENCE_DIR = ROOT / "launcher/branding"
COMMIT = "9862abc066c9b83bf84c3e8ed1fc2d8d48f9bbf0"
BASE = f"https://raw.githubusercontent.com/google/fonts/{COMMIT}/ofl"

# resource name: (folder, family name, regular file, bold file or None for a variable font)
FAMILIES = {
    "inter": ("inter", "Inter", "Inter[opsz,wght].ttf", None),
    "montserrat": ("montserrat", "Montserrat", "Montserrat[wght].ttf", None),
    "poppins": ("poppins", "Poppins", "Poppins-Regular.ttf", "Poppins-Bold.ttf"),
    "lato": ("lato", "Lato", "Lato-Regular.ttf", "Lato-Bold.ttf"),
    "opensans": ("opensans", "OpenSans", "OpenSans[wdth,wght].ttf", None),
    "nunito": ("nunito", "Nunito", "Nunito[wght].ttf", None),
    "raleway": ("raleway", "Raleway", "Raleway[wght].ttf", None),
    "oswald": ("oswald", "Oswald", "Oswald[wght].ttf", None),
    "playfair": ("playfairdisplay", "PlayfairDisplay", "PlayfairDisplay[wght].ttf", None),
    "merriweather": ("merriweather", "Merriweather", "Merriweather[opsz,wdth,wght].ttf", None),
}

# Latin, Latin-1, Latin Extended-A/B and Additional (Polish, Czech, Vietnamese…), combining marks,
# general punctuation, currency, letterlike symbols, arrows and the minus sign.
UNICODES = (
    list(range(0x20, 0x250)) + list(range(0x300, 0x370)) + list(range(0x1E00, 0x1F00))
    + list(range(0x2000, 0x2070)) + list(range(0x20A0, 0x20D0)) + list(range(0x2100, 0x2200))
    + [0x2212]
)


def fetch(folder: str, name: str) -> bytes:
    url = f"{BASE}/{folder}/{urllib.request.quote(name)}"
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "buckler"}), timeout=120) as r:
        return r.read()


def weight(font: TTFont, wght: int) -> TTFont:
    """A static instance of a variable font at [wght], other axes at their defaults."""
    if "fvar" not in font:
        return font
    axes = {a.axisTag: a.defaultValue for a in font["fvar"].axes}
    axes["wght"] = wght
    if "opsz" in axes:
        # Display sizes on a TV: the optical size meant for large text.
        top = next(a.maxValue for a in font["fvar"].axes if a.axisTag == "opsz")
        axes["opsz"] = min(top, 32)
    return instancer.instantiateVariableFont(font, axes)


def trimmed(font: TTFont) -> bytes:
    options = subset.Options()
    # The default features (kerning, ligatures, marks…); no hinting, which TV text does not need.
    options.name_IDs = ["*"]
    options.notdef_outline = True
    options.hinting = False
    options.desubroutinize = True
    options.drop_tables += ["meta"]
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=UNICODES)
    subsetter.subset(font)
    # Instancing leaves kerning pairs unpacked; packing them again saves most of their size.
    if "GPOS" in font:
        compact(font, level=9)
    out = io.BytesIO()
    font.save(out)
    return out.getvalue()


def main() -> None:
    FONT_DIR.mkdir(parents=True, exist_ok=True)
    for res, (folder, family, regular, bold) in FAMILIES.items():
        source = fetch(folder, regular)
        for style, wght, file in (("regular", 400, regular), ("bold", 700, bold)):
            raw = source if file in (None, regular) else fetch(folder, file)
            font = weight(TTFont(io.BytesIO(raw)), wght)
            data = trimmed(font)
            (FONT_DIR / f"{res}_{style}.ttf").write_bytes(data)
            print(f"{res}_{style}.ttf {len(data) // 1024} KB")
        (LICENCE_DIR / f"{family}-OFL.txt").write_bytes(fetch(folder, "OFL.txt"))


if __name__ == "__main__":
    main()
