#!/usr/bin/env python3
"""Builds two small indexes the cheat sheet reads (no cheat contents, only where to fetch):

  assets/wiiu_cheats.tsv  Cemu's official graphic packs (CC0) under */Cheats/*: title IDs,
                          name, description, folder, files - fetched when a pack is turned on.
  assets/n3ds_cheats.tsv  the CTRPF-AR-CHEAT-CODES collection's Cheats/<game>/<title ID>.txt
                          files: title ID and path - fetched when the player opens the sheet.

Usage: ./cores/gen-cheat-indexes.py [work dir]   (needs git)
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets")
PACKS = ("https://github.com/cemu-project/cemu_graphic_packs", "efa14afb2f6b25080c19878bb6c2ea722d78655b")
CTRPF = ("https://github.com/iSharingan/CTRPF-AR-CHEAT-CODES", "a931ae0f4e9f0165d15b1b652e5d1511430d8196")


def clone(work, name, repo):
    path = os.path.join(work, name)
    if not os.path.isdir(path):
        subprocess.run(["git", "clone", "-q", "--filter=blob:none", "--no-checkout", repo[0], path], check=True)
    subprocess.run(["git", "-C", path, "fetch", "-q", "origin", repo[1]], check=True)
    return path


def git(path, *args):
    return subprocess.run(["git", "-c", "core.quotepath=off", "-C", path] + list(args), check=True, capture_output=True).stdout.decode("utf-8", "replace")


def main():
    work = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "build", "cheat-indexes")
    os.makedirs(work, exist_ok=True)
    gp = clone(work, "packs", PACKS)
    files = git(gp, "ls-tree", "-r", "--name-only", PACKS[1]).splitlines()
    dirs = sorted({f.rsplit("/", 1)[0] for f in files if "/Cheats/" in f and f.endswith("/rules.txt")})
    rows = []
    for d in dirs:
        rules = git(gp, "show", "%s:%s/rules.txt" % (PACKS[1], d))
        fields = {}
        for line in rules.splitlines():
            if line.strip().startswith("[") and fields:
                break
            if "=" in line:
                k, v = line.split("=", 1)
                fields.setdefault(k.strip().lower(), v.strip().strip('"'))
        ids = fields.get("titleids", "").replace(" ", "")
        if not ids:
            continue
        names = [f.rsplit("/", 1)[1] for f in files if f.rsplit("/", 1)[0] == d]
        desc = fields.get("description", "").replace("\t", " ").split("||")[0]
        rows.append("\t".join([ids.upper(), fields.get("name", d.rsplit("/", 1)[1]), desc, d, ",".join(names)]))
    with open(os.path.join(ASSETS, "wiiu_cheats.tsv"), "w") as out:
        out.write("#%s %s\n" % PACKS + "\n".join(rows) + "\n")
    print("wiiu packs:", len(rows))

    ar = clone(work, "ctrpf", CTRPF)
    paths = [p for p in git(ar, "ls-tree", "-r", "--name-only", CTRPF[1]).splitlines() if p.startswith("Cheats/") and p.lower().endswith(".txt")]
    seen = {}
    for p in paths:
        tid = p.rsplit("/", 1)[1][:-4].upper()
        if len(tid) == 16 and all(c in "0123456789ABCDEF" for c in tid):
            seen.setdefault(tid, p)
    with open(os.path.join(ASSETS, "n3ds_cheats.tsv"), "w") as out:
        out.write("#%s %s\n" % CTRPF + "\n".join("%s\t%s" % kv for kv in sorted(seen.items())) + "\n")
    print("3ds games:", len(seen))


if __name__ == "__main__":
    main()
