<p align="center">
  <img src="docs/media/mac-banner.png" alt="universal-modder for macOS" width="100%">
</p>

<p align="center">
  <b>Point any AI coding agent at any game on your Mac. It finds it, reads it, mods it, drives it, records it, and proves it works.</b><br>
  The macOS port of <a href="https://github.com/rehan-remade/universal-modder">universal-modder</a>: the same skills, knowledge base and fal asset pipeline,
  plus a native <code>um mac</code> toolkit built on ScreenCaptureKit, CGEvent and code-signing checks.
</p>

<p align="center">
  <img alt="macOS 15+" src="https://img.shields.io/badge/macOS-15%2B%20·%20Tahoe%2026-B6FF3B?labelColor=0A0D12&logo=apple&logoColor=white">
  <img alt="Apple Silicon + Intel" src="https://img.shields.io/badge/Apple%20Silicon-native%20·%20Intel%20via%20Rosetta-B6FF3B?labelColor=0A0D12">
  <a href="https://github.com/Arnie016/universal-modder-mac/actions"><img alt="tests" src="https://img.shields.io/github/actions/workflow/status/Arnie016/universal-modder-mac/test.yml?label=tests&labelColor=0A0D12&color=B6FF3B"></a>
  <a href="https://github.com/Arnie016/universal-modder-mac/releases"><img alt="release" src="https://img.shields.io/github/v/release/Arnie016/universal-modder-mac?labelColor=0A0D12&color=B6FF3B"></a>
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/license-MIT-B6FF3B?labelColor=0A0D12"></a>
  <img alt="agents" src="https://img.shields.io/badge/agents-Claude%20Code%20·%20Codex%20·%20Cursor%20·%20Gemini-B6FF3B?labelColor=0A0D12">
</p>

<table>
<tr>
<td width="50%"><img src="docs/media/mac-homing-demo.gif" alt="Homing rockets curving into off-axis imps in Freedoom on UZDoom"></td>
<td width="50%"><img src="docs/media/mac-minecraft-seeker.gif" alt="Seeker arrows curving into off-axis husks in Minecraft 26.3"></td>
</tr>
<tr>
<td><sub><b>Doom (UZDoom + Freedoom):</b> <a href="examples/macos-uzdoom-freedoom">Mac Arsenal</a>. Seeker rockets curve into imps placed 20–55° off-aim. The pickup sprite came from fal.</sub></td>
<td><sub><b>Minecraft 26.3 (Fabric):</b> <a href="examples/minecraft-seeker-fabric">Seeker Wand</a>. A client game test fires straight ahead, and homing arrows take out three off-axis husks (3/3). The texture came from fal.</sub></td>
</tr>
</table>
<p align="center"><sub>Both clips are real captures with <code>um mac record</code> on an M5 Max. Nothing is mocked.</sub></p>

## Quickstart (Mac)
```bash
uv tool install git+https://github.com/Arnie016/universal-modder-mac   # puts `um` on PATH (needs Python 3.10+, uv)
um mac doctor          # permissions + tools; compiles the native helper once (Xcode command line tools)
um scan --list         # your .app games, Steam for Mac, App Store titles (flagged), Wine-wrapped Windows games
um scan "Some Game"    # engine, Mach-O archs, code signing, ranked Mac routes, save folders
```
Then open your agent in a clone and say what you want, for example *"Mod Freedoom: add a homing rocket launcher with a
fal sprite and prove it works in game."* The agent follows `skills/mod-any-game` and uses `um mac` to see and test.

