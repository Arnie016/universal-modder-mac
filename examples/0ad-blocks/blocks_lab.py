#!/usr/bin/env python3
"""Decisive test for Minecraft-style blocks inside 0 A.D.: real collision, removal of visual + collider, reload.

    python3 blocks_lab.py --public /usr/share/games/0ad/mods/public          # Linux (apt)
    python3 blocks_lab.py --exe "/Applications/0 A.D..app/Contents/MacOS/pyrogenesis" \
        --public "/Applications/0 A.D..app/Contents/Resources/data/mods/public"   # macOS (untested)

It runs the real game headless through its RL interface (-autostart-nonvisual -rl-interface), on the stock
Arcadia scenario, with the um_blocks mod next to this file installed in the user's mod folder:

  1. baseline   a spearman walks 40 m down an open lane; the path is near-straight.
  2. wall       9 granite blocks are placed across the lane; the same walk must go around the wall and never
                stand inside a block.
  3. break      the middle block is removed; its obstruction and visual are gone, and the walk goes through
                the gap.
  4. reload     the remaining blocks are written into a scenario map in the mod, the game is restarted on
                it, and the walk again goes through the same gap: 8 blocks back with colliders, the broken
                one still gone.

Only stdlib. Prints a JSON report and exits 0 when every check passes. Ships no game files: the map written in
step 4 is a copy of Arcadia's own files inside the user's mod folder, not in this repo.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
MOD = "um_blocks"
BLOCK = "um/block_granite"
LAB_MAP = "um_blocks_lab"
SIZE = 4.0          # block footprint, metres (matches the template's Obstruction)
WALL = 9            # blocks in the wall
LANE = 40.0         # walk length, metres


class Game:
    def __init__(self, exe: str, port: int, scenario: str, log: Path):
        self.url = f"http://127.0.0.1:{port}"
        with socket.socket() as s:     # a game left on this port would answer for us and fake every result
            if s.connect_ex(("127.0.0.1", port)) == 0:
                sys.exit(f"port {port} is already in use; stop that process or pass --port")
        cmd = [exe, "-mod=public", f"-mod={MOD}", "-autostart-nonvisual", f"-autostart=scenarios/{scenario}",
               f"-rl-interface=127.0.0.1:{port}"]
        self.proc = subprocess.Popen(cmd, stdout=log.open("w"), stderr=subprocess.STDOUT)
        for _ in range(120):
            try:
                self.started_ms = self.js("return Engine.QueryInterface(SYSTEM_ENTITY, IID_Timer).GetTime();")
                if self.proc.poll() is not None or self.started_ms > 1000:
                    self.close()
                    sys.exit("the RL interface that answered is not a freshly started game")
                return
            except OSError:
                if self.proc.poll() is not None:
                    sys.exit(f"the game exited early; see {log}")
                time.sleep(0.5)
        self.close()
        sys.exit(f"the RL interface never answered; see {log}")

    def post(self, route: str, body: str) -> bytes:
        req = urllib.request.Request(f"{self.url}/{route}", data=body.encode(), method="POST")
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read()

    def js(self, body: str):
        """Evaluate in the simulation; each call gets its own scope (the RL context's globals persist)."""
        return json.loads(self.post("evaluate", f"(()=>{{ {body} }})()") or b"null")

    def step(self, *commands: dict) -> dict:
        return json.loads(self.post("step", "\n".join("1;" + json.dumps(c) for c in commands)))

    def close(self):
        self.proc.terminate()       # the exact process we started
        try:
            self.proc.wait(10)
        except subprocess.TimeoutExpired:
            self.proc.kill()


# --------------------------------------------------------------------------- simulation helpers

def spearman(g: Game) -> int:
    return g.js("""
        let tm = Engine.QueryInterface(SYSTEM_ENTITY, IID_TemplateManager);
        return Engine.QueryInterface(SYSTEM_ENTITY, IID_RangeManager).GetEntitiesByPlayer(1)
            .find(e => tm.GetCurrentTemplateName(e).indexOf("infantry_spearman") != -1);""")


def pos(g: Game, ent: int) -> tuple[float, float]:
    p = g.js(f"let p = Engine.QueryInterface({ent}, IID_Position).GetPosition2D(); return [p.x, p.y];")
    return p[0], p[1]


def teleport(g: Game, ent: int, x: float, z: float):
    g.step({"type": "stop", "entities": [ent], "queued": False})
    g.js(f"Engine.QueryInterface({ent}, IID_Position).JumpTo({x}, {z}); return 1;")
    g.step()


def walk(g: Game, ent: int, start, goal, turns: int = 600) -> dict:
    teleport(g, ent, *start)
    state = g.step({"type": "walk", "entities": [ent], "x": goal[0], "z": goal[1], "queued": False})
    path = [tuple(state["entities"][str(ent)]["position"])]
    for _ in range(turns):
        state = g.step()
        p = tuple(state["entities"][str(ent)]["position"])
        path.append(p)
        if math.dist(p, goal) < 1.5:
            break
    length = sum(math.dist(a, b) for a, b in zip(path, path[1:]))
    return dict(arrived=math.dist(path[-1], goal) < 1.5, turns=len(path) - 1, seconds=round((len(path) - 1) * 0.2, 1),
                length=round(length, 1), path=path)


def place(g: Game, x: float, z: float, angle: float) -> int:
    return g.js(f"""
        let e = Engine.AddEntity("{BLOCK}");
        let p = Engine.QueryInterface(e, IID_Position);
        p.JumpTo({x}, {z}); p.SetYRotation({angle});
        Engine.QueryInterface(e, IID_Ownership).SetOwner(0);
        return e;""")


def blocks(g: Game) -> list[dict]:
    return g.js(f"""
        let tm = Engine.QueryInterface(SYSTEM_ENTITY, IID_TemplateManager);
        return Engine.QueryInterface(SYSTEM_ENTITY, IID_RangeManager).GetEntitiesByPlayer(0)
            .filter(e => tm.GetCurrentTemplateName(e) == "{BLOCK}")
            .map(e => {{
                let p = Engine.QueryInterface(e, IID_Position), o = Engine.QueryInterface(e, IID_Obstruction);
                return {{ id: e, x: p.GetPosition2D().x, z: p.GetPosition2D().y, angle: p.GetRotation().y,
                         collider: !!o && o.GetBlockMovementFlag(true), visual: !!Engine.QueryInterface(e, IID_Visual) }};
            }});""")


def components(g: Game, ent: int) -> dict:
    return g.js(f"""return {{ exists: !!Engine.QueryInterface({ent}, IID_Position),
        collider: !!Engine.QueryInterface({ent}, IID_Obstruction), visual: !!Engine.QueryInterface({ent}, IID_Visual) }};""")


def damage(g: Game, unit: int) -> dict:
    """Both directions a crossover needs: a hit from outside (as a Minecraft sword would send) lands as exact
    damage through the game's own Health.TakeDamage, and a commanded attack shows up as a health change to forward."""
    enemy = g.js("""
        let tm = Engine.QueryInterface(SYSTEM_ENTITY, IID_TemplateManager);
        return Engine.QueryInterface(SYSTEM_ENTITY, IID_RangeManager).GetEntitiesByPlayer(2)
            .find(e => tm.GetCurrentTemplateName(e).indexOf("units/") == 0 && Engine.QueryInterface(e, IID_Health));""")
    hp = lambda: g.js(f"return Engine.QueryInterface({enemy}, IID_Health).GetHitpoints();")
    h0 = hp()
    applied = g.js(f"return Engine.QueryInterface({enemy}, IID_Health).TakeDamage(7, {unit}, 1).healthChange;")
    h1 = hp()
    ex, ez = pos(g, enemy)
    teleport(g, unit, ex + 3, ez)
    g.step({"type": "attack", "entities": [unit], "target": enemy, "allowCapture": False, "queued": False})
    turns = 0
    while hp() >= h1 and turns < 150:
        g.step()
        turns += 1
    return dict(enemy=enemy, outside_hit=dict(before=h0, applied=applied, after=h1),
                attack=dict(turns=turns, health_change=round(hp() - h1, 2)))


# --------------------------------------------------------------------------- geometry

def lane(g: Game, unit: int):
    """An open 40 m lane from the spearman: the first heading whose baseline walk is near-straight."""
    sx, sz = pos(g, unit)
    for k in range(16):
        a = k * math.pi / 8
        d = (math.sin(a), math.cos(a))
        goal = (sx + d[0] * LANE, sz + d[1] * LANE)
        w = walk(g, unit, (sx, sz), goal)
        if w["arrived"] and w["length"] < LANE * 1.05:
            return (sx, sz), goal, d, a, w
    sys.exit("no open lane around the spearman")


def wall_cells(start, d, angle):
    mid = (start[0] + d[0] * LANE / 2, start[1] + d[1] * LANE / 2)
    side = (d[1], -d[0])
    return [(mid[0] + side[0] * SIZE * i, mid[1] + side[1] * SIZE * i, angle) for i in range(-(WALL // 2), WALL // 2 + 1)]


def crossing(path, start, d):
    """Where the path crosses the wall line, as a lateral offset from the lane's centre (metres)."""
    side = (d[1], -d[0])
    along = lambda p: (p[0] - start[0]) * d[0] + (p[1] - start[1]) * d[1]
    lat = lambda p: (p[0] - start[0]) * side[0] + (p[1] - start[1]) * side[1]
    for a, b in zip(path, path[1:]):
        if along(a) < LANE / 2 <= along(b):
            t = (LANE / 2 - along(a)) / (along(b) - along(a))
            return round(lat(a) + t * (lat(b) - lat(a)), 2)
    return None


def inside_any(path, cells) -> int:
    """Samples whose centre lies inside a block's square: a pass-through would show here."""
    n = 0
    for p in path:
        for (x, z, a) in cells:
            dx, dz = p[0] - x, p[1] - z
            u, v = dx * math.cos(a) - dz * math.sin(a), dx * math.sin(a) + dz * math.cos(a)
            if abs(u) < SIZE / 2 and abs(v) < SIZE / 2:
                n += 1
    return n


# --------------------------------------------------------------------------- persistence

def write_map(mods: Path, public: Path, cells: list[dict]):
    """The 'save': the stock Arcadia scenario plus one <Entity> per block, in the mod's own maps folder."""
    out = mods / MOD / "maps" / "scenarios"
    out.mkdir(parents=True, exist_ok=True)
    zp = public / "public.zip"
    read = (lambda rel: zipfile.ZipFile(zp).read(rel)) if zp.exists() else (lambda rel: (public / rel).read_bytes())
    xml = read("maps/scenarios/arcadia.xml").decode("utf-8")
    (out / f"{LAB_MAP}.pmp").write_bytes(read("maps/scenarios/arcadia.pmp"))
    uid = max(map(int, re.findall(r'<Entity uid="(\d+)"', xml))) + 1000
    ents = "".join(
        f'\t\t<Entity uid="{uid + i}">\n\t\t\t<Template>{BLOCK}</Template>\n\t\t\t<Player>0</Player>\n'
        f'\t\t\t<Position x="{c["x"]:.5f}" z="{c["z"]:.5f}"/>\n\t\t\t<Orientation y="{c["angle"]:.5f}"/>\n\t\t</Entity>\n'
        for i, c in enumerate(cells))
    (out / f"{LAB_MAP}.xml").write_text(xml.replace("\t</Entities>", ents + "\t</Entities>", 1), encoding="utf-8")


def install(mods: Path):
    dst = mods / MOD
    for f in (HERE / MOD).rglob("*"):
        if f.is_file():
            (dst / f.relative_to(HERE / MOD)).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, dst / f.relative_to(HERE / MOD))


# --------------------------------------------------------------------------- the test

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--exe", default=shutil.which("pyrogenesis") or "/usr/games/pyrogenesis")
    ap.add_argument("--public", required=True, help="the game's mods/public folder (holds public.zip)")
    ap.add_argument("--mods", default=str(Path.home() / ".local/share/0ad/mods"),
                    help="user mod folder (macOS: ~/Library/Application Support/0ad/mods)")
    ap.add_argument("--port", type=int, default=6000)
    ap.add_argument("--log", default="blocks_lab.game.log")
    a = ap.parse_args()
    mods, public, log = Path(a.mods), Path(a.public), Path(a.log)
    install(mods)
    report, checks = {}, {}

    g = Game(a.exe, a.port, "arcadia", log)
    try:
        unit = spearman(g)
        start, goal, d, angle, base = lane(g, unit)
        report["baseline"] = {k: v for k, v in base.items() if k != "path"}

        cells = wall_cells(start, d, angle)
        ids = [place(g, *c) for c in cells]
        g.step()
        walled = walk(g, unit, start, goal)
        report["wall"] = {k: v for k, v in walled.items() if k != "path"} | dict(
            blocks=len(ids), crossing_offset=crossing(walled["path"], start, d),
            samples_inside_blocks=inside_any(walled["path"], cells))
        half = WALL * SIZE / 2
        checks["wall_forces_detour"] = walled["arrived"] and abs(report["wall"]["crossing_offset"] or 0) >= half
        checks["never_inside_a_block"] = report["wall"]["samples_inside_blocks"] == 0

        mid = ids[WALL // 2]
        before = components(g, mid)
        g.js(f"Engine.DestroyEntity({mid}); return 1;")
        g.step()
        after = components(g, mid)
        gap = walk(g, unit, start, goal)
        report["break"] = dict(block=mid, before=before, after=after,
                               **{k: v for k, v in gap.items() if k != "path"},
                               crossing_offset=crossing(gap["path"], start, d),
                               samples_inside_blocks=inside_any(gap["path"], cells[:WALL // 2] + cells[WALL // 2 + 1:]))
        checks["break_removes_visual_and_collider"] = before["collider"] and before["visual"] and not any(after.values())
        checks["walk_uses_the_gap"] = gap["arrived"] and abs(report["break"]["crossing_offset"]) < SIZE / 2
        saved = blocks(g)
        write_map(mods, public, saved)
        report["damage"] = damage(g, unit)
        checks["outside_hit_applies_exact_damage"] = report["damage"]["outside_hit"]["applied"] == -7
        checks["commanded_attack_lowers_enemy_health"] = report["damage"]["attack"]["health_change"] < 0
    finally:
        g.close()

    g = Game(a.exe, a.port, LAB_MAP, log)
    try:
        unit = spearman(g)
        back = blocks(g)
        again = walk(g, unit, start, goal)
        moved = max(min(math.dist((b["x"], b["z"]), (s["x"], s["z"])) for b in back) for s in saved)
        report["reload"] = dict(fresh_game_clock_ms=g.started_ms, saved=len(saved), loaded=len(back), max_position_error=round(moved, 4),
                                all_have_colliders=all(b["collider"] and b["visual"] for b in back),
                                **{k: v for k, v in again.items() if k != "path"},
                                crossing_offset=crossing(again["path"], start, d),
                                samples_inside_blocks=inside_any(again["path"], [(b["x"], b["z"], b["angle"]) for b in back]))
        # walk a lane shifted sideways onto a block that survived the reload: it must still be walked around
        side = (d[1], -d[0])
        shift = lambda p: (p[0] + side[0] * 2 * SIZE, p[1] + side[1] * 2 * SIZE)
        onto = walk(g, unit, shift(start), shift(goal))
        off = crossing(onto["path"], shift(start), d)
        report["reload"]["lane_onto_a_kept_block"] = dict(arrived=onto["arrived"], length=onto["length"], crossing_offset=off)
        checks["reload_kept_block_still_collides"] = onto["arrived"] and off is not None and abs(off) >= SIZE / 2
        checks["reload_restores_8_blocks"] = len(back) == WALL - 1 and moved < 0.01 and report["reload"]["all_have_colliders"]
        checks["reload_keeps_the_gap"] = again["arrived"] and abs(report["reload"]["crossing_offset"]) < SIZE / 2
        checks["reload_never_inside_a_block"] = report["reload"]["samples_inside_blocks"] == 0
    finally:
        g.close()

    report["checks"] = checks
    report["passed"] = all(checks.values())
    report["evidence"] = "0 A.D. 0.0.26 headless via the RL interface; AI pathing, no rendering; player feel not tested"
    print(json.dumps(report, indent=2))
    sys.exit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
