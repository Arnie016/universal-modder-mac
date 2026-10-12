# Tactics Possession

A playable single-player browser encounter: command eight guardians from above, then control one guardian while the same battle continues. Defend the sanctuary and defeat twelve raiders. Prototype; no human quality acceptance yet.

## Run and contribute

Requires Node 24 for tests/MCP. Browser play needs WebGL, native ES modules and import maps. The browser game itself has no npm dependency or remote game-data requirement.

1. Download `source.zip` beside this file and extract it.
2. Run `python3 -m http.server 8849 --bind 127.0.0.1` in the extracted directory.
3. Visit http://127.0.0.1:8849/ and click Enter the valley.
4. To verify the bridge: `npm ci`, then `npm test`.
5. For local MCP: `node --import ./node-register.mjs mcp-server.mjs`. The server has its own isolated headless world; it does not control an existing browser tab or user game.

Controls: click ground/enemies to command; Tab switches viewpoints; WASD moves; drag to look; Space strikes; Shift sprints; Q rallies; G holds; R restarts. Choose a numbered guardian to select them; choose them again to select the squad. Saves stay in this browser. This is single player, not online matchmaking.

## Optional live browser agent

On browsers exposing `document.modelContext.registerTool` (WebMCP), click **Agent off**, then **Enable for 5 minutes** after entering the valley. Three tools observe this exact open battle, order owned guardians, and switch viewpoint. They use the same bridge as player controls. Access is off by default, expires after five minutes, and can be revoked in the game. Restart, loading a save and leaving the page end it. Commands require the current battle epoch; they cannot reset, save, advance time or access accounts/files.

This is browser-dependent experimental support, not universal agent compatibility. The local stdio MCP server above still has its own world. The permission lease limits trusted game tools; it does not sandbox arbitrary contributed code. Reproduce the live check by inspecting guardian positions, issuing a move, inspecting again after normal play, and revoking access.

Primary API: https://developer.chrome.com/docs/ai/webmcp/imperative-api

## Actual reuse and ownership

The two files in `vendor/0ad/` are unchanged upstream `EntityCollection` and utilities from Wildfire Games 0 A.D., pinned to `78ca04310ebe9c02d8db22eb6bbd79ee0f3dfac9`. Source: https://gitea.wildfiregames.com/0ad/0ad/src/commit/78ca04310ebe9c02d8db22eb6bbd79ee0f3dfac9/binaries/data/mods/public/simulation/ai/common-api/entitycollection.js

`adapter.js` supplies the synchronous `Engine.PostCommand` seam. Real upstream methods produce walk, attack-walk, attack and stance commands. The original `simulation.js` interprets them and owns movement, path routing, collision, health and persistence. This does not run the 0 A.D. engine, native pathfinder or Petra AI. It is not a whole-game port or console emulator. The binding is trusted source integration, not a security sandbox.

`main.js` is an original Three.js renderer with original procedural scenery and Web Audio cues. The views share one world; switching cameras does not clone the battle. No Minecraft code/assets or proprietary commercial game files are included. Minecraft host integration remains future work.

## Licence

Original code and donor code: GPL-2.0-or-later. Copyright 2026 Arnav and contributors for original files; Wildfire Games and its contributors for donor files. Preserve notices and provide the corresponding source when distributing. `vendor/0ad/LICENSE.md` and `license_gpl-2.0.txt` include upstream terms. Three.js 0.186.1 is MIT, with its notice in `vendor/three/LICENSE`. npm MCP SDK is MIT; its dependencies retain their own notices. Fonts are loaded from Google Fonts under their own font terms; system fallbacks remain available.

## Adapter recipe

`recipe.json` records the source pin and capability boundary. An envelope contains `v`, `epoch`, `seq`, `owner`, and an upstream `command`. Reject duplicates, stale epochs, invalid positions and entity ownership violations before applying a command. Only the host applies damage. The native Mac adapter below implements these semantics and has local runtime/window proof. Minecraft remains unimplemented; accepting JSON alone does not prove a game hook.

## A useful contribution

Fork/extract the source, choose one bounded change, and preserve the donor files and independent acceptance tests. Examples: a different donor command, a supported host adapter, a tactical rule, or one accessible control improvement. Document which source is reused versus original, its licence, host authority and reproduction steps. Run `npm test` and a real browser playthrough. Include a sanitized test receipt and before/after screenshot; no keys, private chats, saves or proprietary assets. A maintainer should run acceptance tests independently rather than trusting changed expectations.

No automatic token collection, paid worker service or private telemetry exists. An independent contributor reproducing and extending the game is still untested.

`bridge.js` is the shared host boundary used by browser controls and MCP. `matchHost` checks declared commands, coordinate units, tick rate and state authority. A matching declaration still requires an actual gameplay verifier on that host. It cannot discover or grant game hooks.

## Native Mac prototype

`native/` builds an original AppKit/SceneKit window using Metal and JavaScriptCore. It executes the same pinned donor and reviewed simulation as the browser; the native scene owns no second collision or health system. Browser and native sessions are separate battles, not connected multiplayer. This is not a port of the full 0 A.D. engine or an adapter into a retail game.

Requires macOS, Apple command-line development tools and Node 24+. After `npm ci --ignore-scripts`, run `npm run native:verify`. This compiles the app and runs 18 checks inside Apple JavaScriptCore: actual donor commands, movement, ownership, view identity, wall obstruction, melee damage/cooldown/line of sight, full combat, invalid imports and JSON persistence. The receipt prints the local app path. Double-click that app to play; it begins paused. Begin/Pause, Rally, Possess, Save and Load are visible controls. WASD/Space/Shift/drag apply in first person. R restarts.

Build products go into a checkout-specific temporary directory because iCloud/File Provider metadata can prevent code signing. `REMIX_NATIVE_BUILD_DIR` optionally selects a local output folder. The binary targets macOS 14 on the build machine's architecture; only the arm64 macOS 26.6.2 local window has been exercised. Intel and older macOS gameplay remain untested. Signing is local ad-hoc only: no Developer ID, notarization or public installable native release.

The visible Mac window was exercised with native input: rally moved Guardian 1 from z=22 to z=5.962; possession preserved all units and its 138 HP; holding W moved it to z=1.462; restart/load restored the exact saved world. Its recorded SceneKit backend was Metal with rendered frames. This is agent-operated local proof, not human acceptance. Native scenery is deliberately simpler than the browser and native audio is not implemented. Native MCP access is not exposed. Default saves live in the prototype's own Application Support folder; `--state-dir` can direct lab saves to an explicit isolated folder. It does not change Minecraft or browser saves.

## Formation workshop

Choose Shield line, Spear wedge or Gate column, import your own validated eight-position layout, then issue a movement order. The real pinned donor command passes to the host, which assigns the selected positions and rejects any blocked destination atomically. A shareable play link carries only layout data into a new battle. Downloaded layouts stay local; no accounts, tokens or arbitrary code are collected. See `formations/README.md` for the supported schema and a contribution verifier. Restart and save/load preserve the chosen formation. The same interpreter is covered by native JavaScriptCore checks, but a visible native picker is not yet implemented. This is one data extension, not a general game plugin sandbox.
