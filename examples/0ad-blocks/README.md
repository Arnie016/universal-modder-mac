# 0 A.D. blocks: Minecraft-style building inside a real RTS

This is a small 0 A.D. mod (`um_blocks/`) and a headless test (`blocks_lab.py`). Together they show that
building works inside the real game's simulation, not in an overlay:

- **Collision.** A placed granite block is a real 0 A.D. entity with a static obstruction. Soldiers re-path around a wall of blocks.
- **Removal.** Breaking a block removes its collider and its visual together. Soldiers then walk through the gap.
- **Persistence.** Damage and placed blocks go through the game's own components. The surviving blocks are saved into a scenario map, and a freshly started game loads them with working colliders.

The block's look is a 4 m granite cube (`um_blocks/art/meshes/um/block_4m.dae`, generated for this mod) skinned
with the game's stock `props/wrld_block_granite.dds` texture, so the visual matches the 4 × 4 m collider. The stock
`block_granite` prop is only 0.8 × 0.5 m. Mechanically the block is a gaia stone quarry with 50 stone, so workers can
also mine it. The mining path has not been tested yet.

`shots.py` replays the test in the rendered game and saves real frames: the open lane, the placed wall, the detour,
the broken block and the walk through the gap. It needs an X display; on Linux, run it under `xvfb-run`. It uses
ImageMagick's `import` for capture, so it does not run on macOS yet. It installs a second, test-only mod
(`um_blocks_shots`) that adds one GUI notification type so the simulation can move the camera.

The installer writes the installed game's version (from `mods/public/mod.json`) into the mod's dependency, because
0 A.D. refuses mods declared for another version.

## Hero mode: play one soldier in first person and command the squad

`um_blocks` also adds a hero mode to the real game, and `hero_lab.py` plays it with real mouse and keyboard input
on an X display (xdotool) and saves screenshots.

- **Enter:** click **Play as hero** at the top of the screen. A hero spawns next to your soldiers, the nearest
  soldiers (up to 12, within 40 m) become your squad and follow you, and the camera switches to the engine's own
  first-person view (`Engine.CameraFollowFPS`).
- **HUD:** 10 hearts (the hero's health), 10 drumsticks (your food stockpile: the hero eats 1 food every 10 s and
  starves without it), an XP bar (1 per block placed, 2 per block broken), and a 9-slot hotbar. The art is original
  16 x 16 pixel art drawn by `make_hud_art.py`.
- **Keys:** W walk, S stop, A/D turn, 1–9 pick a slot, left click use, right click break, V first/third person,
  Tab tactical map (the normal RTS view and panels), Esc leave.
- **Hotbar:** granite block, pickaxe, sword (you and the squad attack what you look at, or the nearest enemy),
  banner (the squad marches where you look), horn (follow me), box formation, line formation, shield (hold
  ground), map.

How it is built:

| piece | where | reused or new |
|---|---|---|
| movement, pathfinding, formations, combat, health, resources, saving | 0 A.D.'s own components and commands (`walk`, `guard`, `formation`, `attack`, `stance`, `stop`) | reused, unmodified engine 0.0.26 |
| first-person camera | `Engine.CameraFollowFPS` (in the engine since before A23, unused by the stock GUI) | reused |
| `um-spawn`, `um-possess`, `um-face`, `um-move`, `um-place`, `um-break`, `um-squad` | `simulation/helpers/UmCommands.js`, `simulation/components/UmCrossover.js` | new |
| hotbar, hearts, hunger, XP, input | `gui/session/um_hud.js`, `gui/session/session_objects/um_hud.xml` | new |

Every action is one typed command (JSON) with an envelope: `src`, `seq`, `session`, `epoch`. The GUI sends it with
`Engine.PostNetworkCommand`; a script sends the same JSON through the RL interface. The `UmCrossover` system
component is the single authority for the hero, the squad and XP; it ignores a repeated `seq` (`duplicate`),
refuses commands from another game (`stale-session`) or from before the last load (`stale-epoch`), and is saved
with the game. In hero mode the camera keys move from W/A/S/D to the arrow keys through the `mod` config
namespace, which is never written to the player's `user.cfg`.

### Result

