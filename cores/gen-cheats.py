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
    "Sony - PlayStation", "Sega - Mega-CD - Sega CD", "Sega - 32X",
]
DESC = re.compile(r'^\s*cheat(\d+)_desc\s*=', re.M)
CODE = re.compile(r'^\s*cheat(\d+)_code\s*=\s*"([^"]*)"', re.M)


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
            count = len({m.group(1) for m in CODE.finditer(text) if m.group(2).strip()})
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
