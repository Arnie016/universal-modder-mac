#!/bin/sh
# End to end: a guest Mac app (Freedoom on UZDoom, looping its homing test so the picture moves) -> `um mac stream`
# -> Minecraft client game test that shows it on an in-world screen and asserts live, changing frames.
set -e
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
UM="$ROOT/bin/um"
DOOM="$ROOT/examples/macos-uzdoom-freedoom"
IWAD=${IWAD:-$HOME/Desktop/mac-games/freedoom-0.13.0/freedoom2.wad}
APP=${UZDOOM:-$HOME/Applications/UZDoom.app}
PORT=${PORT:-7788}
mkdir -p build
defaults write org.zdoom.UZDoom NSAppSleepDisabled -bool YES          # App Nap would freeze the guest in the background
defaults write org.zdoom.UZDoom ApplePersistenceIgnoreState -bool YES # after any crash macOS shows a modal "reopen windows?" prompt that hangs scripted launches
"$DOOM/build.sh" >/dev/null
"$APP/Contents/MacOS/uzdoom" -iwad "$IWAD" -file "$DOOM/MacArsenal.pk3" -config "$PWD/build/guest.ini" -savedir "$PWD/build" \
  -skill 3 -warp 1 +vid_fullscreen 0 +win_w 960 +win_h 600 +vid_preferbackend 1 +i_pauseinbackground 0 \
  +exec "$PWD/guest-loop.cfg" > build/guest.log 2>&1 &
GUEST=$!
sleep 4
"$UM" mac stream --pid $GUEST --port $PORT > build/stream.log 2>&1 &
STREAM=$!
cleanup() { "$UM" mac kill $STREAM >/dev/null 2>&1 || true; "$UM" mac kill $GUEST >/dev/null 2>&1 || true; }
trap cleanup EXIT
sleep 3
./gradlew runClientGameTest -PmacarcadePort=$PORT --console=plain > build/gametest.log 2>&1 || { grep -E "MacArcade|Error|Exception" build/gametest.log | head -20; exit 1; }
grep -E "MacArcade test" build/gametest.log
ls build/run/clientGameTest/screenshots
