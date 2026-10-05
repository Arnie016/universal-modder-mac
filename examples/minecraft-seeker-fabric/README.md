# Seeker: a homing-arrow wand for Minecraft 26.3 (Fabric), built and verified on macOS

<p align="center"><img src="../../docs/media/mac-minecraft-seeker.gif" width="640" alt="Seeker arrows curving into three off-axis husks"></p>

Right-click the **Seeker Wand** and it fires a glowing arrow. The arrow bends toward the nearest monster within 32 blocks,
then locks on inside 8 blocks. It uses vanilla arrows steered server-side each tick, so there is no custom entity and no
renderer. The item texture came from fal.

## Play it
```bash
./gradlew runClient                 # dev client with an offline username - no Microsoft account needed
# in game: /seeker demo   (wand + 5 husks around you), then RIGHT-click (two-finger click on a trackpad) to fire
```
Or drop `build/libs/seeker-0.1.0.jar` (after `./gradlew build`), plus Fabric API, into a Fabric 26.3 instance's `mods/`.

## Prove it: the client game test
```bash
./gradlew runClientGameTest
```
This uses Fabric's client game test API to boot the **real client**. The test:
- creates a world;
- summons three husks 27°, 35° and 49° away from where the player looks;
- fires six seeker arrows straight ahead;
- requires at least 2 homing kills, and saves screenshots to `build/run/clientGameTest/screenshots/`.

**Result on macOS 26.6, M5 Max:** 3/3 kills, passing on 4 consecutive runs. Each run takes about 30 s, including the client boot.

## Pins
| | |
|---|---|
| Minecraft | 26.3 (unobfuscated, so there are no mappings) |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.161.0+26.3 |
| Loom | 1.18-SNAPSHOT |
| Gradle | 9.7.1 (wrapper) |
| Java | toolchain 25+ (built with OpenJDK 26/27) |

## Credits
- Item texture generated with [fal](https://fal.ai) (openai/gpt-image-2, cut out with fal-ai/birefnet/v2), then reduced to
  16×16 with `um sprite fit`.
- Minecraft is © Mojang. This mod ships only its own code and art.
