# Mac Arsenal: a homing rocket launcher for Freedoom on UZDoom (macOS)

A ZScript mod built and verified entirely on macOS, as the end-to-end test of the `um mac` port.

- **`SeekerLauncher`** (slot 5) fires `SeekerRocket`s. They look for a target even when you didn't aim at one and steer up to 6°
  per step, leaving an ice-blue trail. It reuses the IWAD's own rocket sprites, recoloured, so no game art ships.
- **Pickup sprite** `mod/sprites/SEEKA0.png` was generated with fal (`um fal sprite`), fitted with `um sprite fit`, and
  given Doom offsets through a `grAb` PNG chunk.
- **`MacArsenalHandler`** hands the weapon out on spawn and logs every seeker kill. `netevent ma_test` places three
  imps 20–55° away from where the player aims, so only homing can hit them.

## Run it
```bash
./build.sh                                         # -> MacArsenal.pk3
open -a UZDoom --args -iwad /path/to/freedoom2.wad -file "$PWD/MacArsenal.pk3"
```
Freedoom: https://github.com/freedoom/freedoom/releases. UZDoom for Mac: https://github.com/UZDoom/UZDoom/releases.
Any Doom II IWAD works.

## Verify (no Screen Recording permission needed)
```bash
./verify.sh      # isolated config in lab/, runs test.cfg, prints the mod's log lines, saves engine screenshots
```
Results on UZDoom 5.0.3 / Freedoom 0.13.0 / macOS 26.6 (M5 Max), screen locked:
- Run 1 killed all three off-axis imps.
- Run 2 killed two. The 55° imp was at the edge of the seeker's look cone.

Lessons are in `knowledge/games/doom/` (field note).

## Credits
- Pickup sprite: generated with [fal](https://fal.ai) (openai/gpt-image-2, cut out with fal-ai/birefnet/v2).
- Rocket, launcher and puff sprites come from the IWAD you load. Nothing from Freedoom is redistributed here.
