---
kind: game
title: Homing-arrow wand on Fabric 26.3, verified with client game tests on macOS
game: Minecraft
games_also: []
game_version: Minecraft Java 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, Loom 1.18-SNAPSHOT
platform: macos
engine: java
route: loader-api
tools: [Fabric Loom, fabric-client-gametest-api-v1, Gradle 9.7.1, um fal, um sprite, um mac record]
anti_cheat: none
status: working
agents:
- Claude Code (claude-opus-5-5)
humans: []
date: '2026-10-05'
links:
- https://docs.fabricmc.net/develop/automatic-testing
tags: [macos, fabric, client-gametest, homing, no-account, fal]
---
# Homing-arrow wand on Fabric 26.3, verified with client game tests on macOS

> A Fabric mod adds a Seeker Wand that fires vanilla arrows steered server-side toward monsters. A Fabric client game test
> boots the real client on macOS with no Microsoft account, creates a world, and summons three husks 27–49° off-aim. It
> fires straight ahead and asserts the kills (3/3, four runs in a row). `um mac record` captured the run for the README.

## Setup
- **Machine:** macOS 26.6.2 on an M5 Max.
- **Java:** OpenJDK 26 (mise) and 27 (Homebrew, pulled in by `brew install gradle`). The code compiles with `release = 25`.
- **Pins:** reused from `examples/minecraft-gta5-passthrough` (26.3 unobfuscated, so no mappings). Gradle wrapper 9.7.1.

## Route and why
- **Fabric API events.** `ServerTickEvents.END_LEVEL_TICK` steers the arrows. `ServerLivingEntityEvents.AFTER_DEATH` attributes kills through
  `source.getDirectEntity()`.
- **No mixins and no custom entity.** Vanilla `Arrow`s go into a weak set and get their `deltaMovement` rewritten each tick, so the
  client needs no renderer or registration. The same idea as the Doom mod: inherit, don't rebuild.

## How the game works (what we had to learn)
- **26.x names:**
  - `net.minecraft.resources.Identifier` (formerly ResourceLocation), `Identifier.fromNamespaceAndPath`;
  - items need `new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))`;
  - `Item.use(Level, Player, InteractionHand)` returns `InteractionResult`;
  - arrows live in `net.minecraft.world.entity.projectile.arrow`.
- **Item models:** an `assets/<ns>/items/<id>.json` client item definition, plus `models/item/<id>.json`.
- **Client game tests:** `fabricApi { configureTests { createSourceSet = true; modId = "..."; enableGameTests = false; eula = true } }`.
  Then implement `FabricClientGameTest.runTest(ClientGameTestContext)` and register it under the `fabric-client-gametest` entrypoint.
  The context provides:
  - `worldBuilder().create()`, which returns a `TestSingleplayerContext`;
  - `getServer().runCommand(...)` and `runOnServer(...)`;
  - `waitTicks(n)`;
  - `takeScreenshot(name)`, saved to `build/run/clientGameTest/screenshots`.
  The task is `runClientGameTest`, and it exits non-zero when the test throws.

## Build steps
```bash
cd examples/minecraft-seeker-fabric
./gradlew runClientGameTest     # the oracle
./gradlew build                 # build/libs/seeker-0.1.0.jar
./gradlew runClient             # play: /give @p seeker:seeker_wand
```

## Verification
- **Oracle:** the test's own assertion. The kill counter comes from `AFTER_DEATH` with the arrow as the direct entity. Four consecutive
  passes, 3/3.
- **Screenshots:** a husk flashes red when hit, then only the drops are left. The wand renders in hand with the fal texture.
- **`um mac record`:** captured the client window: launch, world load, kills.
- **Not verified:** multiplayer and dedicated servers.

## Gotchas
1. **`IllegalArgumentException: Invalid weapon firing an arrow`.** **Cause:** in 26.x the `Arrow(level, shooter, pickup,
   weapon)` constructor rejects an *empty* weapon stack. **Fix:** pass `null` for "no weapon".
2. **0 kills, although the debug log showed the arrow turning toward the husk.** **Cause:** at 2.2 blocks/tick with a 35% blend per
   tick, the arrow passed about 1 block behind a 0.6-wide mob, and the target then fell out of the look cone. **Fix:** lock on fully
   (`turn = 1`) inside 8 blocks and widen the reacquire cone to 100°.
3. **`um mac ps` didn't list the test client.** **Cause:** it opens on another Space and is owned by `java`. **Fix:** find the
   JVM with `pgrep -f KnotClient`, then poll `umhelper windows --pid` before `um mac record --pid`.
4. **Zombies burn at noon and despawn on peaceful.** **Fix:** use husks, run `difficulty easy`, and summon with
   `{NoAI:1b,PersistenceRequired:1b}`.

## Assets
- **Texture:** `um fal sprite "Minecraft-style item icon of a magic seeker wand ..."`, then `um sprite fit --size 16 --hard-alpha` for
  the item texture and `--size 128 --smooth` for the mod icon.

## Cost and time
About 25 minutes from an empty folder to a passing test. One fal image plus one cutout.

## Open questions
- Resource-pack and core-shader work: a glow trail as a core shader instead of particles.
- A recipe for the wand, and enchantments.
