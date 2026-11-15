#!/usr/bin/env bash
# Fetches the General MIDI soundfont DOS mode ships (TimGM6mb by Tim Brechbill, GPL-2; the
# copy pretty-midi carries) into the app's assets, checked against its hash. DOSBox Pure plays
# DOS games' MIDI music through it.
#
# Usage: ./cores/fetch-soundfont.sh
set -euo pipefail

URL=https://raw.githubusercontent.com/craffel/pretty-midi/286326f165c19e694533263db38033c71c1c1a2b/pretty_midi/TimGM6mb.sf2
SHA256=82475b91a76de15cb28a104707d3247ba932e228bada3f47bba63c6b31aaf7a1

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../app/src/main/assets/dos/TimGM6mb.sf2"
mkdir -p "$(dirname "$OUT")"
if [ -f "$OUT" ] && echo "$SHA256  $OUT" | sha256sum -c --status -; then
    exit 0
fi
curl -fsSL --retry 4 -o "$OUT.tmp" "$URL"
echo "$SHA256  $OUT.tmp" | sha256sum -c -
mv "$OUT.tmp" "$OUT"
echo "   -> $OUT ($(du -h "$OUT" | cut -f1))"
