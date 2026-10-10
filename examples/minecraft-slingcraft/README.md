# Worlds Garden · Minecraft minigames on Mac

A Fabric 26.3 mod for the real Minecraft Java client: Sling Siege, Frostline GP and Bloom, connected by a landscaped garden lobby.

## Public playtest

Join **54.169.34.180** with the matching 0.4.4 client mods. Follow [JOIN.md](JOIN.md) for the download requirements and compass controls. The public server-list response is verified; authenticated remote gameplay and concurrent capacity are still awaiting confirmation.

## Start from the garden

Select hotbar **9** and right-click the compass to open a native three-row chest menu. Hover over the bird, pod/boat or fungus icon for the mode description and reward; click to enter. The menu also contains your XP/credits, the upgrade shop, guide and optional creator-support page. Display icons cannot be taken as items.

Hotbar **8** holds a real guide book covering controls, goals, bird powers, race results, Bloom extraction and rewards. The support page credits **Dooley Doo**. No donation checkout or external payment link is configured yet.

During a game, the slot-9 compass returns you to the garden. The game selector and shop work for ordinary players without operator permission. Each mode receives a fresh kit; the first pre-garden inventory is archived before replacement.

Frostline now has position/lap/time readouts and a finish screen showing best lap and earned rewards. Choose **Race again**, **Change setup** or **Garden**, or wait eight seconds for the next grid. A race ends after four minutes; only a completed three-lap run earns the 100 XP/credits reward. Opponents are paced racers, not tactical AI. Multiplayer competition remains unverified.

## Play on this Mac

Double-click **Play Sling Siege.command**. The prepared **Sling Siege Challenge** world opens in a separate Minecraft development profile. The previous Sling Siege save is preserved. The normal Minecraft Launcher installation and its saves are not changed.

An installed JDK 25 or newer is required. The first development launch needs internet to obtain the pinned Minecraft/Fabric dependencies. This is a development launcher for Minecraft owners; it is not a standalone replacement for Minecraft or a general App Store game runner.

| Input | Action |
| --- | --- |
| Mouse + WASD | Look and move in first person |
| Hold right mouse / R, then release | Draw the equipped bird bow and shoot |
| 1 | Red bird: impact and loose blocks |
| 2 | Bomb bird: wider impact, including stone |
| 3 | Blue bird: splits automatically into three projectiles |
| 4–8 / mouse wheel | Building materials, TNT, flint and steel |
| 9, then right-click | Return compass; opens the game grid in the garden |
| Right mouse / R with a block | Normal Minecraft block placement |
| Hold left mouse / X | Break a block inside the fort |
| E | Your Minecraft inventory and building kit |
| N / V | Administrator retry / next shortcuts; public rounds advance automatically |
| Space | Jump |
| Esc | Minecraft pause menu |

The bird icon in the hotbar is a bow. In your hand it draws like a normal bow; the projectile is the visible 3D bird. The matching bird is displayed in the offhand when that slot is available. No arrows are required for these bird bows.

Build inside the gold outline. Bird damage and support collapse are restricted to the siege volume. Unsupported connected structures become falling blocks; those can settle into piles and damage targets. TNT uses Minecraft's own blast effects, with block damage restricted to the fort. The foundation, launch pad and surrounding scenery stay protected. Your four kit materials refill after successful placement; the unrestricted Creative catalogue is unavailable. The six marked green pigs are the targets.

## The three-fort challenge

1. **Glass Roost:** a tall timber-and-glass tower; knock out its supports.
2. **Twin Nests:** separated towers; choose which nest to hit first.
3. **Stone Crown:** a larger citadel with four corner towers, battlements, flags, elevated walkways and a real trapdoor deck. The six targets remain in the central keep; bomb birds can breach its stone supports.

Each attempt gives five birds and six targets. The top bar shows targets remaining,
birds remaining and score. A clear starts a six-second countdown to the next fort.
A loss starts an eight-second countdown to retry. When birds run out, the round
waits for projectiles and falling debris to settle before showing the result.

