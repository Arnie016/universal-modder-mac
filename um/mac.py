"""macOS games from an agent: the `um win` toolkit, rebuilt on Apple's own APIs.

    um mac doctor [--request]                     # permissions, Xcode tools, ffmpeg, Blender, Rosetta, CrossOver
    um mac ps [name]                              # apps with windows: pid, window id, size, title
    um mac kill <pid>                             # by exact PID only (never by pattern); refuses our own tree
    um mac launch /Applications/Foo.app [-- args] # or: um mac launch --steam 105600 / --bundle com.foo.game
    um mac shot --app UZDoom out.png [--scale 0.5]   # one frame of the game window, checked for blank captures
    um mac drive --app UZDoom "focus" "click 640 360" "key escape" "hold w 800" "rel 200 0"
    um mac record --app UZDoom --out ~/caps/take1.mov --seconds 30   # window video + only the game's audio
    um mac stream --app UZDoom --port 7777        # live JPEG frames + input on 127.0.0.1 (for in-game screens)
    um mac sign-info /Applications/Foo.app        # hardened runtime / library validation / dyld entitlements
    um mac inject /path/Game.app --dylib mod.dylib [-- args]        # DYLD_INSERT_LIBRARIES launch (if allowed)

How it maps to `um win`: CGWindowList replaces EnumWindows; `screencapture -l <window id>` replaces
gfxcapture for stills; ScreenCaptureKit (one app's windows + only that app's audio) replaces gfxcapture +
process loopback; CGEvent replaces SendInput and is only sent while the game is frontmost. The native half
is um/swift/umhelper.swift, compiled with swiftc on first use (Xcode command line tools) and cached.

Permissions belong to the app that runs the agent (Terminal, iTerm, VS Code, Cursor, Claude): System
Settings > Privacy & Security > Screen Recording (shots, record, window titles) and Accessibility (drive).
Without Screen Recording, `screencapture` silently returns only wallpaper - `shot` checks for that.
Lessons: drive coordinates are screenshot pixels relative to the window (Retina = 2x points; add "pt" to pass
points); mouselook games read deltas, so use "rel dx dy", not "move"; apps from the Mac App Store are
sandboxed and signed - never patch them in place, mod a copy or use the game's own mod folder.
"""
from __future__ import annotations

import hashlib
import json
import os
import platform
import plistlib
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

from um.common import data_dir, die, is_mac

HERE = Path(__file__).resolve().parent
SWIFT = HERE / "swift" / "umhelper.swift"


def _check_platform():
    if not is_mac():
        die("`um mac` drives macOS games. On Windows/WSL use `um win`.")


# --------------------------------------------------------------------------- helper binary


def helper() -> str:
    """Compile um/swift/umhelper.swift once per source hash (swiftc ships with the Xcode command line tools)."""
    _check_platform()
    digest = hashlib.sha1(SWIFT.read_bytes()).hexdigest()[:12]
    out = data_dir() / "bin" / f"umhelper-{digest}"
    if out.exists():
        return str(out)
    swiftc = shutil.which("swiftc") or "/usr/bin/swiftc"
    if not Path(swiftc).exists():
        die("swiftc not found: run `xcode-select --install` (Xcode command line tools), then retry")
    out.parent.mkdir(parents=True, exist_ok=True)
    for old in out.parent.glob("umhelper-*"):
        old.unlink(missing_ok=True)
    print(f"um: compiling the macOS helper (one time) -> {out}", file=sys.stderr)
    r = subprocess.run([swiftc, "-O", str(SWIFT), "-o", str(out)], capture_output=True, text=True)
    if r.returncode:
        die(f"swiftc failed:\n{r.stderr[-3000:]}")
    return str(out)


def call(*args: str, timeout: float = 30):
    r = subprocess.run([helper(), *args], capture_output=True, text=True, timeout=timeout)
    if r.returncode:
        die((r.stderr or r.stdout).strip())
    return json.loads(r.stdout)


def perms() -> dict:
    return call("perms")


# --------------------------------------------------------------------------- processes and windows


def windows(pid: int | None = None) -> list[dict]:
    return call("windows", *(["--pid", str(pid)] if pid else []))


