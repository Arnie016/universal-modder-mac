# Remix creator kit MODLOG

## 2026-10-13 — isolated toolkit branch

- Target: reusable local package/host/test/serve entry point for the deployed Tactics Possession crossover.
- Base: toolkit macos branch 280c599. Main desktop checkout has active Minecraft edits; none were changed.
- Route: MIT Python CLI reads versioned recipe/host/manifest; separate GPL2-or-later example contains pinned 0 A.D. EntityCollection and original browser simulation. No game installation, save migration or native injection.
- Provenance: donor 78ca04310ebe9c02d8db22eb6bbd79ee0f3dfac9; unchanged EntityCollection SHA256 615cdb8774b22df3265d3ec504e03f836a7d5091b73d8d137ca3127eb0cc81cc. Three.js 0.186.1 with MIT notice. No upstream art/audio.
- Verifiers: 70/70 Python tests (54 existing plus 16 package/contract/server/execution-gate checks), 15/15 actual example tests through um remix test, 45 KB notes check pass, publish lint 0 failures. Existing unrelated Project Zomboid field note produces one absolute-path warning; Pillow emits one deprecation warning.
- Browser: entered the localhost package served by um remix serve, rallied squad, possessed guardian, held W; actual camera/HUD/battle responded. HTTP server served modules successfully. Screenshot retained in the parent task evidence folder, not shipped as private telemetry.
- Errors: app-managed worktree creation could not resolve a Git repo from the chat folder. An explicit local Git worktree in the toolkit repository provided isolation. Initial diff lint caught a blank line at EOF; corrected. Staging revealed that the base repo did not ignore npm dependencies; removed them from the index and added an example-specific ignore rule before committing.
- Boundaries: manifest hash is not authenticity, declarations are not native compatibility, tests are reviewed source execution rather than a sandbox, MCP controls an isolated world. Human playtest and independent contributor reproduction NOT_TESTED; native/Minecraft adapters NOT_IMPLEMENTED. Worlds Garden remained paused.
- CI now includes the real game acceptance suite on Linux and macOS; results are separate from local proof. New authored diff passes whitespace checks; an existing whitespace line in the copied unchanged Three.js vendor source is preserved.
- Next: review draft change, reproduce from its source, then validate a second real host adapter. Zero external spend.

## 2026-10-13 — opt-in tools for the visible battle

- Outcome: optional WebMCP connection for five minutes, with inspect/orders/possession sharing the player host. Default off; revoke, restart, load and pagehide end registration. Per-call lease/epoch/ownership checks reject stale callbacks and invalid commands. No account/file access, time stepping, reset or save tool.
- Evidence: 23/23 example tests pass through `um remix test`, including seven lease/validation checks and a live-combat JSON save regression. Actual Mac Codex browser called all three tools: guardian 1 moved from (-3.181,20.583) to (-2.093,17.980); guardian 2 became the first-person entity with 156 HP and the same 20 entities. Revoke removed tools and the old handle was rejected; restart removed registration. Combat save/load restored 2:25 and guardian 2 at 87% HP.
- Failure found/fixed: unit pursuit paths retained live entities, creating circular references during combat and breaking JSON observations/saves. Route points now contain coordinates only. The browser also cached an older module; versioned script/import URLs and a fresh localhost origin ensured the fixed source was tested.
- Source disposition: official Chrome WebMCP imperative API BOUNDED_EXPERIMENT. Modern `document.modelContext.registerTool` with AbortSignal is available in the tested browser. No claim of universal browser availability.
- Design: permission/revoke screen reviewed at 1280x800 and 390x844. Human review NOT_TESTED.
- Evidence folder (local, not committed): chat outputs/remix-live-tools-2026-10-13, including live-receipt.json and two screenshots.
- Boundaries: stdio MCP remains an isolated headless world; native/Minecraft adapters remain NOT_IMPLEMENTED. Independent contributor remains NOT_TESTED. No Worlds Garden edits or external spend.
- Next bounded step: a contributor reproduces the package and adds one supported rule, with an independent browser verifier.
