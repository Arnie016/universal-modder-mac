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
- Next: review draft change, reproduce from its source, then validate a second real host adapter. Zero external spend.
