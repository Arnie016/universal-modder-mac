---
kind: game
title: Homing rocket launcher on UZDoom, built and verified on macOS
game: Freedoom
games_also: [Doom II]
game_version: Freedoom 0.13.0 (freedoom2.wad) on UZDoom 5.0.3 (MacOS-UZDoom-Release-ARM64)
platform: macos
engine: idtech
route: data
tools: [UZDoom 5.0.3, ZScript, um mac, um fal, um sprite]
anti_cheat: none
status: working
agents:
- Claude Code (claude-opus-5-5)
humans: []
date: '2026-10-05'
links:
- https://github.com/freedoom/freedoom/releases/tag/v0.13.0
- https://github.com/UZDoom/UZDoom/releases/tag/5.0.3
- https://zdoom.org/wiki/ZScript
tags: [macos, zscript, homing, headless-verification, vulkan, fal]
---
# Homing rocket launcher on UZDoom, built and verified on macOS

> A ZScript PK3, "Mac Arsenal", adds a Seeker Launcher whose rockets home on monsters, with a fal-generated pickup
> sprite. It runs in the real game on Apple Silicon. A scripted test places imps 20–55° away from where the player
> aims and fires straight ahead, and the mod's own kill log plus the engine's screenshots show homing hits
> (3/3 in run 1, 2/3 in run 2). It was the end-to-end test of the macOS port (`um mac`) and was done with the Mac
> locked, with no Screen Recording permission.

## Setup
- **Machine:** macOS 26.6.2 on an M5 Max (arm64).
- **Source port:** UZDoom 5.0.3, `MacOS-UZDoom-Release-ARM64.dmg`, copied to `~/Applications`. It is signed (team 7V3STQ9AVN), hardened, and arm64-only.
- **IWAD:** Freedoom 0.13.0 (`freedoom2.wad`), with the release SHA-256 checked against `freedoom-0.13.0-CHECKSUM`.
- **No loader needed.** Mods are PK3 zips passed with `-file`.

## Route and why
This was a data route: a PK3 with `zscript.txt`, `mapinfo.txt`, `language.txt` and `sprites/`. The scanner flags the hardened signature
("dylib injection blocked"), but a source port has a full scripting VM, so native hooks would be pointless.
- **New classes inherit from the stock ones.** `SeekerLauncher : RocketLauncher` and `SeekerRocket : Rocket` keep every IWAD state and
  sprite. Only Fire/Spawn are overridden.
- **No game art ships.** The rocket sprites are recoloured with the built-in `Translation "Ice"`.

## How the game works (what we had to learn)
- **Homing:** `A_SeekerMissile(threshold, maxturn, SMF_LOOK | SMF_PRECISE, chance, distance)` on a `+SEEKERMISSILE` actor. `SMF_LOOK`
  makes the rocket find its own tracer when the shooter had no autoaim target. The look cone is narrow: an imp at 55°
  and 224 units was sometimes missed.
- **The event handler** is registered from MAPINFO `GameInfo { AddEventHandlers = "..." }`.
  - `PlayerSpawned` gives the weapon.
  - `WorldThingDied` sees `e.Inflictor`, which is the projectile, so `e.Inflictor is "SeekerRocket"` attributes kills exactly.
  - `NetworkProcess` handles `netevent <name>` from the console, so a test fixture can live inside the mod.
- **PNG sprites take Doom offsets from a `grAb` chunk:** two big-endian int32s. Pillow writes one with
  `PngInfo().add(b"grAb", struct.pack(">ii", w//2, h-2))`.

## Build steps
1. `examples/macos-uzdoom-freedoom/build.sh` zips `mod/` into `MacArsenal.pk3`.
2. `uzdoom -iwad freedoom2.wad -file MacArsenal.pk3`, or `open -a UZDoom --args ...`.
3. `verify.sh` runs the scripted test with an isolated `-config`/`-savedir` in `lab/`.

## Verification
- **Oracle 1, stdout:** `Console.Printf` lines go to the process's stdout on macOS. The test prints the placed imps and each
  `seeker kill #n DoomImp`.
- **Oracle 2, engine screenshots:** the `screenshot` console command writes PNGs from the framebuffer into `screenshot_dir`. They show
  the imps off-axis, the ice-blue explosion, the corpses, and the fal pickup in front of the player. This needs no Screen
  Recording permission.
- **Not verified:**
  - live input through `um mac drive` (Accessibility permission was off);
  - `um mac record` on this game;
  - multiplayer.

## Gotchas
1. **All `screenshot`s fired at the same second and came out black, and nothing from the mod ran.** **Cause:** in ZDoom,
   `wait N` only delays the rest of the same command string. With one command per line, `exec` runs everything at
   startup, before the level exists. **Fix:** put the whole timed test in one `alias "a; wait 70; b; ..."` and call it.
2. **The game froze right after "MAP01" loaded, with no ticks and no `wait` progress, until a timeout killed it.** **Cause:** the Mac
   was locked (frontmost app `loginwindow`), and the OpenGL backend blocks on buffer swap when the window can't present.
   `vid_vsync 0` did not help. **Fix:** `+vid_preferbackend 1` (Vulkan through MoltenVK) kept the game running and the
   screenshots correct.
3. **`+logfile <path>` printed "Could not start log".** **Cause:** unknown (early in startup). **Fix:** not needed. On macOS
   the console already goes to stdout, so redirect that.
4. **`um fal sprite` failed with HTTP 422 "Transparent background is not supported for this model".** **Cause:** fal's
   `openai/gpt-image-2` endpoint no longer accepts `background=transparent`. **Fix (in the toolkit):** render on flat
   white, then cut out with `fal-ai/birefnet/v2`.
5. **A copied repo's `.venv` broke `uv run` (`No module named 'um'`).** **Cause:** the editable install pointed at the old path.
   **Fix:** `rm -rf .venv && uv sync`.

## Assets
- **Pickup:** `um fal sprite "retro 1990s Doom-style pickup sprite of a sci-fi homing rocket launcher lying on its side,
  icy cyan glowing seeker sensor..." --quality medium`, then `um sprite fit --size 64x28 --anchor bottom --smooth --hard-alpha`,
  trimmed to 51×28 with a `grAb` chunk added.
- **Projectile and trail:** IWAD sprites with `Translation "Ice"`.

## Cost and time
About 40 minutes of agent time inside a larger porting session. One fal image plus one cutout.

## Open questions
- Widen the look cone (`A_SeekerMissile` distance/threshold) and test a boss.
- Give the weapon a fal-generated HUD sprite (`MISG` replacement).
- Record a showcase with `um mac record` once Screen Recording is granted.