def processes(name: str | None = None) -> list[dict]:
    """One row per app with a normal window: pid, owner, biggest window."""
    by_pid: dict[int, dict] = {}
    for w in windows():
        cur = by_pid.get(w["pid"])
        if not cur or w["w"] * w["h"] > cur["w"] * cur["h"]:
            by_pid[w["pid"]] = w
    rows = sorted(by_pid.values(), key=lambda w: w["owner"].lower())
    if name:
        rx = re.compile(name, re.I)
        rows = [w for w in rows if rx.search(w["owner"]) or rx.search(w["title"] or "")]
    return rows


def pid_of(app: str | None = None, pid: int | None = None) -> int:
    if pid:
        return pid
    if not app:
        die("give --app NAME (regex on the app/owner name) or --pid")
    rows = processes(app)
    if not rows:
        # not visible (another Space, behind a full-screen app): find the process and check it has a window anywhere
        r = subprocess.run(["pgrep", "-if", app], capture_output=True, text=True)
        cands = [int(p) for p in r.stdout.split() if int(p) != os.getpid() and windows(int(p))]
        if len(cands) == 1:
            return cands[0]
        # not on screen yet? fall back to the process table
        r = subprocess.run(["pgrep", "-x", app], capture_output=True, text=True)
        pids = [int(p) for p in r.stdout.split()]
        if len(pids) == 1:
            return pids[0]
        die(f"no window owned by an app matching {app!r} (is it running and on screen? `um mac ps` lists them)")
    if len(rows) > 1:
        exact = [w for w in rows if w["owner"].lower() == app.lower()]
        if len(exact) == 1:
            return exact[0]["pid"]
        die("several apps match: " + ", ".join(f"{w['owner']} (pid {w['pid']})" for w in rows) + " - pass --pid")
    return rows[0]["pid"]


def main_window(pid: int) -> dict | None:
    ws = windows(pid)
    return max(ws, key=lambda w: w["w"] * w["h"]) if ws else None


def _ancestors() -> set[int]:
    out, p = set(), os.getpid()
    while p > 1 and p not in out:
        out.add(p)
        r = subprocess.run(["ps", "-o", "ppid=", "-p", str(p)], capture_output=True, text=True)
        try:
            p = int(r.stdout.strip())
        except ValueError:
            break
    return out


def kill(pid: int, force: bool = False):
    _check_platform()
    if pid in _ancestors():
        die(f"refusing to kill {pid}: it is this shell or one of its parents")
    try:
        os.kill(pid, signal.SIGKILL if force else signal.SIGTERM)
    except ProcessLookupError:
        die(f"no process {pid}")
    print(f"sent {'SIGKILL' if force else 'SIGTERM'} to {pid}")


def launch(target: str | None, args: list[str], steam: bool = False, bundle: str | None = None, new: bool = False,
           env: dict | None = None) -> int | None:
    """Start a game; returns the pid when we can know it (direct exec), else None (use `um mac ps`)."""
    _check_platform()
    if steam:
        subprocess.run(["open", f"steam://rungameid/{target}"], check=True)
        return None
    if bundle:
        subprocess.run(["open", "-b", bundle] + (["-n"] if new else []) + (["--args", *args] if args else []), check=True)
        return None
    p = Path(target).expanduser()
    if p.suffix == ".app" and not env:
        subprocess.run(["open", "-a", str(p)] + (["-n"] if new else []) + (["--args", *args] if args else []), check=True)
        return None
    exe = bundle_executable(p) if p.suffix == ".app" else p
    proc = subprocess.Popen([str(exe), *args], env={**os.environ, **(env or {})}, stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, start_new_session=True, cwd=str(exe.parent))
    return proc.pid


# --------------------------------------------------------------------------- bundles and signing


def bundle_info(app: Path) -> dict:
    try:
        with open(app / "Contents" / "Info.plist", "rb") as f:
            return plistlib.load(f)
    except (OSError, plistlib.InvalidFileException, ValueError):
        return {}


def bundle_executable(app: Path) -> Path:
    name = bundle_info(app).get("CFBundleExecutable") or app.stem
    return app / "Contents" / "MacOS" / name


