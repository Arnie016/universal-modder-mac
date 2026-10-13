# Remix creator kit — first playable package

Tactics Possession reuses the unchanged 0 A.D. `EntityCollection` command API in an original browser battle. Command the squad, then possess one of the same guardians. Movement, collision, health, waves and saves belong to the browser host. The full 0 A.D. engine is not running.

[Play the public prototype](https://pure-exploration.arnz.chatgpt.site/games/tactics-possession/).

## Run from this checkout

Python 3.10+ and the normal toolkit dependencies are needed for the CLI. Tests require Node 24+ and the example's locked npm dependencies. Serving the browser game needs only Python; it does not need an npm install. No account, API key or paid service is required.

```sh
bin/um remix inspect examples/tactics-possession
bin/um remix match examples/tactics-possession/recipe.json examples/tactics-possession/host.json
cd examples/tactics-possession
npm ci --ignore-scripts
cd ../..
bin/um remix test examples/tactics-possession --trust-code
bin/um remix serve examples/tactics-possession --trust-code
```

Open the printed localhost link. Click Enter the valley. Q rallies your squad, Tab switches views, WASD moves, Space strikes, Shift sprints, and R restarts. Sound starts off. Saved battles stay in the browser's storage.

The CLI does not install dependencies or open a browser for you. The server binds only to 127.0.0.1, expires after 15 minutes by default and stops with Ctrl+C. It serves only files declared by the package, with no directory listings. Choose another port if it is busy.

## What each result means

| Result | What it proves | What remains |
| --- | --- | --- |
| VERIFIED_AGAINST_MANIFEST | File bytes match the supplied manifest | The manifest is not a trusted signature; review provenance and code |
| declaredCompatible | Command names, units, ticks and field authority match | Run the adapter against an actual game |
| TEST_PROCESS_PASSED | The declared Node test process exited successfully | Inspect what tests cover; a contributor can edit their own tests |
| Public browser checkpoint | The existing deployed encounter was entered and controlled on a Mac | Player value and repeat use need human feedback |

`--trust-code` acknowledges execution of reviewed contributed code. It is not a sandbox. Node imports and tests have your local process permissions. Do not run an unfamiliar package merely because its hashes match. This CLI does not execute runner commands supplied by a recipe, but the fixed runner's import hook and test files are still executable source. No native game hooks, console compatibility or Minecraft connection are inferred.

## Make a contribution

1. Fork this toolkit or copy the example into an isolated branch.
2. Choose one change with a visible player outcome: a new command, rule, accessible control or real host adapter.
3. Preserve pinned donor files and licence notices. Do not import retail assets. Label original code and source reuse separately.
4. Record the host's units, command set and sole owner of movement/collision/damage/persistence in `host.json`. Update the recipe only when requirements really change.
5. Keep the original acceptance checks. Add evidence for the new behaviour; do not rename a screenshot or declaration as gameplay proof.
6. Regenerate the source manifest after reviewing the change. Use `tools/create-remix-manifest.py <folder> --source-license <SPDX-id>`; it hashes the source but does not certify its licence or safety.
7. Run inspect, match and test, then play it in a real browser. Submit a small diff with exact reproduction steps and a sanitized screenshot/test receipt. No private saves, credentials, account keys or chat history.

An independent contributor reproducing and extending this package remains untested. Human playtest quality is NOT_TESTED. Native and Minecraft adapters are NOT_IMPLEMENTED. The stdio MCP controls an isolated test world. Optional WebMCP tools now control the player's actual open battle on supported browsers; see its README.

## Licensing boundary

The toolkit's Python CLI is MIT, following its existing licence. `examples/tactics-possession` is a separate GPL-2.0-or-later example: it contains GPL 0 A.D. code plus original GPL game code. Three.js retains its included MIT notice. The root MIT notice does not relicense the GPL example. No 0 A.D. art/audio or proprietary game files are bundled. The recipe pins source provenance; the manifest detects changes relative to its own hashes, not authenticity.

## Next platform gate

A second host must consume the same orders and prove real movement, obstruction, damage ownership and persistence in its actual running game. A shared JSON format alone does not establish interoperability. The live browser permission lease is off by default, limited to five minutes and the current battle epoch, with revocation and restart/load invalidation. It grants no native game hooks.

## Live agent checkpoint — 13 October 2026

Example 0.1.1 exposes inspect, owned squad orders and viewpoint switching through `document.modelContext` on a supported browser. Click Agent off → Enable for 5 minutes. It uses the same `GameBridge` as player input. This was tested in the Codex browser on a Mac: a tool-issued walk moved the visible guardian, possession selected guardian 2 with existing health, revocation rejected the old registration, and restart removed tools. Combat save/load also passed after fixing entity-reference cycles in navigation. Desktop and 390px permission screens were inspected; human quality acceptance remains NOT_TESTED. 23 game checks pass locally.

API primary source (BOUNDED_EXPERIMENT): https://developer.chrome.com/docs/ai/webmcp/imperative-api (updated 21 September 2026). This is experimental browser support, not a claim of general browser or MCP-client compatibility. Expiry at five minutes is verified with a deterministic clock test; the live browser check verifies revocation and restart, not a five-minute wait.
