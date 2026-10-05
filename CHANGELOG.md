# Changelog

## 0.4.0: Minecraft on macOS (2026-10-05)
- `examples/minecraft-seeker-fabric`: a homing-arrow wand for Minecraft 26.3 / Fabric with a fal texture, verified with a
  Fabric client game test on macOS (3/3 kills, 4 consecutive runs). Field note and a minecraft.md section on client game tests.
- `um mac record` follows windows across Spaces (desktop-independent window filter) and starts on an NSApplication main
  thread. `um mac ps`/`shot` find a game's window on another Space.
- `bin/um` survives macOS hiding `.pth` files in iCloud-synced folders.

## 0.3.0: macOS port (2026-10-05)
Forked from [rehan-remade/universal-modder](https://github.com/rehan-remade/universal-modder) at `d60152b`.

### Added
- **`um mac`**: `doctor`, `ps`, `launch`, `shot`, `record`, `drive`, `kill`, `sign-info`, `inject`, `helper`.
  The native half is `um/swift/umhelper.swift`, compiled with `swiftc` on first use and cached by source hash.
  - `record`: ScreenCaptureKit with a desktop-independent window filter. It writes one `.mov` (H.264 + AAC) with only
    the game's audio, and follows the window across Spaces.
  - `shot`: `screencapture -l`, with blank-frame detection that names the missing permission.
  - `drive`: CGEvent input with WinDrive-compatible commands (Windows VK hex accepted), plus `rel dx dy` for mouselook. It only
    sends input while the game is frontmost.
  - `sign-info` / `inject`: hardened runtime, library validation, dyld entitlements, sandbox and App Store receipt
    decide whether `DYLD_INSERT_LIBRARIES` (BepInEx/Doorstop, your dylib) can work.
- **`um scan` on macOS**: finds `.app` games; reads Mac bundle layouts (Unity, Unreal, Godot, GameMaker, Electron/NW.js,
  Ren'Py, LÖVE), Mach-O architectures (Rosetta) and code signing; spots Wine wrappers; gives Mac routes and save folders.
- `skills/mod-any-game/references/engines/macos.md` playbook. The game-automation skill covers macOS.
- `examples/macos-uzdoom-freedoom`: a homing rocket launcher verified in game, with a fal pickup sprite.
- Field note `knowledge/games/freedoom/...`. Fixture tests for Mac bundles and Mach-O. macOS CI job.

### Fixed
- `um fal sprite`: fal's `openai/gpt-image-2` rejects `background=transparent` (HTTP 422). Sprites now render on white
  and are cut out with BiRefNet.
- `um render3d` finds versioned Blender apps (`Blender 5.2.app`).
- `um publish check` knows `.ios`, `.asar`, `.unity3d` and `.resource` archives.
