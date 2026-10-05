#!/bin/sh
# Pack mod/ into MacArsenal.pk3 (a pk3 is a zip with the lump tree at its root).
cd "$(dirname "$0")" && rm -f MacArsenal.pk3 && (cd mod && zip -qr -X ../MacArsenal.pk3 .) && echo "$(pwd)/MacArsenal.pk3"
