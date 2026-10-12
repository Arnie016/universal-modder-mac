# Playbook: 0ad-hero-squad

Play one Spartan hero in first person inside the original 0 A.D. engine, with a Minecraft-style HUD, stone
blocks that really block movement, and a squad you command from a hotbar. This playbook runs and checks it.

## What you need

- 0 A.D. **0.0.26** or **0.28.0**, installed the normal way (Ubuntu 24.04: `apt install 0ad` gives 0.0.26). macOS
  has not been tested yet.
- Python 3.10+ and this repository. Run as a normal user: 0 A.D. refuses to run as root.
- Nothing is bought, downloaded or generated, and the lab never touches your saves.

## 1. Check the recipe

```sh
um lab validate recipes/0ad-hero-squad
```

This checks the format, the sha256 of all 38 files, the licences, the commands against `commands.wit` and every
fixture line. If an artifact hash fails, a file changed after the evidence was recorded. Run the tests again,
then `um lab hash recipes/0ad-hero-squad --write`.

## 2. Run the acceptance suite in the real game (headless, about 20 s)

```sh
um lab accept recipes/0ad-hero-squad
```

The lab looks for the game where it is usually installed: the Debian/Ubuntu package
(`/usr/share/games/0ad`), or `0 A.D..app` in `/Applications` or `~/Applications` on macOS (untested). For any
other install, say where it is:

```sh
um lab accept recipes/0ad-hero-squad --exe <path to pyrogenesis> --public <the game's mods/public folder>
```

Expected: `14/14 checks`. The run starts the game three times (protocol checks, the fixture, and the same
fixture again to compare) and writes `transcript.jsonl`, `checks.json`, `fixtures.json`, `game.log` and
`receipt.json` to `~/.universal-modder/lab/runs/<date>-0ad-hero-squad-<id>/`.

Where the game writes:

- Linux: the mod, config, logs and cache go to `~/.universal-modder/lab/0ad/xdg/`, not to your normal 0 A.D.
  folders.
- macOS: 0 A.D. has no switch for this, so the mod is copied to `~/Library/Application Support/0ad/mods/um_blocks`.
  It does not touch your saves.

## 3. Watch it or play it

- `examples/0ad-blocks/hero_lab.py` plays hero mode with real key and mouse input and takes screenshots.
  It needs a display (`xvfb-run` works). It drives the clock through the RL interface and is the source of the
  21/21 visual evidence.
- To play by hand, copy `examples/0ad-blocks/um_blocks` into your user mods folder, enable it in the Mods menu
  and start a game. Press **Play as hero** under the top bar. Controls:
  - W walks, S stops, A and D turn.
  - 1–9 pick a hotbar slot, left click uses it, right click breaks a block.
  - V switches first and third person, Tab opens the tactical map, Esc leaves hero mode.

  Hand play without the RL clock has not been tested yet.

## 4. Let an agent drive it (MCP)

```sh
um lab mcp            # add --exe and --public if the game is not in a usual place
```

To use it from an MCP client, register that command as a stdio server. For example, in a project's `.mcp.json`:

```json
{"mcpServers": {"umx-lab": {"command": "um", "args": ["lab", "mcp"]}}}
```

These tools take recipe ids and typed commands, never paths or shell commands:

- `lab_capabilities`, `lab_validate`
- `lab_start`, `lab_send`, `lab_step`, `lab_observe`
- `lab_replay`, `lab_export`, `lab_stop`

The game only advances on `lab_step`, so the agent is never inside the frame loop. A step is at most 50 turns
(10 s of game time). An experiment is at most 6000 turns and 900 s of wall-clock time.

## 5. Share what happened

```sh
um lab receipt ~/.universal-modder/lab/runs/<folder>
```

The receipt is safe to share. It carries the checks, the recipe and game versions, the platform, a random
machine id and the transcript's sha256. It does not carry paths, user names, keys or prompts. Once your human
agrees, add it to a PR under `recipes/0ad-hero-squad/receipts/`. `um lab metrics --from recipes` counts
completed remixes, independent reproductions and repeat usage from those receipts.

## When something fails

| Symptom | Cause | Fix |
| --- | --- | --- |
| `mod-missing` | The installed game is neither 0.0.26 nor 0.28.0. | The lab rewrites the mod's dependency to the installed version. If it still fails, the scripts need porting: read `game.log` for the first `JavaScript error`. |
| `start-failed` | The game exited while starting. | Read `game.log` in the run folder. A common cause is running as root. |
| `occupied` or `blocked` on place | A unit, a block or terrain is in that 4 m cell. | Pick another cell. The fixture uses a cell that is free on Arcadia. |
| `stale-session` | The command belongs to a game from before a restart. | Observe again and use the new session. |
| artifact hash error | A mod file changed. | Run the evidence again, then use `um lab hash --write`. |

## Proof limits

- Tested on Linux x86_64 on 2026-10-12: 0 A.D. 0.0.26 headless, visually (Xvfb) and over MCP, and 0.28.0 headless
  only. The 0.28.0 run used the Ubuntu archive's build on Ubuntu 24.04 under its own newer glibc, not a stock
  install. Not yet tested on macOS, by hand without the RL clock, or in multiplayer.
- Session and epoch rules assume one simulation.
- Headless runs check commands, state and replay, but not the picture. The HUD and the camera were checked from
  screenshots of the visual run.