def sign_info(path: Path) -> dict:
    """Read-only code-signing facts that decide whether DYLD_INSERT_LIBRARIES / doorstop loaders can work."""
    r = subprocess.run(["codesign", "-dvvv", str(path)], capture_output=True, text=True)
    txt = r.stderr
    info = dict(signed=r.returncode == 0, adhoc="Signature=adhoc" in txt, team=None, flags=[], hardened=False,
                library_validation=False, entitlements={})
    m = re.search(r"TeamIdentifier=(\S+)", txt)
    if m and m.group(1) != "not":
        info["team"] = m.group(1)
    m = re.search(r"flags=0x[0-9a-f]+\(([^)]*)\)", txt)
    if m:
        info["flags"] = [f for f in m.group(1).split(",") if f]
    info["hardened"] = "runtime" in info["flags"]
    info["library_validation"] = "library-validation" in info["flags"]
    e = subprocess.run(["codesign", "-d", "--entitlements", "-", "--xml", str(path)], capture_output=True)
    blob = e.stdout[e.stdout.find(b"<?xml"):] if b"<?xml" in e.stdout else b""
    if blob:
        try:
            info["entitlements"] = plistlib.loads(blob)
        except Exception:
            pass
    ent = info["entitlements"]
    info["app_store"] = (path / "Contents" / "_MASReceipt").exists() if path.suffix == ".app" else False
    info["sandboxed"] = bool(ent.get("com.apple.security.app-sandbox"))
    dyld_ok = not info["hardened"] or bool(ent.get("com.apple.security.cs.allow-dyld-environment-variables"))
    lib_ok = not (info["hardened"] or info["library_validation"]) or bool(ent.get("com.apple.security.cs.disable-library-validation"))
    if not info["signed"]:
        verdict = "unsigned: DYLD_INSERT_LIBRARIES works (arm64 code still needs an ad-hoc signature to run)"
    elif dyld_ok and lib_ok:
        verdict = "DYLD_INSERT_LIBRARIES allowed: doorstop-style loaders (BepInEx run_bepinex.sh, MelonLoader) and your own dylib can load"
    else:
        why = []
        if not dyld_ok:
            why.append("hardened runtime strips DYLD_* variables")
        if not lib_ok:
            why.append("library validation rejects dylibs not signed by the same team")
        verdict = ("blocked (" + "; ".join(why) + "). For a game you own, offline: mod a COPY and re-sign it ad-hoc "
                   "(`codesign --force --deep -s - Copy.app`), or prefer data/script mod routes that need no injection")
    if info["app_store"]:
        verdict = ("Mac App Store app: do not modify or inject into the bundle (signed + receipt-checked, often online). "
                   "Stick to what the game exposes; saves live in ~/Library/Containers/<bundle id>")
    elif info["sandboxed"]:
        verdict += ". Sandboxed app: do not modify the bundle; saves live in ~/Library/Containers/<bundle id>"
    info["injection"] = verdict
    info["dyld_allowed"] = dyld_ok and lib_ok and not info["app_store"]
    return info


# --------------------------------------------------------------------------- doctor


def doctor(request: bool = False) -> dict:
    _check_platform()
    p = call("perms", *(["--request"] if request else []))
    clt = subprocess.run(["xcode-select", "-p"], capture_output=True, text=True)
    blender = next((str(b) for b in sorted(Path("/Applications").glob("Blender*.app"), reverse=True)), None)
    rosetta = Path("/Library/Apple/usr/libexec/oah/libRosettaRuntime").exists()
    rep = dict(
        macos=platform.mac_ver()[0], arch=platform.machine(),
        screen_recording=p["screen"], accessibility=p["accessibility"],
        xcode_tools=clt.stdout.strip() if clt.returncode == 0 else None,
        swiftc=bool(shutil.which("swiftc")), ffmpeg=shutil.which("ffmpeg"), blender=blender,
        rosetta=rosetta if platform.machine() == "arm64" else None,
        crossover=Path("/Applications/CrossOver.app").exists(), steam=Path("/Applications/Steam.app").exists(),
        java=shutil.which("java"), dotnet=shutil.which("dotnet"), fal_key=bool(os.environ.get("FAL_KEY")),
    )
    todo = []
    if not p["screen"]:
        todo.append("Screen Recording: System Settings > Privacy & Security > Screen Recording > enable the app running your agent, then restart it")
    if not p["accessibility"]:
        todo.append("Accessibility: System Settings > Privacy & Security > Accessibility > enable the same app (needed for `um mac drive`)")
    if not rep["xcode_tools"]:
        todo.append("xcode-select --install")
    if not rep["ffmpeg"]:
        todo.append("brew install ffmpeg")
    if rep["rosetta"] is False:
        todo.append("Intel-only games need Rosetta: softwareupdate --install-rosetta")
    if not rep["fal_key"]:
        todo.append("assets: export FAL_KEY=... (https://fal.ai/dashboard/keys) in ~/.zshrc")
    rep["todo"] = todo
    return rep


