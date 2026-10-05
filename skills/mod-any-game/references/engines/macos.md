# macOS: Mac builds, signing, permissions and `um mac`

Read this whenever the game is a `.app` bundle, or the agent runs on a Mac. The engine playbook still applies.
This file covers what changes on macOS.

## Find the game
- `um scan --list` lists `.app` games in `/Applications`, `/Applications/Games`, `~/Applications`, `~/Games`
  (games category, or an engine signature in the bundle) plus Steam's `steamapps/common`.
- `um scan <name or .app>` reads the bundle the same way it reads a Windows folder, plus `Info.plist`,
  the Mach-O architectures (arm64 / x86_64 under Rosetta), and the code signature.
- The macOS launcher of a game is not always the game. Minecraft.app is only the launcher: Java Edition lives in
  `~/Library/Application Support/minecraft`.

## Where things live inside a bundle
| Engine | Mac layout |
|---|---|
| Unity Mono | `Contents/Resources/Data/Managed/Assembly-CSharp.dll`, `Contents/Frameworks/UnityPlayer.dylib` |
| Unity IL2CPP | `Contents/Frameworks/GameAssembly.dylib` + `Contents/Resources/Data/il2cpp_data/Metadata/global-metadata.dat` |
| Unreal | `Contents/UE/<Project>/Content/Paks/*.pak`, binary `Contents/MacOS/<Project>-Mac-Shipping` |
| Godot | `Contents/Resources/<name>.pck` |
| GameMaker | `Contents/Resources/game.ios` (same format as data.win) |
| Electron / NW.js | `Contents/Resources/app.asar` / `app.nw` |
| Ren'Py | `Contents/Resources/autorun/{renpy,game}` |
| LÖVE | `Contents/Resources/*.love` |
| FNA / MonoGame | managed `.dll`/`.exe` in `Contents/Resources` or `Contents/MonoBundle` |
| Doom source ports | the port is the `.app`; the IWAD and your PK3 are outside it (`-iwad`, `-file`) |
| Wine wrappers (CrossOver, Wineskin, Whisky, many ports) | a Windows game inside `drive_c`: use the Windows route |

Saves: `~/Library/Application Support/<name or company/product>`, Unity prefs in
`~/Library/Preferences/unity.<company>.<product>.plist`, sandboxed apps in
`~/Library/Containers/<bundle id>/Data`. `um backup` works on any of them.

## Signing decides what can load
`um mac sign-info Game.app` (read-only) reports the team, hardened runtime, library validation, sandbox, App
Store receipt, and a verdict.
- **Not hardened, or hardened with `allow-dyld-environment-variables` + `disable-library-validation`:**
  `DYLD_INSERT_LIBRARIES` works. That is how BepInEx's `run_bepinex.sh` (Doorstop) and MelonLoader load, and how
  `um mac inject Game.app --dylib mod.dylib` loads your own dylib.
- **Hardened without those entitlements:** dyld strips `DYLD_*` and refuses foreign dylibs. For a game you own,
  played offline, the community route is to mod a **copy** and re-sign it ad-hoc:
  `ditto Game.app ~/Games/Game-modded.app && codesign --force --deep -s - ~/Games/Game-modded.app`.
  Ask the user first, never touch the original, and expect Steam updates to restore it.
- **Mac App Store / sandboxed:** do not modify or inject. Receipts and the sandbox break, and many of these
  games are online. Stick to what the game itself exposes.
- **Intel-only (`x86_64`) binaries run under Rosetta:** an injected dylib has to be x86_64 too
  (`clang -arch x86_64`), and so do native debuggers attached to it.
- System Integrity Protection blocks injection into Apple binaries. Never disable SIP for a mod.

## Native code tools on macOS
- Read: Ghidra, Hopper, `otool -L` (linked libraries), `nm -gU` (exports), `class-dump` for Objective-C.
- Debug: `lldb` (Xcode tools). Attaching to hardened apps needs the `get-task-allow` entitlement, which
  ad-hoc re-signed copies can carry.
- Hook: fishhook (rebinds imported symbols), Dobby or frida-gum (inline hooks), Frida for live probing,
  frida-il2cpp-bridge for Unity IL2CPP.
- .NET: `dotnet tool install -g ilspycmd`, then `ilspycmd Assembly-CSharp.dll -o out/` (keep the output outside the repo).

## Drive, see and record the game: `um mac`
`um mac doctor` first. The app running the agent (Terminal, iTerm, VS Code, Cursor, the agent's desktop app) needs:
- **Screen Recording** for `shot`, `record` and window titles. Without it macOS quietly returns wallpaper, and `shot` fails loudly
  rather than handing you a blank frame.
- **Accessibility** for `drive`.

Only the user can grant these. `um mac doctor --request` shows the system prompts.

```bash
um mac ps                                   # pid, window id, size, owner
um mac launch ~/Applications/UZDoom.app -- -iwad freedoom2.wad -file mod.pk3
um mac shot --app UZDoom shot.png --scale 0.5
um mac drive --app UZDoom "focus" "key escape" "hold w 600" "rel 300 0" "click 640 400"
um mac record --app UZDoom --out take1.mov --seconds 20   # window + only the game's audio, one .mov
um mac kill 12345                            # exact PID, never our own process tree
```
- **Drive coordinates** are screenshot pixels relative to the window, and Retina is 2x. Append `pt` to pass points.
- **Mouselook games** read deltas, so turn with `rel dx dy`, not `move`.
- **Key names:** names (`escape`, `w`, `f1`), Windows VK hex (`0x1B`, so WinDrive scripts port over) or `mac:53`.
- **Input:** goes only to the frontmost app. `drive` refuses otherwise, and the Python `Drive` refocuses once.

## Verify without the screen: the game's own screenshots
A locked or occluded Mac stops presenting OpenGL frames, and many games then stall on their swap
(the same trap as Godot's headless capture). Before blaming the mod:
- **Use the Vulkan (MoltenVK) or Metal backend:** for GZDoom/UZDoom, `+vid_preferbackend 1`. It kept ticking with the screen locked
  where OpenGL froze right after the map loaded.
- **Use in-engine screenshots:** the engine's `screenshot` command, Unity's `ScreenCapture`, Godot's `get_viewport().get_texture()`. They read
  the framebuffer, so they need no Screen Recording permission.
- **Log a line per behaviour:** `Console.Printf`, `Debug.Log`, `print`, and read stdout.
- **ZDoom-family scripts:** `wait N` only delays the rest of the same command string, so put a timed test in one
  `alias`. See `examples/macos-uzdoom-freedoom/test.cfg`.