`hero_lab.py` on 2026-10-12, 0 A.D. 0.0.26, Linux, Xvfb with software rendering, real xdotool input: **21 of 21
checks passed** (`hero_report.json`). Among them: the button click spawns the hero and a 4-soldier squad; a click
places a block (−10 stone, +1 XP) and the pickaxe breaks it (+10 stone); W walks 22 m and D turns 1.2 rad; slot 6
puts all 4 soldiers in a box formation; with the horn they follow at a median 11.6 m; the sword takes an enemy
spearman from 100 to 76 HP; after Shift+F5 and Shift+F8 the saved block is back, the unsaved one is gone, the epoch
went from 0 to 1, and the hero and squad are intact; a script's `um-place` works through the same handler, its
repeated `seq` is ignored, and a command with the old epoch is refused.

The mechanic is also packaged as a recipe, [`recipes/0ad-hero-squad`](../../recipes/0ad-hero-squad/): `um lab accept
recipes/0ad-hero-squad` runs the same commands headless in the real engine (14/14 on 2026-10-12, including the
envelope rules, a restart and an identical replay), and `um lab mcp` gives them to an agent.

Fixtures, stated in the report: the enemy spearman is spawned by the test 14 m in front of the hero, and two
envelope checks come from the script. **Not shown:** a human playtest (feel, readability, camera height), the
macOS build, and multiplayer (the epoch counter assumes a single-player game).

## Block lab result

Tested on 2026-10-12 with 0 A.D. 0.0.26 (Ubuntu `0ad 0.0.26-6ubuntu0.24.04.1`), headless on Linux, on the
stock Arcadia scenario. Two separate game processes were used. The second one reported a sim clock of 0 ms
when it answered.

| step | what happened | check |
|---|---|---|
| baseline | a spearman walks 40 m in 4.6 s (path 39.9 m) | lane is open |
| wall | 9 blocks across the lane: 6.8 s, 57.5 m, crossing 20.4 m off-centre; 0 samples inside a block | `wall_forces_detour`, `never_inside_a_block` |
| break | the middle block's position, obstruction and visual all gone; walk back to 4.6 s through the gap (0.44 m off-centre) | `break_removes_visual_and_collider`, `walk_uses_the_gap` |
| damage | `Health.TakeDamage(7)` from outside: 25 → 18 HP. A commanded attack: −7.13 HP within 2 turns | `outside_hit_applies_exact_damage`, `commanded_attack_lowers_enemy_health` |
| reload | new process on the saved map: 8 of 8 blocks, 0.0 m position error, all with colliders. The gap still works, and a lane aimed at a kept block detours 8 m | `reload_restores_8_blocks`, `reload_keeps_the_gap`, `reload_kept_block_still_collides`, `reload_never_inside_a_block` |

All 10 checks passed. The report is JSON, and the script exits 0 only when every check passes.

**What this does not prove:** a human playing it, the macOS build,
0 A.D.'s own savegame format (the test reloads through a scenario map that the harness writes), and any link to
Minecraft.

**Pitfall found while building this:** an RL-interface game left running on the same port answered the
harness's requests, which made a first "reload" look like it passed while still talking to the old game. The harness now
refuses a busy port and rejects a game whose clock is not near zero.

## Run it

0 A.D. refuses to run as root. Do not install `um_blocks` into the game's own folder: `blocks_lab.py` copies
it into your user mod folder.

```sh
# Linux (apt install 0ad)
python3 blocks_lab.py --public /usr/share/games/0ad/mods/public
# macOS (untested; check the paths in your install)
python3 blocks_lab.py --exe "/Applications/0 A.D..app/Contents/MacOS/pyrogenesis" \
  --public "/Applications/0 A.D..app/Contents/Resources/data/mods/public" \
  --mods "$HOME/Library/Application Support/0ad/mods"
```

`blocks_lab.py` uses these documented engine features: `-autostart-nonvisual`, `-autostart=scenarios/<map>`,
`-rl-interface=127.0.0.1:<port>`, and the RL interface's `/step` and `/evaluate` routes (see
`source/tools/rlclient/python` in the 0 A.D. source).

## Licences and attribution

0 A.D. is © Wildfire Games. Its code is GPL-2.0-or-later and its art is CC BY-SA 3.0. This folder ships no
game files. The template only refers to a stock actor by path. The scenario map that the test writes, a copy of
Arcadia plus the blocks, stays in your local mod folder and is not committed.