# --------------------------------------------------------------------------- shot


def _blank(png: Path) -> tuple[bool, float]:
    try:
        from PIL import Image, ImageStat
    except ImportError:
        return False, -1
    im = Image.open(png).convert("L")
    im.thumbnail((256, 256))
    sd = ImageStat.Stat(im).stddev[0]
    return sd < 3.0, sd


def shot(out: str, app: str | None = None, pid: int | None = None, window: int | None = None, scale: float | None = None,
         allow_blank: bool = False) -> str:
    _check_platform()
    if not window:
        w = main_window(pid_of(app, pid))
        if not w:
            die("that app has no on-screen window")
        window = w["id"]
    outp = Path(out).expanduser().resolve()
    outp.parent.mkdir(parents=True, exist_ok=True)
    r = subprocess.run(["screencapture", "-x", "-o", "-l", str(window), str(outp)], capture_output=True, text=True)
    if r.returncode or not outp.exists():
        die(f"screencapture failed: {r.stderr.strip() or 'no file'} (Screen Recording permission? `um mac doctor`)")
    blank, sd = _blank(outp)
    if blank and not allow_blank:
        p = perms()
        hint = "" if p["screen"] else " - Screen Recording is OFF for this terminal/agent app, so macOS returned an empty frame"
        die(f"capture looks blank (stddev {sd:.1f}){hint}. Kept {outp}; pass --allow-blank if a black screen is expected")
    if scale:
        from PIL import Image
        im = Image.open(outp)
        small = outp.with_name(outp.stem + f"_{scale:g}x" + outp.suffix)
        im.resize((max(1, int(im.width * scale)), max(1, int(im.height * scale))), Image.LANCZOS).save(small)
        return f"{outp}\n{small}"
    return str(outp)


# --------------------------------------------------------------------------- drive