Score: 1,000 per target; a clear adds 500 per unused bird. Building, breaking and
TNT are allowed inside the fort. Scores are shared within this prototype arena;
competitive balancing is unverified. Target identity is tracked so a pig wandering
outside the fort is not counted as defeated.

Best scores, unlocks and the selected fort are saved. Reopening the challenge
rebuilds that fort as a fresh attempt; mid-flight projectiles and temporary player
construction are not a resumable checkpoint. Only the lab arena is rebuilt.

## Aim and destruction

Hold a bird bow to draw it. A small **2D flight preview** appears while aiming; the
main camera stays in first person. The dots predict the central bird's block-hit
path from its current charge and look direction. The number is an estimate:
pigs can move into the path, water changes drag, and blue side-birds diverge.

The gold build pad is 37 by 37 blocks. Place kit blocks inside its fort boundary.
On the final fort, right-click or R directly on a trapdoor panel to open it; each
panel uses Minecraft's real hinge and collision. Build steps to reach upper
walkways. The game handles rounds and resets automatically.

Destruction uses radial impacts and a support-connectivity check, followed by
Minecraft falling blocks and TNT. It is **not** a stress solver or joined rigid-body
rotation. Support collapse processes up to 48 blocks per pass and waits when its
nearby debris budget is occupied. A larger hit can still generate additional
native TNT/debris work; frame-time benchmarking remains open.

Physics Mod 3.2.5 for 26.3 was tested separately on this Mac. Its launch stalled
before reaching gameplay; it is not enabled or bundled in this candidate. See
`CROSSOVER_RESEARCH.md` in the distribution for the verified sources and future
Box2D/Matter.js bridge design.

## Existing Fabric installation

Use `slingcraft-0.4.4.jar` with **Minecraft Java 26.3**, **Fabric Loader 0.19.5**, **Fabric API 0.161.0+26.3**, and **Java 25+**. Back up any world before enabling the mod. For a server, follow `SERVER_AND_BEDROCK.md` and use its starter world configuration. The `/sling level` command is an administrator's arena setup tool; players enter through the compass or portals. The compass also opens saved achievements, Worlds levels and the credit shop.

`/sling kit` replaces hotbar slots and gives the three bows. `/sling reset` resets the existing arena. Arena creation/reset requires game-master command permission. One arena per dimension is kept in memory; only the Overworld arena location is persisted by this prototype. Multiplayer balancing and multi-arena support are not verified.

## What is included

- Actual Minecraft hotbar, inventory, bow animation, building and TNT.
- Three authored voxel bird models, with textures referenced from the user's Minecraft installation.
- R/X alternate controls alongside the ordinary mouse controls.
- Charge meter, selected bird readout, side-view trajectory estimate, round titles and a retry shortcut.
- Three stages, five-bird rounds, local scores and saved unlocks.
- Support connectivity checks, target feedback and dimension-scoped projectiles.
- A Fabric client game test that drives the actual input path.

This is an Angry Birds-inspired fan experiment with newly authored bird geometry and gameplay code. It does not include Rovio's engine, extracted models, artwork or audio. Minecraft game binaries and textures are not included in the mod archive.

## Build and verify

```sh
./gradlew build runClientGameTest --console=plain
```

Use a compatible `JAVA_HOME`. The current suite creates disposable worlds and tests lobby/inventory boundaries, the chest/book/shop flow, Bloom's objective loop and a complete race driven with W/A/D. `SLINGCRAFT_PROOF=menu`, `race` or `public-siege` selects a narrower flow. `FROSTLINE_RESULTS_ACTION=setup` exercises finish-to-garage; `timeout` waits through the real four-minute race limit. See `MODLOG.md` for tests actually run and evidence. Human play-feel remains unreviewed until the player tries it.

## Mac platform direction

The reusable module here is a Fabric mod plus version pins, an isolated play profile, a launcher and an input-level test. Future Minecraft Agent Lab integration could construct new forts and record how well they survive. The pasted Agent Lab setup notes are design context, not evidence of working keys, running bots or webhook services. No paid API is needed for this build.
