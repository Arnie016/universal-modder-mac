# Recipes

A recipe packages one gameplay mechanic so that someone else can run it in a real game, check it, and improve
it. `um lab` validates recipes, runs their acceptance tests through an adapter, writes receipts, and serves the
same operations to agents over MCP.

| Recipe | What it is | Evidence |
| --- | --- | --- |
| [`0ad-hero-squad`](0ad-hero-squad/) | You play one hero in first person inside the original 0 A.D. engine. It has a Minecraft-style HUD, stone blocks that really block movement, and a squad you command from a hotbar. | Linux, 0 A.D. 0.0.26: 14/14 headless acceptance, 21/21 visual play with real input, and an MCP client session in both protocol eras. |
| [`templates/demo-counter`](templates/demo-counter/) | The adapter template ([`adapter_template.py`](templates/adapter_template.py)) with a one-number "game". It is not a game integration. | 14/14 acceptance; also run in CI. |

```text
recipes/<id>/
  recipe.json        the recipe (format umx-recipe/0.1, JSON Schema in umx-recipe-0.1.schema.json)
  commands.wit       the same commands in WIT (optional; checked against recipe.json)
  fixtures/*.jsonl   replay fixtures
  PLAYBOOK.md        how to run it, what to expect, what fails
  evidence/          sanitized receipts and summaries the recipe's claims rest on
  receipts/          receipts other people contributed (see CONTRIBUTING.md)
```

## recipe.json (umx-recipe/0.1)

| Field | Meaning |
| --- | --- |
| `format`, `id`, `version` | `umx-recipe/0.1`, a kebab-case id, and a semver version. |
| `title`, `summary`, `status` | What it is, in plain words, and how far it has been proven. |
| `root` | The folder the artifact paths are relative to. It may not leave the project that holds `recipes/`. |
| `engine` | `game`, supported `versions`, the `adapter` (built-in name) or `adapter_cmd`, the `mod` folder, `scenario` and `player`. `interfaces` lists the real upstream interfaces used, each with `evidence` (a source file and line, or a docs URL). |
| `profile` | `kind` is `native-worker`, `wasm-restricted` or `reference`. `sandbox` says what isolation there is (often none). `why` says why this profile fits. |
| `provenance` | `upstream` lists each project with `version`, `url`, `license`, what is used and what is `shipped`. `reused` and `new` list what was reused and what is new. |
| `licenses` | `recipe`, `code`, `assets` and any `open_questions`. |
| `artifacts` | Every file the recipe ships or runs, with its `sha256` and `role`. `um lab hash --write` records new hashes after a deliberate change. |
| `units`, `timing` | Length, angle and time units, and the coordinate frame. `turn_ms`, who owns the `clock`, and when commands apply. |
| `envelope` | The `src`, `seq`, `session` and `epoch` rules: refusals, resume, restart and load. |
| `commands` | One JSON Schema per command type. The `type` is a `const`, and `additionalProperties` is false. |
| `observation` | A JSON Schema for what `observe` returns. |
| `ownership` | Who owns `movement`, `collision`, `health`, `inventory` and `persistence` (usually the engine), and what the recipe does to each. |
| `owned_entities`, `effects` | What the recipe creates or orders, the maximum of each, and what every command does. |
| `limits` | Turns per step, total turns, commands per send and wall-clock seconds, all enforced by the lab, plus game and process limits with `enforced_by`. |
| `fixtures`, `acceptance` | Replay fixtures, the harmless `probe` command used by the envelope checks, and the fixtures the acceptance suite runs. |
| `playbook`, `wit`, `evidence` | The playbook file, the WIT file, and evidence entries (`date`, `kind`, `result`, `file`, `sha256`). |

A receipt names the recipe by `digest`: the sha256 of recipe.json without its evidence list. Adding evidence
therefore does not change the recipe's identity. Changing anything else does, and `um lab validate` then warns
that older receipts came from an earlier version.

## Commands and the envelope

A command is plain JSON: `{"type": "um-place", "x": 642, "z": 594, "block": "granite"}`. The lab checks it against
the recipe's schema and adds the envelope: `src`, `seq` (1, 2, 3... per src), and `session` and `epoch` from the
last observation. Commands are typed the same whether they come from the game's own UI, a script or an agent over
MCP.