class Drive:
    """Same API as um.win.Drive: d = Drive(app="UZDoom"); d.focus(); d.click(640, 360); d.key("escape")."""

    def __init__(self, app: str | None = None, pid: int | None = None):
        self.pid = pid_of(app, pid)
        self.p = subprocess.Popen([helper(), "drive", "--pid", str(self.pid)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1)
        self.ready = self.p.stdout.readline().strip()

    def cmd(self, line: str, retry: bool = True) -> str:
        self.p.stdin.write(line + "\n")
        self.p.stdin.flush()
        out = self.p.stdout.readline().strip()
        if out.startswith("error") and "foreground" in out and retry:
            self.cmd("focus", retry=False)
            time.sleep(0.3)
            return self.cmd(line, retry=False)
        if out.startswith("error"):
            raise RuntimeError(f"{line}: {out}")
        return out

    def focus(self):
        return self.cmd("focus") == "ok"

    def rect(self):
        r = self.cmd("rect")
        return None if r == "none" else tuple(float(v) for v in r.split())

    def click(self, x, y, right=False, pause=0.3):
        self.cmd(f"click {int(x)} {int(y)}" + (" right" if right else ""))
        time.sleep(pause)

    def key(self, k, mode="tap", pause=0.12):
        self.cmd(f"key {k} {mode}")
        time.sleep(pause)

    def hold(self, k, ms):
        self.cmd(f"hold {k} {int(ms)}")

    def look(self, dx, dy):
        self.cmd(f"rel {int(dx)} {int(dy)}")

    def type(self, text):
        self.cmd("type " + text)

    def drag(self, x0, y0, x1, y1):
        self.cmd(f"drag {int(x0)} {int(y0)} {int(x1)} {int(y1)}")

    def wheel(self, d):
        self.cmd(f"wheel {int(d)}")

    def close(self):
        try:
            self.p.stdin.close()
            self.p.wait(5)
        except (OSError, subprocess.TimeoutExpired):
            self.p.kill()


# --------------------------------------------------------------------------- record


class Recorder:
    """ScreenCaptureKit: the game's window only, with only the game's audio, into one .mov (no mux step)."""

    def __init__(self, out: str, app: str | None = None, pid: int | None = None, window: int | None = None, fps: int = 30,
                 audio: bool = True, cursor: bool = False):
        if not window:
            w = main_window(pid_of(app, pid))
            if not w:
                die("that app has no on-screen window")
            window = w["id"]
        self.out = str(Path(out).expanduser().with_suffix(".mov").resolve())
        Path(self.out).parent.mkdir(parents=True, exist_ok=True)
        self.args = ["record", "--window", str(window), "--out", self.out, "--fps", str(fps)] + \
                    ([] if audio else ["--no-audio"]) + (["--cursor"] if cursor else [])
        self.meta = {}

    def start(self):
        self.p = subprocess.Popen([helper(), *self.args], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        buf = ""
        while True:
            line = self.p.stdout.readline()
            if not line:
                die("recorder exited: " + self.p.stderr.read().strip())
            buf += line
            if line.startswith("}"):
                self.meta = json.loads(buf)
                return self

    def stop(self) -> dict:
        try:
            self.p.stdin.write("stop\n")
            self.p.stdin.flush()
        except OSError:
            pass
        out, err = self.p.communicate(timeout=30)
        res = json.loads(out[out.find("{"):]) if "{" in out else {}
        if err.strip():
            res["stderr"] = err.strip()
        return {**self.meta, **res}


# --------------------------------------------------------------------------- inject


def inject(app: str, dylibs: list[str], args: list[str], force: bool = False) -> int | None:
    p = Path(app).expanduser()
    info = sign_info(p)
    if not info["dyld_allowed"] and not force:
        die(f"{info['injection']}\n(--force to try anyway)")
    libs = ":".join(str(Path(d).expanduser().resolve()) for d in dylibs)
    pid = launch(str(p), args, env={"DYLD_INSERT_LIBRARIES": libs})
    print(f"launched pid {pid} with DYLD_INSERT_LIBRARIES={libs}")
    return pid


# --------------------------------------------------------------------------- CLI


def main(a):
    c = a.cmd
    if c == "doctor":
        rep = doctor(a.request)
        if a.json:
            print(json.dumps(rep, indent=2))
            return
        for k, v in rep.items():
            if k != "todo":
                print(f"  {k:17} {v}")
        for t in rep["todo"]:
            print(f"  TODO  {t}")
    elif c == "ps":
        for w in processes(a.name):
            print(f"{w['pid']:>7}  win {w['id']:<7} {w['w']:>5}x{w['h']:<5} {w['owner'][:28]:28} {w['title'] or ''}")
    elif c == "kill":
        kill(a.pid, a.force)
    elif c == "launch":
        pid = launch(a.target, a.args, a.steam, a.bundle, a.new)
        print(f"pid {pid}" if pid else "launched (find it with `um mac ps`)")
    elif c == "shot":
        print(shot(a.output, a.app, a.pid, a.window, a.scale, a.allow_blank))
    elif c == "drive":
        d = Drive(a.app, a.pid)
        print(d.ready)
        try:
            for line in a.commands:
                print(line, "->", d.cmd(line, retry=not a.no_retry))
        finally:
            d.close()
    elif c == "record":
        rec = Recorder(a.out, a.app, a.pid, a.window, a.fps, not a.no_audio, a.cursor).start()
        print(json.dumps(rec.meta, indent=1))
        try:
            if a.seconds:
                time.sleep(a.seconds)
            else:
                input("recording - press Enter to stop ")
        finally:
            print(json.dumps(rec.stop(), indent=1))
    elif c == "stream":
        if not a.window:
            w = main_window(pid_of(a.app, a.pid))
            if not w:
                die("that app has no window")
            a.window = w["id"]
        os.execv(helper(), [helper(), "stream", "--window", str(a.window), "--port", str(a.port), "--width", str(a.width), "--fps", str(a.fps)])
    elif c == "sign-info":
        print(json.dumps(sign_info(Path(a.path).expanduser()), indent=2, default=str))
    elif c == "inject":
        inject(a.app, a.dylib, a.args, a.force)
    elif c == "helper":
        print(helper())


def register(sub):
    import argparse
    p = sub.add_parser("mac", help="macOS: permissions, screenshots, recording with game-only audio, input, signing, injection",
                       description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    cs = p.add_subparsers(dest="cmd", metavar="<cmd>")
    q = cs.add_parser("doctor", help="check permissions and tools (read-only; --request shows the system prompts)")
    q.add_argument("--request", action="store_true", help="ask macOS to show the Screen Recording / Accessibility prompts")
    q.add_argument("--json", action="store_true")
    q.set_defaults(func=main)
    q = cs.add_parser("ps", help="apps that have a window")
    q.add_argument("name", nargs="?", help="regex on app name or window title")
    q.set_defaults(func=main)
    q = cs.add_parser("kill", help="kill by exact PID")
    q.add_argument("pid", type=int)
    q.add_argument("--force", action="store_true", help="SIGKILL instead of SIGTERM")
    q.set_defaults(func=main)
    q = cs.add_parser("launch", help="start a game (.app, executable, --steam APPID or --bundle ID)")
    q.add_argument("target", nargs="?")
    q.add_argument("args", nargs="*")
    q.add_argument("--steam", action="store_true")
    q.add_argument("--bundle")
    q.add_argument("--new", action="store_true", help="new instance even if one is running")
    q.set_defaults(func=main)
    for name in ("shot", "record", "drive"):
        q = cs.add_parser(name, help={"shot": "screenshot of a game window", "record": "record a game window + only its audio",
                                      "drive": "send input to a game (WinDrive-style commands)"}[name])
        q.add_argument("--app", help="app name regex (as in `um mac ps`)")
        q.add_argument("--pid", type=int)
        if name != "drive":
            q.add_argument("--window", type=int, help="CGWindowID (from `um mac ps`)")
        if name == "shot":
            q.add_argument("output")
            q.add_argument("--scale", type=float, help="also write a scaled copy that is cheap to look at")
            q.add_argument("--allow-blank", action="store_true")
        elif name == "record":
            q.add_argument("--out", required=True, help="output path (.mov)")
            q.add_argument("--seconds", type=float)
            q.add_argument("--fps", type=int, default=30)
            q.add_argument("--no-audio", action="store_true")
            q.add_argument("--cursor", action="store_true")
        else:
            q.add_argument("commands", nargs="+", help='focus | rect | click X Y [right] [pt] | move X Y | rel DX DY | drag X0 Y0 X1 Y1 | '
                                                       'key K [tap|down|up] | hold K MS | type TEXT | wheel N | sleep MS')
            q.add_argument("--no-retry", action="store_true")
        q.set_defaults(func=main)
    q = cs.add_parser("stream", help="serve a window as live JPEG frames on 127.0.0.1 and accept input (game-in-a-game screens)")
    q.add_argument("--app")
    q.add_argument("--pid", type=int)
    q.add_argument("--window", type=int)
    q.add_argument("--port", type=int, default=7777)
    q.add_argument("--width", type=int, default=640)
    q.add_argument("--fps", type=int, default=20)
    q.set_defaults(func=main)
    q = cs.add_parser("sign-info", help="code signing facts + whether dylib injection can work (read-only)")
    q.add_argument("path")
    q.set_defaults(func=main)
    q = cs.add_parser("inject", help="launch with DYLD_INSERT_LIBRARIES (refuses when signing blocks it)")
    q.add_argument("app")
    q.add_argument("--dylib", action="append", required=True)
    q.add_argument("args", nargs="*")
    q.add_argument("--force", action="store_true")
    q.set_defaults(func=main)
    q = cs.add_parser("helper", help="compile (if needed) and print the path of the native helper")
    q.set_defaults(func=main)
