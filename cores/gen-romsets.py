#!/usr/bin/env python3
"""Builds app/src/main/assets/romsets.bin, the romset index the app uses to
recognise arcade ZIPs by their contents (CRC32) instead of their file name.

The cores look games up by ZIP file name, so a ROM saved as e.g. "봄버맨.zip"
is unknown to them. The app reads the CRCs from the ZIP directory, finds the
matching set here and renames the file.

Usage: ./cores/gen-romsets.py   (downloads the DATs for the pinned commits)

Output (gzip text; not named .gz because the Android build unpacks those), one section per core:
  #<core>
  !bios\t<crcs of BIOS sets, ignored when matching>
  !biosnames\t<names of BIOS sets>
  <set>\t<parent or ->\t<own crcs, comma separated>\t<crcs shared with parent>\t<BIOS set or ->\t<x if the core marks it not working>
"""
import gzip, os, re, sys, urllib.parse, urllib.request, xml.etree.ElementTree as ET, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "romsets.bin")


def pinned(name, script="build-cores.sh"):
    m = re.search(name + r"_COMMIT=([0-9a-f]+)", open(os.path.join(HERE, script)).read())
    return m.group(1)


SOURCES = [
    ("fbneo", "https://raw.githubusercontent.com/libretro/FBNeo/%s/dats/"
              "FinalBurn%%20Neo%%20(ClrMame%%20Pro%%20XML%%2C%%20Arcade%%20only).dat" % pinned("FBNEO")),
    ("mame2003plus", "https://raw.githubusercontent.com/libretro/mame2003-plus-libretro/%s/metadata/"
                     "mame2003-plus.xml" % pinned("MAME2003P")),
    ("mame2010", "https://raw.githubusercontent.com/libretro/mame2010-libretro/%s/metadata/"
                 "mame2010.xml" % pinned("MAME2010", "build-mame2010.sh")),
    ("mame2015", "https://raw.githubusercontent.com/libretro/mame2015-libretro/%s/metadata/"
                 "mame2015-xml.zip" % pinned("MAME2015", "build-mame2015.sh")),
]


def load(url):
    cache = os.path.join(HERE, "build", "dats", urllib.parse.unquote(os.path.basename(url)))
    if not os.path.exists(cache):
        os.makedirs(os.path.dirname(cache), exist_ok=True)
        urllib.request.urlretrieve(url, cache)
    if cache.endswith(".zip"):
        with zipfile.ZipFile(cache) as z:
            name = next(n for n in z.namelist() if n.endswith(".xml"))
            with z.open(name) as f:
                return ET.parse(f).getroot()
    return ET.parse(cache).getroot()


def section(core, root):
    games = root.findall("game") + root.findall("machine")
    bios_crcs = set()
    for g in games:
        if g.get("isbios") == "yes" or g.get("runnable") == "no":
            bios_crcs.update(r.get("crc").lower() for r in g.findall("rom") if r.get("crc"))
    bios_names = sorted(g.get("name") for g in games if g.get("isbios") == "yes" or g.get("runnable") == "no")
    by_name = {g.get("name"): g for g in games}

    def bios_of(g):
        seen = set()
        while g is not None and g.get("name") not in seen:
            seen.add(g.get("name"))
            if g.get("isbios") == "yes" or g.get("runnable") == "no":
                return g.get("name")
            g = by_name.get(g.get("romof"))
        return None

    lines = ["#" + core, "!bios\t" + ",".join(sorted(bios_crcs)), "!biosnames\t" + ",".join(bios_names)]
    for g in games:
        if g.get("isbios") == "yes" or g.get("runnable") == "no":
            continue
        # Devices and slot machines are not games anyone launches here.
        if g.get("isdevice") == "yes" or g.get("ismechanical") == "yes":
            continue
        own, shared = [], []
        for r in g.findall("rom"):
            crc = (r.get("crc") or "").lower()
            if not crc or r.get("status") == "nodump" or crc in bios_crcs or r.get("bios"):
                continue
            (shared if r.get("merge") else own).append(crc)
        if not own and not shared:
            continue
        driver = g.find("driver")
        broken = driver is not None and driver.get("status") in ("preliminary", "protection")
        lines.append("%s\t%s\t%s\t%s\t%s\t%s" % (g.get("name"), g.get("cloneof") or "-",
                                                 ",".join(dict.fromkeys(own)), ",".join(dict.fromkeys(shared)),
                                                 bios_of(g) or "-", "x" if broken else "-"))
    return lines


def main():
    lines = []
    for core, url in SOURCES:
        part = section(core, load(url))
        print("%s: %d sets" % (core, len(part) - 3), file=sys.stderr)
        lines += part
    with gzip.GzipFile(OUT, "wb", mtime=0) as f:
        f.write(("\n".join(lines) + "\n").encode())
    print("wrote %s (%d bytes)" % (OUT, os.path.getsize(OUT)), file=sys.stderr)


if __name__ == "__main__":
    main()
