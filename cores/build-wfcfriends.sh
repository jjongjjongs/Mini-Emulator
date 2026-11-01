#!/usr/bin/env bash
# Wii 친구끼리: everything a host phone needs to run its own Wi-Fi Connection room, and every
# phone needs to join one.
#   - WiiLink's client patches (wfc-patcher-wii) built for our room domain "mini.wfc" with a
#     key of our own: the first-stage Gecko code per game (assets/wfc_room_codes.txt), and the
#     stage 1 / stage 2 payloads plus the signing key the room server hands out
#     (assets/wfc_room.zip, unpacked next to the server).
#   - WiiLink's server (wfc-server) with an in-memory database instead of PostgreSQL
#     (cores/wfcserver/memdb.go), built for arm64 as jniLibs/arm64-v8a/libwfcserver.so, an
#     executable the app starts on the host phone.
# Every phone in a room runs the same build, so the codes, payloads and key all match.
# Needs docker (devkitPPC) and Go.
#
# Online communications credit to WiiLink WFC - https://wfc.wiilink.ca
#   wfc-patcher-wii: custom BSD-style license (attribution); wfc-server: AGPL-3.0, so the
#   server as modified here (this script plus cores/wfcserver) is AGPL-3.0 as well.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HERE/build/wfcfriends}"
PATCHER_REPO="https://github.com/WiiLink24/wfc-patcher-wii"
PATCHER_COMMIT="0d9d7a697d7fb299ecc629f2c5da071d116369f2"
SERVER_REPO="https://github.com/WiiLink24/wfc-server"
SERVER_COMMIT="f0825bf89a28de560de99006802e065e17b9e038"
DOMAIN="mini.wfc"
ASSETS="$REPO_ROOT/app/src/main/assets"
JNI="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"

fetch() {
    local dir="$1" repo="$2" commit="$3"
    if [ ! -d "$dir/.git" ]; then
        git init -q "$dir"
        git -C "$dir" remote add origin "$repo"
    fi
    if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" != "$commit" ]; then
        git -C "$dir" fetch -q --depth 1 origin "$commit"
        git -C "$dir" checkout -q --force FETCH_HEAD
    fi
}

mkdir -p "$WORK" "$ASSETS" "$JNI"
PATCHER="$WORK/patcher"
SERVER="$WORK/server"
fetch "$PATCHER" "$PATCHER_REPO" "$PATCHER_COMMIT"
fetch "$SERVER" "$SERVER_REPO" "$SERVER_COMMIT"

echo "== client patches and payloads for $DOMAIN (devkitPPC)"
docker run --rm -v "$PATCHER:/src" -w /src devkitpro/devkitppc bash -c "
    set -e
    apt-get update -qq > /dev/null
    apt-get install -y -qq python3 python3-cryptography python-is-python3 > /dev/null
    git config --global --add safe.directory /src || true
    ./make.sh --clean --clean-keys > /dev/null || true
    ./make.sh --all -- -j\$(nproc) '-DWWFC_DOMAIN=\"$DOMAIN\"'
    status=\$?
    chown -R $(id -u):$(id -g) /src
    exit \$status
"
test -s "$PATCHER/dist/stage1.bin"
test -s "$PATCHER/dist/private-key.pem"
ls "$PATCHER/dist/binary" | head -3

python3 - "$PATCHER" "$PATCHER_COMMIT" "$ASSETS/wfc_room_codes.txt" "$DOMAIN" <<'PY'
import csv, os, sys
src, commit, out, domain = sys.argv[1:5]
lines = ["# Mini Emulator Wii 친구끼리: WiiLink WFC first-stage Gecko codes for the room domain " + domain,
         "# built from https://github.com/WiiLink24/wfc-patcher-wii @ " + commit,
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
open(out, "w").write("\n".join(lines) + "\n")
print("   -> %s (%d games)" % (out, count))
PY

echo "== room server (in-memory database, linux/arm64)"
rm -f "$SERVER"/database/*.go
cp "$HERE/wfcserver/memdb.go" "$SERVER/database/memdb.go"
for f in gamestats/setpd.go gamestats/getpd.go sake/storage.go; do
    sed -i 's/pgx\.ErrNoRows/database.ErrNoRows/g; /"github.com\/jackc\/pgx\/v4"/d' "$SERVER/$f"
    grep -q '"wwfc/database"' "$SERVER/$f" || sed -i '0,/^import (/s//import (\n\t"wwfc\/database"/' "$SERVER/$f"
done
(cd "$SERVER" && GOTOOLCHAIN=auto GOFLAGS=-mod=mod CGO_ENABLED=0 GOOS=linux GOARCH=arm64 \
    go build -trimpath -ldflags="-s -w" -o "$JNI/libwfcserver.so" .)
echo "   -> $JNI/libwfcserver.so ($(du -h "$JNI/libwfcserver.so" | cut -f1))"

echo "== room files"
ROOM="$WORK/room"
rm -rf "$ROOM"
mkdir -p "$ROOM/payload"
cp -r "$PATCHER/dist/." "$ROOM/payload/"
cp "$SERVER/game_list.tsv" "$ROOM/"
[ -f "$SERVER/motd.txt" ] && cp "$SERVER/motd.txt" "$ROOM/"
rm -f "$ASSETS/wfc_room.zip"
(cd "$ROOM" && find . -type f | LC_ALL=C sort | TZ=UTC zip -q -X -D "$ASSETS/wfc_room.zip" -@)
echo "   -> $ASSETS/wfc_room.zip ($(du -h "$ASSETS/wfc_room.zip" | cut -f1))"
