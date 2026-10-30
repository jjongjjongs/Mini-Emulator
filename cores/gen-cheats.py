#!/usr/bin/env python3
"""Builds app/src/main/assets/cheats.bin, the cheat files of libretro-database for the
consoles whose engines take cheats, so the app can match a ROM to its cheats offline and
download just that file.

Usage: ./cores/gen-cheats.py [work dir]   (needs git; fetches only the cheat folders)

Output (gzip text; not named .gz because the Android build unpacks those):
  #<folder>
  <file name without .cht>\t<number of cheats>
The app downloads a file from
  https://raw.githubusercontent.com/libretro/libretro-database/master/cht/<folder>/<name>.cht
"""
import gzip, os, re, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "cheats.bin")

FOLDERS = [
    "Nintendo - Game Boy Advance", "Nintendo - Game Boy Color", "Nintendo - Game Boy",
    "Nintendo - Nintendo DS", "Nintendo - Super Nintendo Entertainment System",
    "Nintendo - Nintendo Entertainment System", "Sega - Mega Drive - Genesis",
    "Sega - Master System - Mark III", "Sega - Game Gear", "NEC - PC Engine - TurboGrafx 16",
    "NEC - PC Engine CD - TurboGrafx-CD", "Nintendo - Nintendo 64", "Sony - PlayStation Portable",
    "Sony - PlayStation", "Sega - Mega-CD - Sega CD", "Sega - 32X", "Sega - Saturn",
    "Sega - Dreamcast", "SNK - Neo Geo Pocket", "SNK - Neo Geo Pocket Color",
]
# (WonderSwan's files are all ROM patches its engine can't take, so it has none.)
DESC = re.compile(r'^\s*cheat(\d+)_desc\s*=', re.M)
CODE = re.compile(r'^\s*cheat(\d+)_code\s*=\s*"([^"]*)"', re.M)
FIELD = re.compile(r'^\s*cheat(\d+)_(handler|memory_search_size)\s*=\s*"?([^"\n]*)"?', re.M)


def count_cheats(text):
    """Cheats the app can use, as Cheats.parse() keeps them: a code without "??" (a value
    left for the player to fill in), or a RetroArch address cheat (handler 1) of 1, 2 or 4
    bytes, which the app applies itself."""
    usable = {m.group(1) for m in CODE.finditer(text) if m.group(2).strip() and "??" not in m.group(2)}
    fields = {}
    for m in FIELD.finditer(text):
        fields.setdefault(m.group(1), {})[m.group(2)] = m.group(3).strip()
    for index, f in fields.items():
        if f.get("handler") == "1" and f.get("memory_search_size", "3") in ("3", "4", "5"):
            usable.add(index)
    return len(usable)


def checkout(work):
    path = os.path.join(work, "libretro-database")
    if not os.path.isdir(path):
        subprocess.run(["git", "clone", "-q", "--filter=blob:none", "--no-checkout", "--depth", "1",
                        "https://github.com/libretro/libretro-database.git", path], check=True)
        subprocess.run(["git", "-C", path, "sparse-checkout", "init", "--no-cone"], check=True)
        subprocess.run(["git", "-C", path, "sparse-checkout", "set"] + ["cht/%s/" % f for f in FOLDERS], check=True)
        subprocess.run(["git", "-C", path, "checkout", "-q"], check=True)
    return path


def main():
    work = sys.argv[1] if len(sys.argv) > 1 else tempfile.mkdtemp()
    root = checkout(work)
    lines = []
    for folder in FOLDERS:
        entries = []
        directory = os.path.join(root, "cht", folder)
        for name in sorted(os.listdir(directory)):
            path = os.path.join(directory, name)
            if not name.endswith(".cht") or os.path.islink(path) or not os.path.isfile(path):
                continue
            text = open(path, encoding="utf-8", errors="replace").read()
            # Only cheats that have a code count (some files list descriptions alone).
            count = count_cheats(text)
            if count:
                entries.append("%s\t%d" % (name[:-4], count))
        print("%s: %d" % (folder, len(entries)), file=sys.stderr)
        lines.append("#" + folder)
        lines.extend(entries)
    with gzip.GzipFile(OUT, "wb", mtime=0) as f:
        f.write(("\n".join(lines) + "\n").encode())
    print("wrote %s (%d bytes)" % (OUT, os.path.getsize(OUT)), file=sys.stderr)


if __name__ == "__main__":
    main()
