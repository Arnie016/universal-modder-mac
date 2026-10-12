"""Remix a game's rules without touching the game: an overlay data folder, exact attribute patches, A/B runs, restore.

    um remix new fast --game supertuxkart                       # overlay of the installed game's data folder
    um remix set fast kart_characteristics.xml "characteristic[name=base]/engine" max-speed=35
    um remix show fast                                          # every patch: file, element, old -> new
    um remix bench fast --runs 3 --laps 2 --track lighthouse    # SuperTuxKart: headless A/B race, base vs remix
    um remix run fast                                           # play the remix (the game reads the overlay)
    um remix reset fast                                         # drop all patches; the overlay matches the game again
    um remix verify fast                                        # the game's own files still hash as recorded

How it stays reversible: the overlay is a tree of symlinks to the game's data, and only a file you patch is
copied into it, so the installed game (or a signed .app) is never written. The game is pointed at the overlay
through its own data-folder setting (SuperTuxKart: SUPERTUXKART_DATADIR). Patches edit one attribute of one
element in place, keeping the file's comments and layout, and refuse to apply when the path matches zero or
several elements. Overlays live in ~/.universal-modder/remix/<name>.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import xml.parsers.expat
from pathlib import Path

from um.common import data_dir, die, emit, is_mac

# Games the overlay knows how to point at. `data` lists install locations of the folder to overlay; `env` names
# the variable the game reads; `root` says whether that variable wants the data folder or its parent.
GAMES = {
    "supertuxkart": dict(
        data=["/usr/share/games/supertuxkart/data", "/usr/share/supertuxkart/data",
              "/Applications/SuperTuxKart.app/Contents/Resources/data",
              str(Path.home() / "Applications/SuperTuxKart.app/Contents/Resources/data")],
        exe=["/usr/games/supertuxkart", "/usr/bin/supertuxkart",
             "/Applications/SuperTuxKart.app/Contents/MacOS/supertuxkart",
             str(Path.home() / "Applications/SuperTuxKart.app/Contents/MacOS/supertuxkart")],
        env="SUPERTUXKART_DATADIR", root="parent"),
}


def remix_dir(name: str) -> Path:
    if not re.fullmatch(r"[\w.-]+", name):
        die(f"remix names are letters, digits, '.', '_' or '-': {name!r}")
    return data_dir() / "remix" / name


def sha256(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def load(name: str) -> dict:
    m = remix_dir(name) / "remix.json"
    if not m.exists():
        die(f"no remix {name!r}; create it with `um remix new {name} --game ...` or `--base DIR`")
    return json.loads(m.read_text())


def save(name: str, man: dict):
    (remix_dir(name) / "remix.json").write_text(json.dumps(man, indent=2) + "\n")


def first_existing(paths: list[str]) -> str | None:
    return next((p for p in paths if Path(p).exists()), None)


# --------------------------------------------------------------------------- overlay

def new(name: str, base: str | None, game: str | None) -> dict:
    prof = GAMES.get(game or "", {})
    if game and not prof:
        die(f"unknown game {game!r}; known: {', '.join(GAMES)} (or pass --base DIR)")
    base = base or first_existing(prof.get("data", []))
    if not base or not Path(base).is_dir():
        die("game data folder not found; pass --base DIR")
    d = remix_dir(name)
    if d.exists():
        die(f"remix {name!r} already exists at {d}")
    overlay = d / "data"
    overlay.mkdir(parents=True)
    for entry in sorted(Path(base).iterdir()):
        (overlay / entry.name).symlink_to(entry.resolve())
    man = dict(name=name, game=game, base=str(Path(base).resolve()), overlay=str(overlay), patches=[], originals={})
    save(name, man)
    return man


def materialize(man: dict, rel: str) -> Path:
    """Copy-on-write: turn the symlinked path to `rel` into real folders down to a private copy of the file."""
    overlay, base = Path(man["overlay"]), Path(man["base"])
    parts = Path(rel).parts
    if ".." in parts or Path(rel).is_absolute():
        die(f"path must stay inside the game data: {rel}")
    cur = overlay
    for i, part in enumerate(parts):
        nxt = cur / part
        src = base.joinpath(*parts[: i + 1])
        last = i == len(parts) - 1
        if nxt.is_symlink():
            nxt.unlink()
            if last:
                shutil.copy2(src, nxt)
            else:
                nxt.mkdir()
                for e in sorted(src.iterdir()):
                    (nxt / e.name).symlink_to(e.resolve())
        elif not nxt.exists():
            die(f"{rel}: not in the game data at {base}")
        cur = nxt
    if rel not in man["originals"]:
        man["originals"][rel] = sha256(base / rel)
    return cur


# --------------------------------------------------------------------------- exact attribute patches

def parse_path(path: str) -> list[tuple[str, dict]]:
    segs = []
    for seg in path.strip("/").split("/"):
        m = re.fullmatch(r"([\w.:-]+)((?:\[[\w.:-]+=[^\]]*\])*)", seg)
        if not m:
            die(f"bad path segment {seg!r}: use tag or tag[attr=value]")
        segs.append((m.group(1), dict(re.findall(r"\[([\w.:-]+)=([^\]]*)\]", m.group(2)))))
    return segs


def find_elements(data: bytes, path: str) -> list[int]:
    """Byte offsets of start tags whose ancestry ends with `path` (each segment: tag + attribute filters)."""
    want, stack, hits = parse_path(path), [], []
    p = xml.parsers.expat.ParserCreate()

    def start(tag, attrs):
        stack.append((tag, attrs))
        if len(stack) >= len(want) and all(
                t == wt and all(a.get(k) == v for k, v in wa.items())
                for (t, a), (wt, wa) in zip(stack[-len(want):], want)):
            hits.append(p.CurrentByteIndex)

    p.StartElementHandler = start
    p.EndElementHandler = lambda tag: stack.pop()
    p.Parse(data, True)
    return hits


def set_attr(data: bytes, offset: int, attr: str, value: str) -> tuple[bytes, str]:
    end = data.index(b">", offset)
    tag = data[offset:end].decode("utf-8")
    m = re.search(r'(\s' + re.escape(attr) + r'\s*=\s*)(["\'])(.*?)\2', tag)
    if not m:
        die(f"element at byte {offset} has no attribute {attr!r}")
    if any(c in value for c in "\"'<>&"):
        die("values cannot contain quotes, <, > or &")
    new_tag = tag[:m.start(3)] + value + tag[m.end(3):]
    return data[:offset] + new_tag.encode("utf-8") + data[end:], m.group(3)


def set_values(name: str, rel: str, path: str, assignments: list[str]) -> list[dict]:
    man = load(name)
    target = materialize(man, rel)
    data = target.read_bytes()
    hits = find_elements(data, path)
    if len(hits) != 1:
        die(f"{path!r} matches {len(hits)} elements in {rel}; it must match exactly one (add [attr=value] filters)")
    done = []
    for a in assignments:
        if "=" not in a:
            die(f"expected attr=value, got {a!r}")
        attr, value = a.split("=", 1)
        data, old = set_attr(data, find_elements(data, path)[0], attr, value)
        done.append(dict(file=rel, path=path, attr=attr, old=old, new=value))
    target.write_bytes(data)
    man["patches"] += done
    save(name, man)
    return done


def reset(name: str) -> dict:
    man = load(name)
    for rel in man["originals"]:
        shutil.copy2(Path(man["base"]) / rel, Path(man["overlay"]) / rel)
    man["patches"] = []
    save(name, man)
    return verify(name)


def verify(name: str) -> dict:
    man = load(name)
    base, overlay = Path(man["base"]), Path(man["overlay"])
    rows = []
    for rel, h in man["originals"].items():
        rows.append(dict(file=rel, game_file_unchanged=sha256(base / rel) == h,
                         overlay_matches_game=sha256(overlay / rel) == h))
    return dict(remix=name, files=rows, patches=len(man["patches"]),
                ok=all(r["game_file_unchanged"] for r in rows))


# --------------------------------------------------------------------------- run and bench

def env_for(man: dict) -> dict:
    prof = GAMES.get(man.get("game") or "", {})
    env = dict(os.environ)
    if prof.get("env"):
        env[prof["env"]] = str(Path(man["overlay"]).parent if prof.get("root") == "parent" else man["overlay"])
    return env


def exe_for(man: dict, exe: str | None) -> str:
    exe = exe or first_existing(GAMES.get(man.get("game") or "", {}).get("exe", []))
    if not exe:
        die("game executable not found; pass --exe")
    return exe


PROFILE_ROW = re.compile(r"profile:\s+(\S+)\s+(\d+)\s+(\d+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s")


def stk_race(exe: str, env: dict, laps: int, track: str, kart: str) -> dict:
    """One headless SuperTuxKart profile race (AI drives); returns time, average and top speed."""
    cmd = [exe, "--no-graphics", f"--profile-laps={laps}", f"--track={track}", "--numkarts=1", f"--kart={kart}", f"--aiNP={kart}"]
    out = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=600).stdout
    rows = [m for m in PROFILE_ROW.finditer(out)]
    if not rows:
        die("no profile result in SuperTuxKart's output; run the command by hand:\n  " + " ".join(cmd))
    m = rows[-1]
    return dict(time=float(m.group(4)), avg_speed=float(m.group(5)), top_speed=float(m.group(6)))


def bench(name: str, runs: int, laps: int, track: str, kart: str, exe: str | None) -> dict:
    man = load(name)
    if man.get("game") != "supertuxkart":
        die("bench knows SuperTuxKart only so far")
    exe = exe_for(man, exe)
    plain = {k: v for k, v in os.environ.items() if k != GAMES["supertuxkart"]["env"]}
    stk_race(exe, plain, 1, track, kart)   # the first run after a fresh config differs; warm it up once
    sides = {}
    for label, env in (("base", plain), ("remix", env_for(man))):
        rs = [stk_race(exe, env, laps, track, kart) for _ in range(runs)]
        sides[label] = {k: dict(mean=round(statistics.mean(r[k] for r in rs), 2),
                                spread=round(max(r[k] for r in rs) - min(r[k] for r in rs), 2)) for k in rs[0]}
    delta = {k: round(sides["remix"][k]["mean"] - sides["base"][k]["mean"], 2) for k in sides["base"]}
    return dict(name=name, patches=man["patches"], track=track, kart=kart, laps=laps, runs=runs, **sides, delta=delta,
                verify=verify(name), evidence="headless AI-driven profile races; play feel not tested")


def run(name: str, exe: str | None, args: list[str]):
    man = load(name)
    cmd = [exe_for(man, exe)] + args
    os.execvpe(cmd[0], cmd, env_for(man))


# --------------------------------------------------------------------------- CLI

def main(a):
    if a.cmd == "new":
        emit(new(a.name, a.base, a.game))
    elif a.cmd == "set":
        emit(set_values(a.name, a.file, a.path, a.assign))
    elif a.cmd == "show":
        emit(load(a.name))
    elif a.cmd == "reset":
        emit(reset(a.name))
    elif a.cmd == "verify":
        r = verify(a.name)
        emit(r)
        sys.exit(0 if r["ok"] else 1)
    elif a.cmd == "bench":
        emit(bench(a.name, a.runs, a.laps, a.track, a.kart, a.exe))
    elif a.cmd == "run":
        run(a.name, a.exe, a.args)
    elif a.cmd == "list":
        root = data_dir() / "remix"
        emit(sorted(p.name for p in root.iterdir()) if root.is_dir() else [])


def register(sub):
    p = sub.add_parser("remix", help="change a game's rules through an overlay: patch, A/B race, reset, verify")
    p.description = __doc__
    cs = p.add_subparsers(dest="cmd", required=True)
    n = cs.add_parser("new", help="create an overlay of a game's data folder")
    n.add_argument("name")
    n.add_argument("--game", choices=list(GAMES))
    n.add_argument("--base", help="the data folder to overlay (default: the game's usual install)")
    s = cs.add_parser("set", help="set attributes on exactly one XML element")
    s.add_argument("name")
    s.add_argument("file", help="path inside the data folder, e.g. kart_characteristics.xml")
    s.add_argument("path", help="element path, e.g. \"characteristic[name=base]/engine\"")
    s.add_argument("assign", nargs="+", help="attr=value")
    for c in ("show", "reset", "verify"):
        cs.add_parser(c).add_argument("name")
    b = cs.add_parser("bench", help="SuperTuxKart: headless AI races, base vs remix")
    b.add_argument("name")
    b.add_argument("--runs", type=int, default=3)
    b.add_argument("--laps", type=int, default=2)
    b.add_argument("--track", default="lighthouse")
    b.add_argument("--kart", default="tux")
    b.add_argument("--exe")
    r = cs.add_parser("run", help="launch the game on the remix; extra args go to the game")
    r.add_argument("name")
    r.add_argument("--exe")
    r.add_argument("args", nargs=argparse.REMAINDER)
    cs.add_parser("list")
    p.set_defaults(func=main)

