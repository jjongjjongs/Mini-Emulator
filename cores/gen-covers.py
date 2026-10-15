#!/usr/bin/env python3
"""Builds app/src/main/assets/covers.bin, the box-art names of the libretro-thumbnails
repositories, so the app can match a ROM to its cover without listing folders online.

Usage: ./cores/gen-covers.py [work dir]   (needs git; fetches only the file trees)

Output (gzip text; not named .gz because the Android build unpacks those):
  #<repository>
  <box-art name without .png>
  ...
The app downloads a match from
  https://raw.githubusercontent.com/libretro-thumbnails/<repository>/master/Named_Boxarts/<name>.png
"""
import gzip, os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "covers.bin")

# Console repositories only: arcade covers are found by the set's DAT description.
REPOS = [
    "Nintendo_-_Game_Boy_Advance", "Nintendo_-_Game_Boy_Color", "Nintendo_-_Game_Boy",
    "Nintendo_-_Nintendo_DS", "Nintendo_-_Super_Nintendo_Entertainment_System",
    "Nintendo_-_Nintendo_Entertainment_System", "Sega_-_Mega_Drive_-_Genesis",
    "Sega_-_Master_System_-_Mark_III", "Sega_-_Game_Gear", "NEC_-_PC_Engine_-_TurboGrafx_16",
    "NEC_-_PC_Engine_CD_-_TurboGrafx-CD", "Nintendo_-_Nintendo_64", "Sony_-_PlayStation_Portable",
    "Sony_-_PlayStation", "Sony_-_PlayStation_2", "Sega_-_Dreamcast",
]


def names(work, repo):
    path = os.path.join(work, repo)
    if not os.path.isdir(path):
        subprocess.run(["git", "clone", "-q", "--filter=blob:none", "--no-checkout", "--depth", "1",
                        "https://github.com/libretro-thumbnails/%s.git" % repo, path], check=True)
    out = subprocess.run(["git", "-C", path, "ls-tree", "-r", "-z", "--name-only", "HEAD", "Named_Boxarts/"],
                         check=True, capture_output=True).stdout.decode("utf-8")
    result = set()
    for entry in out.split("\0"):
        if entry.startswith("Named_Boxarts/") and entry.endswith(".png") and entry.count("/") == 1:
            result.add(entry[len("Named_Boxarts/"):-4])
    return sorted(result)


def main():
    work = sys.argv[1] if len(sys.argv) > 1 else tempfile.mkdtemp()
    lines = []
    for repo in REPOS:
        found = names(work, repo)
        print("%s: %d" % (repo, len(found)), file=sys.stderr)
        lines.append("#" + repo)
        lines.extend(found)
    with gzip.GzipFile(OUT, "wb", mtime=0) as f:
        f.write(("\n".join(lines) + "\n").encode())
    print("wrote %s (%d bytes)" % (OUT, os.path.getsize(OUT)), file=sys.stderr)


if __name__ == "__main__":
    main()
