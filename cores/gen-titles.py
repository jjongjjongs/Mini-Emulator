#!/usr/bin/env python3
"""Builds app/src/main/assets/titles.bin, the names the app shows for arcade
romsets instead of their short ZIP names (kof98 -> 더 킹 오브 파이터즈 '98).

Each set gets the description from the DATs of the bundled cores; sets listed
in titles-ko.tsv (by parent name) also get a Korean title.

Usage: ./cores/gen-titles.py   (uses the DATs gen-romsets.py downloads)

Output (gzip text; not named .gz because the Android build unpacks those):
  <set>\t<parent or ->\t<description>\t<Korean title or ->
"""
import gzip, importlib.util, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "titles.bin")

spec = importlib.util.spec_from_file_location("genromsets", os.path.join(HERE, "gen-romsets.py"))
genromsets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(genromsets)


def korean():
    titles = {}
    for line in open(os.path.join(HERE, "titles-ko.tsv"), encoding="utf-8"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        name, title = line.split("\t")
        titles[name] = title
    return titles


def main():
    sets = {}
    for core, url in genromsets.SOURCES:
        root = genromsets.load(url)
        for g in root.findall("game") + root.findall("machine"):
            name = g.get("name")
            if name in sets or g.get("isbios") == "yes" or g.get("runnable") == "no":
                continue
            if g.get("isdevice") == "yes" or g.get("ismechanical") == "yes":
                continue
            desc = (g.findtext("description") or "").strip().replace("\t", " ")
            sets[name] = (g.get("cloneof") or "-", desc)
    ko = korean()
    unknown = sorted(n for n in ko if n not in sets)
    if unknown:
        print("not in any DAT, dropped: " + " ".join(unknown), file=sys.stderr)
    lines = []
    for name in sorted(sets):
        parent, desc = sets[name]
        title = ko.get(name) or ko.get(parent) or "-"
        lines.append("%s\t%s\t%s\t%s" % (name, parent, desc, title))
    with gzip.GzipFile(OUT, "wb", mtime=0) as f:
        f.write(("\n".join(lines) + "\n").encode())
    print("wrote %s: %d sets, %d with Korean titles (%d bytes)" % (
        OUT, len(lines), sum(1 for l in lines if not l.endswith("\t-")), os.path.getsize(OUT)), file=sys.stderr)


if __name__ == "__main__":
    main()
