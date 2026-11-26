#!/usr/bin/env python3
"""Builds the PC-98 font NP2kai reads (<system>/np2kai/font.bmp) from the Shinonome
(東雲) 16-dot bitmap fonts, which are in the public domain (conversion and redistribution
allowed). Without a font the libretro build of NP2kai (no TrueType support) shows no
Japanese text; a dump of a real machine's font.rom, when the player has one, wins over it.

Layout (NP2kai font/fontpc98.c, font/fontmake.c): a 2048x2048 1-bit BMP, ink = 0 bits.
  ANK (JIS X 0201) 8x16: top row, x = code * 8.
  Kanji (JIS X 0208) 16x16: x = (first byte - 0x20) * 16, y = second byte * 16.

Usage: gen-pc98-font.py KANJI16.bdf ANK8x16.bdf OUT.bmp
"""
import struct
import sys

W = H = 2048
LINE = W // 8


def read_bdf(path):
    glyphs = {}
    fbb = None
    with open(path, encoding="latin-1") as f:
        lines = f.read().splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("FONTBOUNDINGBOX"):
            fbb = [int(v) for v in line.split()[1:5]]
        if line.startswith("STARTCHAR"):
            enc = None
            bbx = None
            i += 1
            while not lines[i].startswith("BITMAP"):
                if lines[i].startswith("ENCODING"):
                    enc = int(lines[i].split()[1])
                elif lines[i].startswith("BBX"):
                    bbx = [int(v) for v in lines[i].split()[1:5]]
                i += 1
            i += 1
            rows = []
            while not lines[i].startswith("ENDCHAR"):
                rows.append(int(lines[i], 16))
                i += 1
            if enc is not None and enc >= 0:
                glyphs[enc] = (bbx, rows)
        i += 1
    return fbb, glyphs


def put(img, x0, y0, w, h, fbb, glyph):
    """Draws a BDF glyph into the cell (x0, y0, w, h), aligned on the font's baseline."""
    (gw, gh, gx, gy), rows = glyph
    fw, fh, fx, fy = fbb
    bytes_per_row = (gw + 7) // 8
    top = (fh + fy) - (gh + gy)   # rows from the cell top to the glyph's top
    for r, bits in enumerate(rows):
        y = y0 + top + r
        if y < y0 or y >= y0 + h:
            continue
        for c in range(gw):
            if bits & (1 << (bytes_per_row * 8 - 1 - c)):
                x = x0 + gx - fx + c
                if x0 <= x < x0 + w:
                    img[y][x] = 1


def main():
    kanji_path, ank_path, out = sys.argv[1:4]
    img = [bytearray(W) for _ in range(H)]   # 1 = ink
    kfbb, kanji = read_bdf(kanji_path)
    afbb, ank = read_bdf(ank_path)
    for code, glyph in ank.items():
        if 0x20 <= code <= 0xFF:
            put(img, code * 8, 0, 8, 16, afbb, glyph)
    count = 0
    for code, glyph in kanji.items():
        hi, lo = code >> 8, code & 0xFF
        if 0x21 <= hi <= 0x7E and 0x21 <= lo <= 0x7E:
            put(img, (hi - 0x20) * 16, lo * 16, 16, 16, kfbb, glyph)
            count += 1
    data = bytearray()
    for y in range(H - 1, -1, -1):           # bottom-up
        row = img[y]
        for x in range(0, W, 8):
            b = 0
            for k in range(8):
                if not row[x + k]:            # paper = 1, ink = 0
                    b |= 0x80 >> k
            data.append(b)
    assert len(data) == LINE * H
    off = 14 + 40 + 8
    header = b"BM" + struct.pack("<IHHI", off + len(data), 0, 0, off)
    info = struct.pack("<IiiHHIIiiII", 40, W, H, 1, 1, 0, len(data), 2835, 2835, 2, 2)
    palette = bytes([0, 0, 0, 0, 255, 255, 255, 0])
    with open(out, "wb") as f:
        f.write(header + info + palette + data)
    print("font.bmp: %d ANK, %d kanji" % (len(ank), count))


if __name__ == "__main__":
    main()