**Permissions.** The app that runs your agent (Terminal, iTerm, VS Code, Cursor or the agent's desktop app) needs:
- **Screen Recording** (System Settings → Privacy & Security) for `shot`, `record` and window titles;
- **Accessibility** for `drive`.

Only you can grant these. `um mac doctor --request` shows the prompts.

## What the Mac port adds
| | |
|---|---|
| `um mac doctor` | permissions, Xcode tools, ffmpeg, Blender, Rosetta, CrossOver, fal key: reports only, changes nothing |
| `um mac ps` / `launch` / `kill` | windows by app (also on other Spaces); `.app`, executables, `--steam`, `--bundle`; kill by exact PID only, never its own process tree |
| `um mac shot` | `screencapture -l <window>` with blank-frame detection that names the missing permission |
| `um mac record` | ScreenCaptureKit: one window, only the game's audio, one `.mov` (H.264 + AAC), follows the window across Spaces |
| `um mac drive` | CGEvent input: `focus`, `click X Y`, `key`, `hold`, `rel DX DY` (mouselook), `type`, `drag`, `wheel`. WinDrive-compatible, Windows VK hex accepted, input only while the game is frontmost |
| `um mac sign-info` / `inject` | hardened runtime, library validation, dyld entitlements, sandbox, App Store receipt → whether `DYLD_INSERT_LIBRARIES` (BepInEx/Doorstop, your dylib) can work; `inject` refuses when signing blocks it |
| `um scan` on macOS | `.app` discovery, Mac layouts for Unity/Unreal/Godot/GameMaker/Electron/Ren'Py/LÖVE, Mach-O archs, Wine wrappers, Mac routes and save folders |

The native half is **one Swift file** (`um/swift/umhelper.swift`), compiled on first use and cached by hash, so there is
no pyobjc and no build step. Playbook: [`skills/mod-any-game/references/engines/macos.md`](skills/mod-any-game/references/engines/macos.md).

### Status, honestly (macOS 26.6, M5 Max)
| Verified on a real Mac | How |
|---|---|
| `scan`, `--list`, Mac routes, signing verdicts | UZDoom, Minecraft Launcher, an App Store game, Blender, plus fixture tests |
| `ps`, `launch`, `kill`, `doctor`, `helper` | live UZDoom window, on screen and on another Space |
| `shot` | a real window capture, and the clear error when permission is off |
| `record` | the demo above: a 2418×1634 H.264 + AAC `.mov` |
| Mac Arsenal mod (Doom) | 3/3 and 2/3 off-axis homing kills, from the mod's log and the engine's screenshots |
| Seeker Wand (Minecraft 26.3) | client game test: 3/3 kills on four consecutive runs, with screenshots and a recording |
| `drive` input | **not yet**: Accessibility was off. `drive rect` and `focus` work; clicks and keys are untested |
| `inject` | **not yet** run against a real dylib |

## Install

Pick your agent. Each gets the same skills (Agent Skills format), the fal MCP server, and the `um` CLI.

| Agent | Install |
|---|---|
| **Claude Code** | `/plugin marketplace add Arnie016/universal-modder-mac`, then `/plugin install universal-modder@universal-modder` |
| **Codex** | `codex plugin marketplace add Arnie016/universal-modder-mac`, then `codex plugin add universal-modder@universal-modder` |
| **Gemini CLI** | `gemini extensions install https://github.com/Arnie016/universal-modder-mac` |
| **VS Code / Copilot** | Enable `chat.plugins.enabled`, run **Chat: Install Plugin From Source**, and enter this repo's URL |
| **Cursor** | Cursor Marketplace, or clone (Cursor reads `AGENTS.md` and `.cursor/mcp.json`) |
| **Skills only** (any agent) | `npx skills add https://github.com/Arnie016/universal-modder-mac` |
| **Anything else** | `git clone https://github.com/Arnie016/universal-modder-mac` and start your agent inside it |

Inside a clone, each agent finds the skills where it looks for them: `.agents/skills` (Codex and friends),
`.claude/skills`, `.gemini/skills` and `.github/skills` all link to `skills/`. Instructions are in
`AGENTS.md`, which `CLAUDE.md` and `GEMINI.md` point to. MCP config is in `.mcp.json`, `.codex/config.toml`,
`.cursor/mcp.json` and `.vscode/mcp.json`.

**The `um` CLI.** Plugin installs and clones put it on PATH. Anywhere else:
```bash
uv tool install git+https://github.com/Arnie016/universal-modder-mac     # or: pipx install git+...
```
**For assets,** get a [fal API key](https://fal.ai/dashboard/keys). It powers both the fal MCP server and
`um fal`:
```bash
export FAL_KEY=...
```
You also need Python 3.10+ and ffmpeg. `uv` is recommended. Blender is needed for 3D → sprite renders.
Windows games are driven natively or from WSL. macOS games are driven with `um mac` (see below).

## Try it
> Mod Terraria: add a homing missile launcher and a tactical nuke that craters the world. Make the sprites with fal.

> Make a new civilization for Age of Empires II with a unique unit rendered from 3D.

> Put real Minecraft inside GTA V story mode. Minecraft's camera should follow GTA's, and its TNT should blow up GTA cars.

> What engine is `C:\Games\Foo`, and has anyone modded it before?

The agent starts with the **mod-any-game** skill and runs the same loop every time:
1. search the knowledge base;
2. recon, then pick a route;
3. set up a safe lab (saves backed up);
4. read the actual code;
5. build one working slice;
6. generate assets;
7. verify in the real game;
8. record;
9. package;
10. write a field note for the next agent.

## A knowledge base that AIs write for AIs
[`knowledge/`](knowledge/) holds **field notes**: how specific games were actually modded, decompiled and
reverse-engineered. Each note gives:
- the exact versions that worked;
- the route, and why;
- what the engine really does;
- how it was verified;
- the gotchas (symptom → cause → fix).

**Every agent that finishes a mod can open a pull request with its note**, so the next agent starts where it
left off instead of rediscovering the same traps.

```bash
um kb search "grand theft auto"                 # before you start: prior art (works outside the repo too)
um kb new --game "Hades II" --title "A new boon god" --from-scan hades --agent "Codex (gpt-6)"
um kb check knowledge/games/hades-ii/a-new-boon-god.md
um kb pr knowledge/games/hades-ii/a-new-boon-god.md --yes    # after your human says OK: branch, push, PR
```
Browse [`knowledge/INDEX.md`](knowledge/INDEX.md). Every game is welcome. Contribution rules, for humans and
AIs, are in [`CONTRIBUTING.md`](CONTRIBUTING.md): no game files, no decompiled dumps, no cheating other
players, and an honest status and verification.

## What's inside

**Skills** (`skills/`, Agent Skills format)

| Skill | What it does |
|---|---|
| `mod-any-game` | The whole loop, hard safety rules, and **12 engine playbooks**: Unity, Unreal, .NET/XNA (Terraria, Stardew, Celeste), Godot, Source 1/2, Bethesda, Minecraft, AoE2/Genie, RE Engine/FromSoft/GTA/Cyberpunk/BG3, native C++, indie engines (GameMaker, RPG Maker, Ren'Py, Paradox, Doom, HTML5, LÖVE, Java), retro decomps |
| `game-recon` | Prior field notes, engine and version, managed or native, anti-cheat, loaders, save folders, community route → `MODDING_PLAN.md` |
| `reverse-engineering` | ILSpy / Cpp2IL / Vineflower / Ghidra and IDA over MCP / Cheat Engine / Frida / RenderDoc; reverse-engineer a file format and prove it with a round trip |
| `fal-assets` | Sprites with real transparency, consistent variants, pixel art, seamless textures, PBR maps, image-to-3D, auto-rigging, SFX, music, voice, cutscene video |
| `asset-pipeline` | Art → engine-exact frames: cutout, nearest-neighbour fit, palettes, sheets, team-colour masks, 3D → 8/16-heading sprites |
| `game-automation` | Launch, screenshot (GPU-safe), click/type safely, windowed mode, crash-reporter cleanup, in-game agent bridges |
| `showcase-video` | Record the window with only the game's audio, pick moments, cut a styled video from an EDL |
| `mashup-mods` | Game inside a game: content ports, passthrough mods (worked example: Minecraft × GTA V), decomps as libraries, reimplementations |
| `publish-mod` | Lint, package per platform, credits, the post |
| `share-field-notes` | Search the knowledge base, write your own note, open the PR |

**The `um` CLI** (Python). Every command has `--help` with examples.

| | |
|---|---|
| `um scan` | Find Steam/Epic/Xbox installs; fingerprint engine and version, .NET vs native, anti-cheat, installed loaders, save folders, ranked routes |
| `um fal` | `sprite`, `image`, `edit`, `rmbg`, `pixelate`, `upscale`, `texture`, `pbr`, `model3d`, `rig`, `sfx`, `music`, `voice`, `video`, `run`, `search`, `schema`, `price`. Plain REST, with a manifest of every generation |
| `um sprite` | `cutout`, `fit`, `pixelate`, `palette`, `sheet`, `slice`, `frames`, `team-mask`, `seamless`, `preview` |
| `um render3d` | GLB → sprite frames from the game's camera (`aoe2`, `iso8`, `trueiso`, `topdown`, `side`, `turntable`) with Blender |
| `um win` | `shot`, `record` (gfxcapture + process-loopback audio), `drive` (input that only reaches the game), `ps`, `kill`, `launch`, `reg` |
| `um mac` | `doctor`, `ps`, `launch`, `shot`, `record` (ScreenCaptureKit, game-only audio), `drive` (CGEvent), `kill`, `sign-info`, `inject` |
| `um video` | `contact` sheets, `compile` (EDL → titled, beat-cut video with music), `mux`, `beats`, `first-frame` |
| `um backup` | Snapshot, diff and restore save folders |
| `um publish check` | Blocks shipping game files, decompiled code and leaked keys |
| `um kb` | The knowledge base: `search`, `show`, `new`, `check`, `index`, `sync`, `pr` |

Two no-build Windows tools ship inside the package (`um/ps1/`): WinDrive input and ProcLoopback game-only
audio, both PowerShell with embedded C#.

<p align="center"><img src="docs/media/pipeline.png" alt="3D route: fal concept to 3D to 16 AoE2 headings. 2D route: fal art to cutout to a 64x26 Terraria sprite in game." width="100%"></p>

## Built with it
- **[examples/minecraft-seeker-fabric](examples/minecraft-seeker-fabric)**: *Seeker Wand* for Minecraft 26.3 (Fabric). It
  fires homing arrows, with a fal texture. Its oracle is a Fabric **client game test**: the real client boots on macOS with
  no Microsoft account, builds a world, and asserts 3/3 off-axis kills.
- **[examples/macos-uzdoom-freedoom](examples/macos-uzdoom-freedoom)**: *Mac Arsenal*, the first mod built and verified
  end to end on macOS. It adds a seeker rocket launcher for Freedoom on UZDoom 5, with a fal pickup sprite, a test fixture
  inside the mod, and verification through the engine's own screenshots (it works even on a locked Mac).
- **[examples/terraria-tmodloader](examples/terraria-tmodloader)**: *Fal Arsenal* for tModLoader.
  - Weapons: a homing missile launcher, a tactical nuke (crater + mushroom cloud), a chain-lightning rifle, a
    black-hole gun and an orbital strike.
  - Three new enemies and a two-phase Drone Mothership boss.
  - Every sprite came from fal.
- **[examples/aoe2-de-civ](examples/aoe2-de-civ)**: *San Franciscans* for Age of Empires II DE.
  - A new civilization with a Robotaxi unique unit and Delivery Drones, rendered from fal image-to-3D models
    at AoE2's camera angle.
  - A Transamerica Pyramid wonder.
  - A reverse-engineered `.sld` sprite writer.
- **[examples/minecraft-gta5-passthrough](examples/minecraft-gta5-passthrough)**: real Minecraft inside
  GTA V story mode.
  - A Fabric mod and a ScriptHookV + ReShade add-on exchange camera, ground and events over a local
    WebSocket.
  - Minecraft's colour + depth are depth-composited into GTA's frame.
  - Minecraft TNT, arrows and fireworks become GTA explosions and bullets, and Minecraft mobs fight the
    police.

Each has a field note with every non-obvious lesson: [knowledge/INDEX.md](knowledge/INDEX.md).

## Rules it follows
- **Any game you own: single-player, multiplayer, or servers you host.** It refuses to inject into online
  games with anti-cheat, write cheats against other players, or bypass anti-cheat, DRM or ownership checks.
- **It never ships game files or decompiled code.** Mods ship as code, your own assets, patches or
  converters.
- **It backs up before touching saves**, and kills processes by PID only.
- **It asks before** driving your mouse and keyboard, installing loaders into game folders, or publishing,
  PRs included.

Full reasoning: [`skills/mod-any-game/references/safety.md`](skills/mod-any-game/references/safety.md).

## Credits
- Built from real agent sessions modding Terraria, Age of Empires II and GTA V × Minecraft.
- Assets: [fal](https://fal.ai) (GPT Image 2, Nano Banana 2, FLUX, Trellis 2, ElevenLabs...).
- **Upstream:** [rehan-remade/universal-modder](https://github.com/rehan-remade/universal-modder) by Rehan and contributors. This
  repo is its macOS port, and everything outside `um mac` and the Mac scanner comes from there.
- Standing on the shoulders of tModLoader, genieutils-py, AoE2ScenarioParser, ScriptHookV, ReShade, Fabric,
  BepInEx, Harmony, UE4SS, REFramework, SKSE, ILSpy, Ghidra and every modding community that documented its
  game.
- The engine playbooks also draw on the September 2026 wave of AI-built mods, and on how their creators
  explained them in public.

MIT licensed. Fonts: Space Grotesk and JetBrains Mono (SIL OFL).
# Playable remix creator kit

The local `um remix` commands inspect a source package, compare declared host capabilities, run reviewed acceptance tests and serve a browser game. Start with [Tactics Possession and the creator guide](docs/REMIX_CREATOR_KIT.md). It uses real 0 A.D. squad command code inside an original browser encounter. The example is GPL-2.0-or-later; the toolkit CLI remains MIT.

```sh
bin/um remix inspect examples/tactics-possession
bin/um remix serve examples/tactics-possession --trust-code
```

This is a browser prototype, not an automatic game port. Hashes and capability matches do not prove safety or gameplay. Native/Minecraft adapters and independent contributor reproduction remain untested.
