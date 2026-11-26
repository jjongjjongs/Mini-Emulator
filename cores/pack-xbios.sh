#!/usr/bin/env bash
# Packs XBIOS 1.0 (© 2026 GHOST, CC BY-NC-ND 4.0) into app/src/main/assets/msx_xbios.zip.
# The files in third_party/xbios-1.0 are the release's own, unchanged (the licence allows no
# changes): the blueMSX machine descriptions with their ROMs go to Machines/, the licence
# and the example disk to XBIOS/. The app unpacks the zip into the MSX system folder.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
SRC="$REPO/third_party/xbios-1.0"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/Machines" "$STAGE/XBIOS"
cp -r "$SRC/bluemsx/"* "$STAGE/Machines/"
cp "$SRC/LICENSE.txt" "$SRC/README.md" "$SRC/CHECKSUMS.txt" "$SRC/examples.dsk" "$STAGE/XBIOS/"
OUT="$REPO/app/src/main/assets/msx_xbios.zip"
rm -f "$OUT"
( cd "$STAGE" && find . -type f | LC_ALL=C sort | sed 's|^\./||' | TZ=UTC zip -q -X -D "$OUT" -@ )
echo "-> $OUT ($(du -h "$OUT" | cut -f1))"
