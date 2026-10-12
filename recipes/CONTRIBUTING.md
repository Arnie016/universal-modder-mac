# Contributing recipes

You run recipes on your own machine, with your own copy of each game and your own tools. We accept sanitized
receipts and patches. **We never ask for API keys, paid credits, private prompts, saves or game files**, and a PR
that contains any of them will be closed.

Before you open a PR, ask your human. Opening one publishes your work.

## 1. Reproduce a recipe (the most useful contribution)

```sh
um lab validate recipes/0ad-hero-squad
um lab accept recipes/0ad-hero-squad        # finds the game in the usual places; see its PLAYBOOK.md
um lab receipt ~/.universal-modder/lab/runs/<the folder it printed>  > receipt.json
```

Add the receipt as `recipes/<id>/receipts/<date>-<platform>.json` and say in the PR what you ran it on (OS, game
version, how you installed the game).

A failing receipt is welcome too. Include the failing check names and the last 30 lines of the run's `game.log`
in the PR text. Read those lines first, because logs can contain your paths.

`um lab metrics --from recipes` counts these receipts. A reproduction counts when a different machine id passes.

## 2. Improve a recipe

1. Change the files and run `um lab accept` until it passes.
2. Record the new hashes: `um lab hash recipes/<id> --write`.
3. Bump `version`:
   - patch for a fix that keeps every command the same;
   - minor for a new command or a new optional field;
   - major for a changed or removed command.
4. Replace the evidence with your new receipt, and keep the old one if it shows something the new one does not.
5. In the PR, say what changed for a player, what you checked, and what you could not check.

## 3. Add a recipe or an adapter for another game

1. **Find the real interface first.** Name the upstream interface you will use and link it in
   `engine.interfaces[].evidence`: a source file and line, an official docs page, or a mod API. A README promise
   is not an interface. If you cannot find one, stop and report that. Do not build a lookalike and call it an
   integration.
2. **Copy the templates.** Start from `templates/demo-counter/` and `templates/adapter_template.py`. Keep the
   envelope rules (`World.admit`): put them in the game's own scripting when it has some, as UmCrossover.js does,
   and otherwise in the adapter.
3. **Keep the game in charge of what it already does.** Movement, collision, health, inventory and saving
   normally stay with the engine. Say so in `ownership`, and change only what the mechanic adds.
4. **Pause the game between steps.** The lab owns the clock. If the game cannot be paused, say so in `timing`;
   the recipe is then not replayable.
5. **Ship no game files.** Do not commit extracted assets, decompiled code or proprietary files. Refer to the
   game's files by path, and load them from the player's own install. List every upstream project with its
   licence in `provenance`, and say what you ship.
6. **Write a fixture** that a fresh game passes the same way every time. Then run:

   ```sh
   um lab validate recipes/<id>
   um lab accept recipes/<id> --adapter-cmd "<your adapter>"
   ```

7. Add a `PLAYBOOK.md` with setup, the expected result, the common failures and the proof limits.

## What reviewers check

- `um lab validate` passes, and the evidence receipt matches the recipe (no "earlier version" warning).
- Every interface has evidence, and nothing is described as working that no receipt shows.
- No game files, keys, personal paths or prompts are anywhere in the diff (`um publish check .`).
- The licences fit: for example, GPL code is not copied into MIT files.
- Commands are typed (`additionalProperties: false`), limits are set, and the fixture runs from a fresh game.

## What we do not accept

- Anything that bypasses anti-cheat, DRM or ownership checks.
- Cheats against other players.
- Mods for online clients that are protected by anti-cheat.

The rules in `skills/mod-any-game/references/safety.md` apply here too.
