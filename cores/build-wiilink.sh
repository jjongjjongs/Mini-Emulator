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

# WiiLink built their stage 1 with some devkitPPC release; a different compiler gives a
# different binary. Try the devkitPPC images, newest first, until one makes the same stage 1.
stage1_hash() {
    python3 -c 'import hashlib,sys; print(hashlib.md5(b"\x01\x2C" + open(sys.argv[1], "rb").read()).hexdigest())' "$SRC/stage1/build/stage1.bin"
}
# The container runs as root: it clears its own build folder first and hands the files back
# to the runner's user at the end, so the next try (and the cache) can use them.
build_in() {
    docker run --rm -v "$SRC:/src" -w /src "devkitpro/devkitppc:$1" bash -c "
        rm -rf /src/$2/build
        command -v python3 > /dev/null || { apt-get update -qq && apt-get install -y -qq python3 > /dev/null; }
        cd /src/$2 && python3 $3
        status=\$?
        chown -R $(id -u):$(id -g) /src
        exit \$status
    "
}
TAGS="$(curl -sS -m 60 'https://hub.docker.com/v2/repositories/devkitpro/devkitppc/tags?page_size=100' \
    | python3 -c 'import json,sys; print(" ".join(t["name"] for t in sorted(json.load(sys.stdin)["results"], key=lambda t: t["last_updated"], reverse=True)))' || true)"
TAGS="latest ${TAGS:-}"
IMAGE=""
TRIES=0
for TAG in $TAGS; do
    TRIES=$((TRIES + 1))
    [ "$TRIES" -le 15 ] || break
    echo "== stage 1 with devkitpro/devkitppc:$TAG"
    if ! build_in "$TAG" stage1 make-stage1.py > "$WORK/stage1-$TAG.log" 2>&1; then
        echo "   build failed"
        docker rmi -f "devkitpro/devkitppc:$TAG" > /dev/null 2>&1 || true
        continue
    fi
    HASH="$(stage1_hash)"
    echo "   $HASH"
    if [ "$HASH" = "$STAGE1_MD5" ]; then
        IMAGE="$TAG"
        break
    fi
    docker rmi -f "devkitpro/devkitppc:$TAG" > /dev/null 2>&1 || true
done
if [ -z "$IMAGE" ]; then
    echo "no devkitPPC image makes WiiLink's stage 1 ($STAGE1_MD5): the codes would not connect"
    exit 1
fi
echo "== codes with devkitpro/devkitppc:$IMAGE"
build_in "$IMAGE" patch "make-patch.py -j"
HASH="$(stage1_hash)"
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
