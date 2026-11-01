#!/usr/bin/env bash
# WiiLink WFC: builds WiiLink's own first-stage Gecko codes (one per supported Wii game and
# region) from their source and packs them into app/src/main/assets/wiilink_wfc.txt, which
# the app turns on as a cheat when a game's 인터넷 서버 is "WiiLink WFC".
#
# The code checks the second stage it downloads from WiiLink's server against an MD5 that is
# built in. That second stage is fixed on the server (stage1v1), so our build of it must hash
# the same as theirs; the build stops if it does not, rather than ship codes that cannot
# connect. Needs docker (devkitPPC image).
#
# Online communications credit to WiiLink WFC - https://wfc.wiilink.ca
# (wfc-patcher-wii: custom BSD-style license, attribution required; see its LICENSE.)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build/wiilink}"
WIILINK_REPO="https://github.com/WiiLink24/wfc-patcher-wii"
WIILINK_COMMIT="0d9d7a697d7fb299ecc629f2c5da071d116369f2"   # payload-0.3.1
# The stage 1 hash WiiLink's own codes carry (patch/wwfcPatch.s).
STAGE1_MD5="6b66872c748546d7632a3d3626ec3cad"
OUT="$REPO_ROOT/app/src/main/assets/wiilink_wfc.txt"

mkdir -p "$WORK"
SRC="$WORK/src"
if [ ! -d "$SRC/.git" ]; then
    git init -q "$SRC"
    git -C "$SRC" remote add origin "$WIILINK_REPO"
fi
if [ "$(git -C "$SRC" rev-parse HEAD 2>/dev/null || true)" != "$WIILINK_COMMIT" ]; then
    git -C "$SRC" fetch -q --depth 1 origin "$WIILINK_COMMIT"
    git -C "$SRC" checkout -q --force FETCH_HEAD
fi
rm -rf "$SRC/stage1/build" "$SRC/patch/build"

echo "== building stage 1 and the codes (devkitPPC)"
docker run --rm -v "$SRC:/src" -w /src devkitpro/devkitppc bash -c '
    set -e
    command -v python3 > /dev/null || { apt-get update -qq && apt-get install -y -qq python3 > /dev/null; }
    cd /src/stage1 && python3 make-stage1.py
    cd /src/patch && python3 make-patch.py -j
'

HASH="$(python3 -c 'import hashlib,sys; print(hashlib.md5(b"\x01\x2C" + open(sys.argv[1], "rb").read()).hexdigest())' "$SRC/stage1/build/stage1.bin")"
if [ "$HASH" != "$STAGE1_MD5" ]; then
    echo "stage 1 hash $HASH is not WiiLink's $STAGE1_MD5: the codes would not connect"
    exit 1
fi
echo "   stage 1 hash matches WiiLink's ($HASH)"

python3 - "$SRC" "$WIILINK_COMMIT" "$OUT" <<'PY'
import csv, os, sys
src, commit, out = sys.argv[1:4]
lines = ["# WiiLink WFC first-stage Gecko codes, built from https://github.com/WiiLink24/wfc-patcher-wii @ " + commit,
         "# Online communications credit to WiiLink WFC - https://wfc.wiilink.ca"]
count = 0
with open(os.path.join(src, "gamedefs.csv")) as f:
    for game in csv.DictReader(f):
        path = os.path.join(src, "patch", "build", game["Title"] + ".txt")
        if not os.path.isfile(path):
            continue
        lines.append("[" + game["Title"] + "]")
        lines += [l.strip() for l in open(path) if l.strip()]
        count += 1
os.makedirs(os.path.dirname(out), exist_ok=True)
open(out, "w").write("\n".join(lines) + "\n")
print("   -> %s (%d games)" % (out, count))
PY
