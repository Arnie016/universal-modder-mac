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

## Result

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
