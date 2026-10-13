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

## 2026-10-13 — native Mac host locally verified

- Route: compile a separate original Swift/AppKit/SceneKit renderer; bundle the existing simulation and unchanged pinned 0 A.D. command donor into JavaScriptCore. One JS simulation owns movement/collision/damage/saves, native nodes only display it. No WebView, native game injection or modification of paused Minecraft worlds.
- Decisive verifier: same envelope on the native runtime; real movement, obstruction/cooldown, view identity, terminal round and save/reload; then actual native window controls and rendered Metal scene. Source matching alone is insufficient.
- Scope: local native prototype in examples/tactics-possession/native, disposable checkout-specific local temporary output. No signed/notarized release claim. Existing public game stays intact during proof.

- Actual result: compiled the AppKit/SceneKit app and ran 15 checks in Apple JavaScriptCore. Movement, wall collision, strike damage/cooldown/occlusion, ownership, epoch, view identity, terminal combat, malformed import and persistence passed. Twenty-five Node example checks passed; native CI is separate and pending at this entry.
- Native window proof: SceneKit reported Metal and rendered frames. Visible Rally moved guardian 1 z=22 → 5.962; possession preserved all 20 units and 138 HP at the same tick. Native W held 900ms moved it to z=1.462. R restarted; Load restored the exact saved world. Lab saves/receipts/screenshots stay outside the source package.
- Errors: Swift rejected leading-dot number syntax and SpriteKit-style userData. Corrected numeric literals, used SceneKit node names and CGFloat vectors. Signing failed because File Provider reapplied Finder metadata; generated app moved outside synced checkout before signing. A test assumed JSON preserved Infinity; corrected it to check an actual invalid numeric string.
- Platform boundary: local arm64 macOS 26.6.2 window exercised; target macOS 14 and build-machine architecture is not proof of older/Intel compatibility. Binary is locally ad-hoc signed, not notarized or a public native release. Native audio and native MCP not implemented. Browser/native sessions are independent; Minecraft adapter and independent contribution/human acceptance remain untested.
- Sources: Apple SceneKit and JSContext official documentation BOUNDED_EXPERIMENT. Existing donor unchanged. Field note records the repeatable route and observed failures. $0 external spend.
- Next: independent reproduction, native sound/feedback and battlefield framing; keep Worlds Garden paused.

## 2026-10-13 — contributable formation layouts verified locally

- Bounded route: validated declarative formation files change the original host's assignment of destinations after real upstream walk/attack-walk commands. No arbitrary source execution, global script injection, borrowed art or new game installation.
- Shared ownership: browser and JavaScriptCore native host use one formation interpreter; collision and combat remain owned by the existing simulation. A layout describes eight bounded metre offsets, not new engine compatibility.
- Proof required: malformed/overlapping layouts rejected atomically, blocked destinations rejected before order mutation, real donor command moves to different layout targets, persistence/native runtime retains formation, browser import/export/share link changes actual play. Public source ZIP must include the current native and creator code. Human/independent creator remains a separate gate.

- Result: 30/30 Node game checks and 18/18 Apple JavaScriptCore checks pass. Browser imported Creator pincer, rejected a script-bearing file while retaining the valid candidate, applied the layout, and issued a real upstream walk command with eight corresponding destinations. A fresh shared-link tab selected the same layout. Escape restored the intro. The existing battle completed with 12 raiders defeated in 66 seconds; this is agent-operated proof, not human acceptance.
- Interface review: enlarged the preview to distinguish all eight positions. Desktop and 390px viewport were inspected; modal scroll keeps secondary share controls accessible. Human comprehension NOT_TESTED. Native picker remains unimplemented. Proof files stay in chat outputs/remix-formation-workshop-2026-10-13.
- Website source update is pending at this entry. Existing donor files unchanged; no Minecraft edits or external spend. Independent contributor and human playtest remain required.

## 2026-10-13 — squad command revision, local proof only

- Human steering: commanding the squad needs improvement. Preserve this as NEEDS_REVISION until an actual retest, not a human acceptance pass.
- Fixed explicit walk being interrupted by auto-pursuit, and Hold leaving an active destination/path. Shared host owns all resulting movement. Added browser group selection, Select all, Move/Advance modes, accepted-order text, valid destination previews and active destination rings. No donor files changed.
- 35 Node tests passed, including retreat past an enemy, Hold cancellation, attack-walk pursuit, fallen selection and preview-to-host identity. 20 Apple JavaScriptCore checks passed, including native retreat/Hold. JavaScript syntax and whitespace checks passed. Native renderer UI was not changed or relaunched.
- GUI verification blocker: CUA timed out, reset its session, then the former browser ID disappeared. Fresh inventory resolved the IAB to ID 2, but reading its existing test tab also timed out. No page test or screenshot of the new controls is claimed. Public remains version 34. No source/deployment publication for this update.
- Balance evidence before adjustment: idle wins at 71.2 seconds; Rally wins at 62.0 seconds, sanctuary intact in both. Player agency/difficulty is a remaining product issue, not resolved by control fixes.
- Next: verify actual group selection, ground click, preview/actual order, Hold and mobile controls; publish only after that passes. Then human retest and independent contribution. External spend $0. Planned 15-minute pass; interruption and browser failures extended it, exact elapsed/per-run tokens not surfaced.
