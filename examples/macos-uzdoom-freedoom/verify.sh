#!/bin/sh
# Headless-ish verification: launch UZDoom with the mod in an isolated config, run test.cfg, read the log and
# the game's own screenshots (the engine writes them from its framebuffer, so no Screen Recording permission).
#   IWAD=~/Desktop/mac-games/freedoom-0.13.0/freedoom2.wad UZDOOM=~/Applications/UZDoom.app ./verify.sh
set -e
cd "$(dirname "$0")"
IWAD=${IWAD:-$HOME/Desktop/mac-games/freedoom-0.13.0/freedoom2.wad}
APP=${UZDOOM:-$HOME/Applications/UZDoom.app}
LAB=${LAB:-$PWD/lab}
mkdir -p "$LAB/shots" "$LAB/saves"
rm -f "$LAB"/shots/* "$LAB/log.txt"
./build.sh >/dev/null
"$APP/Contents/MacOS/uzdoom" -iwad "$IWAD" -file "$PWD/MacArsenal.pk3" -config "$LAB/uzdoom.ini" -savedir "$LAB/saves" \
  -skill 3 -warp 1 -nomonsters +vid_fullscreen 0 +vid_vsync 0 +vid_maxfps 0 ${UZ_EXTRA:-+vid_preferbackend 1} +win_w 1280 +win_h 800 +screenshot_dir "$LAB/shots" \
  +exec "$PWD/test.cfg" >"$LAB/stdout.txt" 2>&1 || true
grep -E "MacArsenal|rror|arning|MAP0" "$LAB/stdout.txt" || true
ls "$LAB/shots"