The game, or an adapter that has no scripting, applies these rules in this order:

1. `stale-session`: the command names another session. Every start gets a fresh one, so commands from before a
   restart are refused.
2. `stale-epoch`: the command was sent before the last load.
3. `bad-envelope`: `src` is missing, or `seq` is not a whole number of at least 1.
4. `duplicate`: `seq` is not above the last one seen from this `src`. The command is ignored and nothing changes.
5. Otherwise the command applies. If seqs were skipped, the ack has `gap: true`.

Every command gets an ack numbered `n`. To resume after a disconnect, observe and carry on from
`lastSeq[src] + 1`. Resending the last command is harmless.

## Fixtures

A fixture is JSON Lines. Each line can have these keys:

- `note`: what the line checks.
- `send`: one command or a list.
- `turns`: how many turns to step. The default is 1 when something was sent.
- `as`: a name to store this line's ack under.
- `expect`: checks on `ack` (the ack of the last command sent) and on `state` (the observation afterwards).

The lab fills in two placeholders:

- `"at": {"ahead_m": 12, "right_m": 0}` becomes `x` and `z` relative to the avatar.
- `{"$ref": "block.entity"}` becomes a field of an earlier ack stored with `as`.

State paths are dotted (`hud.xp`, `squad.0.inFormation`, `squad.length`). A check is a literal or an operator:
`==`, `!=`, `>`, `>=`, `<`, `<=`, `in`, or `delta` (the change since before the line). `travel:<path>` is how
many metres the `{x, z}` at that path moved.

## Adapters (umx-adapter/0.1)

An adapter is a process. It reads one JSON request per line on stdin and writes one JSON answer per line on
stdout, `{"id": n, "ok": true, ...}` or `{"id": n, "ok": false, "error": "...", "code": "..."}`. It logs to
stderr.

| Op | Request | Answer |
| --- | --- | --- |
| `hello` | | `protocol`, `adapter`, `game {name, version}`, `turn_ms`, `profile` |
| `start` | `mod`, `scenario`, `player`, `commands` (the allowed types), `evidence_dir` | `session`, `epoch`, `timeMs` |
| `send` | `commands` (enveloped) | `queued` |
| `step` | `turns` | `timeMs`, `acks` (those after the last step, by `n`), `acks_dropped` if any were missed |
| `observe` | `player` | `state` |
| `stop` | | `stopped`, plus whatever the adapter measured |

An adapter must follow these rules:

- The game does not advance between steps.
- Queued commands apply in the first turn of the next step.
- Only the recipe's command types are accepted.
- Params the adapter does not know are ignored.
- `start` on a running game starts a new one.

The built-in `0ad` adapter (`um lab adapter 0ad`) runs the original engine headless through its RL interface.
`templates/adapter_template.py` is a complete adapter to copy.

## Execution profiles

- `native-worker`: the original engine runs in its own process. The lab adds limits, not isolation. Only the
  recipe's commands go through, the RL port listens on 127.0.0.1, and the process gets a CPU-time cap and a
  wall-clock deadline and is stopped by its exact PID. On Linux it writes to a lab folder. **It is not an OS
  sandbox**: the game runs with your permissions.
- `wasm-restricted`: an engine-free rule compiled to WebAssembly with no imports, meaning no WASI: no files,
  clock, network or randomness. It runs under fuel and memory caps. The `squad-rule` world in
  `0ad-hero-squad/commands.wit` is the contract for this profile. Whole engines like 0 A.D. do not compile to
  WebAssembly, so this profile is for rules, not games.
- `reference`: the template.

## Receipts and metrics

`um lab accept` and `um lab run` write `receipt.json`. It holds:

- the checks;
- the recipe id, version and digest, and whether the artifact hashes matched;
- the adapter and game versions;
- the platform;
- a random machine id (made once per install, not derived from the hardware or the user);
- the transcript's sha256 and the run totals.

`um lab receipt <folder>` prints a receipt only if it is free of home paths, user names, emails and key-like
strings.

`um lab metrics` reports counts, not forecasts:

- **Completed remixes**: recipes with a passing receipt.
- **Independent reproductions**: further machine ids with a passing receipt.
- **Repeat usage**: machines with receipts on two or more days.

It counts the receipts on this machine and in the folders passed with `--from`.
