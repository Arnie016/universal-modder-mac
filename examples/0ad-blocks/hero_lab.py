#!/usr/bin/env python3
"""Hero mode in the rendered game, played with real mouse and keyboard input.

    xvfb-run -s "-screen 0 1280x720x24" python3 hero_lab.py --public /usr/share/games/0ad/mods/public --out hero/

It starts 0 A.D. with graphics and the RL interface, then plays the way a person would, through xdotool:
clicks "Play as hero", picks hotbar slots with the number keys, clicks to place and break blocks, walks with W,
turns with D, blows the horn, sets a box formation, attacks with the sword, opens the tactical map with Tab,
quicksaves with Shift+F5 and quickloads with Shift+F8. After each step it reads the simulation's own state
(UmCrossover.Observe) and saves a screenshot. The report is JSON, and the script exits 0 only when every
check passes.

The clock: with the RL interface, 0 A.D. advances only when /step is called. A background thread calls it
five times a second (one 200 ms turn each), so the game runs at normal speed. The GUI's commands and this
script's commands go through the same command queue and the same "um-*" handlers.

Two steps use test fixtures, and the report says so: an enemy spearman is spawned 14 m from the hero so the
sword has a target, and two envelope checks (stale epoch, duplicate seq) are sent from the script.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path

import blocks_lab as lab

OBSERVE = "return Engine.QueryInterface(SYSTEM_ENTITY, IID_UmCrossover).Observe(1);"
BLOCKS = """let tm = Engine.QueryInterface(SYSTEM_ENTITY, IID_TemplateManager);
    return Engine.QueryInterface(SYSTEM_ENTITY, IID_RangeManager).GetEntitiesByPlayer(0)
        .filter(e => (tm.GetCurrentTemplateName(e) || "").startsWith("um/block_")).length;"""


class Clock:
    """Owns the game clock: steps one turn every 1/hz seconds. All RL calls go through its lock."""

    def __init__(self, game: lab.Game, hz: float = 5.0):
        self.game, self.period, self.lock = game, 1.0 / hz, threading.Lock()
        self.running, self.turns = True, 0
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _run(self):
        while self.running:
            t = time.monotonic()
            with self.lock:
                self.game.step()
                self.turns += 1
            time.sleep(max(0.0, self.period - (time.monotonic() - t)))

    def js(self, body: str):
        with self.lock:
            return self.game.js(body)

    def send(self, *commands: dict):
        with self.lock:
            self.game.step(*commands)
            self.turns += 1

    def stop(self):
        self.running = False
        self.thread.join(5)


class Player:
    """Real input on the X display, plus screenshots."""

    def __init__(self, display: str, out: Path):
        self.env = dict(os.environ, DISPLAY=display)
        self.out = out

    def xdo(self, *args: str):
        subprocess.run(["xdotool", *args], env=self.env, check=True)

    def click(self, x: int, y: int, button: int = 1):
        self.xdo("mousemove", str(x), str(y))
        time.sleep(0.15)
        self.xdo("click", str(button))

    def key(self, name: str):
        self.xdo("key", name)

    def hold(self, name: str, seconds: float):
        self.xdo("keydown", name)
        time.sleep(seconds)
        self.xdo("keyup", name)

    def shot(self, name: str, settle: float = 1.0) -> str:
        time.sleep(settle)
        path = self.out / f"{name}.png"
        subprocess.run(["import", "-display", self.env["DISPLAY"], "-window", "root", str(path)], check=True)
        return path.name


def game_started(g: lab.Game) -> bool:
    """InitGame has run: the stock StatisticsTracker fills its sequences then. A step sent earlier, while the
    loading screen is still up, makes the tracker's timer throw "this.sequences is undefined"."""
    return bool(g.js("let s = QueryPlayerIDInterface(1, IID_StatisticsTracker); return !!(s && s.sequences);"))


def wait_for(clock: Clock, test, timeout: float = 8.0):
    """Poll the simulation until test(observation) is true; return the last observation."""
    end = time.monotonic() + timeout
    obs = clock.js(OBSERVE)
    while not test(obs) and time.monotonic() < end:
        time.sleep(0.25)
        obs = clock.js(OBSERVE)
    return obs


def ack(obs: dict, src_prefix: str, kind: str) -> dict | None:
    return next((a for a in reversed(obs["acks"]) if a["src"].startswith(src_prefix) and a["type"] == kind), None)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--exe", default=shutil.which("pyrogenesis") or "/usr/games/pyrogenesis")
    ap.add_argument("--public", required=True)
    ap.add_argument("--mods", default=str(Path.home() / ".local/share/0ad/mods"))
    ap.add_argument("--port", type=int, default=6004)
    ap.add_argument("--out", default="hero")
    a = ap.parse_args()
    display = os.environ.get("DISPLAY") or exit("no DISPLAY; run under xvfb-run or on a desktop")
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    lab.install(Path(a.mods), Path(a.public))

    g = lab.Game(a.exe, a.port, "arcadia", out / "game.log", visual=True,
                 extra=["-nosound", "-conf=pauseonfocusloss:false", "-conf=view.zoom.default:80"])
    checks, shots, notes = {}, [], {}
    clock = None
    try:
        for _ in range(120):          # the rendered game answers before its map and InitGame are done
            if lab.spearman(g) and game_started(g):
                break
            time.sleep(1)
        clock = Clock(g)
        p = Player(display, out)
        play(clock, p, checks, shots, notes)
    except Exception as e:            # keep the evidence gathered so far
        notes["aborted"] = repr(e)
    finally:
        if clock:
            clock.stop()
        g.close()
    write_report(a, out, checks, shots, notes)


def gui_ack(clock: Clock, kind: str, after_seq: int, timeout: float = 6.0) -> dict | None:
    """The first acknowledgement of a GUI command of this kind newer than after_seq."""
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        found = [a for a in clock.js(OBSERVE)["acks"] if a["src"].startswith("gui-") and a["type"] == kind and a["seq"] > after_seq]
        if found:
            return found[0]
        time.sleep(0.25)
    return None


def last_gui_seq(clock: Clock) -> int:
    return max([a["seq"] for a in clock.js(OBSERVE)["acks"] if a["src"].startswith("gui-")] or [0])


def place_like_a_player(clock: Clock, p: Player, tries: int = 6) -> tuple[dict | None, list]:
    """Slot 1 and a click; if the cell is taken, turn a little with D and try again, as a person would."""
    attempts = []
    for _ in range(tries):
        seq = last_gui_seq(clock)
        p.key("1")
        p.click(640, 300)
        got = gui_ack(clock, "um-place", seq)
        attempts.append(got and got["code"])
        if got and got["ok"]:
            return got, attempts
        p.hold("d", 0.35)
        time.sleep(0.5)
    return None, attempts


def play(clock: Clock, p: Player, checks: dict, shots: list, notes: dict):
    time.sleep(6)                 # let the session GUI finish loading
    shots.append(p.shot("0_rts_view"))

    # 1. Enter hero mode with a real click on the HUD button.
    p.click(640, 99)
    obs = wait_for(clock, lambda o: o["avatar"] and o["squad"])
    spawned = ack(obs, "gui-", "um-spawn")
    checks["hero_spawned_from_button_click"] = bool(obs["avatar"]) and bool(spawned and spawned["ok"])
    if not obs["avatar"]:
        shots.append(p.shot("1_no_hero"))
        raise RuntimeError("no hero after clicking Play as hero")
    checks["squad_recruited"] = len(obs["squad"]) >= 4
    notes["squad_size"] = len(obs["squad"])
    notes["hud_at_spawn"] = obs["hud"]
    checks["hud_reports_full_hearts"] = obs["hud"]["halfHearts"] == 20
    p.xdo("mousemove", "640", "300")
    shots.append(p.shot("1_first_person", 2.0))

    # 2. Walk with W (the squad follows behind), then turn with D.
    x0, z0 = obs["avatar"]["x"], obs["avatar"]["z"]
    p.hold("w", 2.5)
    obs = wait_for(clock, lambda o: math.dist((o["avatar"]["x"], o["avatar"]["z"]), (x0, z0)) > 3, 3)
    moved = math.dist((obs["avatar"]["x"], obs["avatar"]["z"]), (x0, z0))
    notes["walked_m"] = round(moved, 1)
    checks["w_walks_the_hero"] = moved > 3
    angle0 = obs["avatar"]["angle"]
    p.hold("d", 0.8)
    obs = wait_for(clock, lambda o: abs(o["avatar"]["angle"] - angle0) > 0.3, 4)
    notes["turn"] = dict(before=round(angle0, 3), after=round(obs["avatar"]["angle"], 3))
    checks["d_turns_the_hero"] = abs(obs["avatar"]["angle"] - angle0) > 0.3
    shots.append(p.shot("2_walked_and_turned", 1.0))

    # 3. Slot 1 and a left click: place a block where the hero looks.
    stone0, blocks0 = obs["resources"]["stone"], clock.js(BLOCKS)
    placed, attempts = place_like_a_player(clock, p)
    obs = clock.js(OBSERVE)
    notes["place"] = dict(ack=placed, attempts=attempts)
    checks["click_places_block"] = bool(placed) and clock.js(BLOCKS) == blocks0 + 1
    checks["block_costs_10_stone"] = obs["resources"]["stone"] == stone0 - 10
    checks["xp_rises_on_place"] = obs["hud"]["xp"] >= 1
    shots.append(p.shot("3_block_placed", 1.5))

    # 4. Slot 2 and a click: break it again.
    seq = last_gui_seq(clock)
    p.key("2")
    p.click(640, 300)
    broken = gui_ack(clock, "um-break", seq)
    obs = clock.js(OBSERVE)
    notes["break_ack"] = broken
    checks["click_breaks_block"] = bool(broken and broken["ok"]) and clock.js(BLOCKS) == blocks0
    checks["break_refunds_stone"] = obs["resources"]["stone"] == stone0
    shots.append(p.shot("4_block_broken", 1.0))

    # 5. Squad orders from the hotbar: box formation, then the horn (follow me) and a walk.
    p.key("6")
    p.click(640, 300)
    obs = wait_for(clock, lambda o: sum(s["inFormation"] for s in o["squad"]) >= 4)
    notes["formation_ack"] = ack(obs, "gui-", "um-squad")
    checks["slot6_puts_squad_in_box_formation"] = sum(s["inFormation"] for s in obs["squad"]) >= 4 and obs["formation"] == "box"
    p.key("v")
    shots.append(p.shot("5_box_formation_third_person", 2.5))
    p.key("v")
    p.key("5")
    p.click(640, 300)
    obs = wait_for(clock, lambda o: (ack(o, "gui-", "um-squad") or {}).get("code") == "following")
    checks["slot5_horn_orders_follow"] = (ack(obs, "gui-", "um-squad") or {}).get("code") == "following"
    x1, z1 = obs["avatar"]["x"], obs["avatar"]["z"]
    p.hold("w", 3.0)
    time.sleep(3.0)
    obs = clock.js(OBSERVE)
    hero = (obs["avatar"]["x"], obs["avatar"]["z"])
    spread = sorted(math.dist(hero, (s["x"], s["z"])) for s in obs["squad"])
    notes["follow"] = dict(hero_moved_m=round(math.dist(hero, (x1, z1)), 1), squad_median_distance_m=round(spread[len(spread) // 2], 1))
    checks["squad_keeps_up_with_hero"] = math.dist(hero, (x1, z1)) > 3 and spread[len(spread) // 2] < 25

    # 6. Sword: attack an enemy. Fixture: spawn an enemy spearman 14 m ahead of the hero.
    enemy = clock.js("""
        let a = Engine.QueryInterface(SYSTEM_ENTITY, IID_UmCrossover).Observe(1).avatar;
        let e = Engine.AddEntity("units/athen/infantry_spearman_b");
        Engine.QueryInterface(e, IID_Ownership).SetOwner(2);
        Engine.QueryInterface(e, IID_Position).JumpTo(a.x + Math.sin(a.angle) * 14, a.z + Math.cos(a.angle) * 14);
        return e;""")
    hp0 = clock.js(f"return Engine.QueryInterface({enemy}, IID_Health).GetHitpoints();")
    p.key("3")
    p.click(640, 300)
    hp = hp0
    for _ in range(40):
        time.sleep(0.25)
        hp = clock.js(f"let h = Engine.QueryInterface({enemy}, IID_Health); return h ? h.GetHitpoints() : 0;")
        if hp < hp0:
            break
    obs = clock.js(OBSERVE)
    notes["attack"] = dict(fixture_enemy=enemy, hp_before=hp0, hp_after=hp, ack=ack(obs, "gui-", "um-squad"))
    checks["slot3_sword_damages_enemy"] = hp < hp0
    shots.append(p.shot("6_attack", 0.5))

    # 7. Tab: the tactical map with the normal RTS panels; Tab again returns to the hero.
    p.key("Tab")
    shots.append(p.shot("7_tactical_map", 2.0))
    p.key("Tab")
    time.sleep(1.0)

    # 8. Persistence through 0 A.D.'s own savegame: place, quicksave, place again, quickload.
    kept, attempts = place_like_a_player(clock, p)
    obs = clock.js(OBSERVE)
    saved_blocks, saved_epoch = clock.js(BLOCKS), obs["epoch"]
    p.key("shift+F5")
    time.sleep(2.0)
    p.hold("d", 0.5)
    extra, attempts2 = place_like_a_player(clock, p)
    after_blocks = clock.js(BLOCKS)
    p.key("shift+F8")
    obs = wait_for(clock, lambda o: o["epoch"] == saved_epoch + 1)
    loaded_blocks = clock.js(BLOCKS)
    exists = lambda ent: bool(ent) and clock.js(f"return !!Engine.QueryInterface({ent}, IID_Position);")
    notes["persistence"] = dict(saved_block=kept and kept["entity"], unsaved_block=extra and extra["entity"],
                                saved_blocks=saved_blocks, blocks_after_extra_place=after_blocks,
                                blocks_after_quickload=loaded_blocks, epoch_before=saved_epoch, epoch_after=obs["epoch"],
                                attempts=[attempts, attempts2])
    checks["quickload_keeps_saved_block"] = bool(kept) and exists(kept["entity"]) and loaded_blocks == saved_blocks >= 1
    checks["quickload_drops_unsaved_block"] = bool(extra) and after_blocks == saved_blocks + 1 and not exists(extra["entity"])
    checks["quickload_starts_new_epoch"] = obs["epoch"] == saved_epoch + 1
    checks["hero_and_squad_survive_quickload"] = bool(obs["avatar"]) and len(obs["squad"]) >= 4
    shots.append(p.shot("8_after_quickload", 2.0))

    # 9. The same typed command from a script, and the envelope rules.
    envelope = dict(src="script", session=obs["session"], epoch=obs["epoch"])
    hero = obs["avatar"]
    seq, placed_cmd = 0, None
    for turn in range(8):         # try directions around the hero until a cell is free
        seq += 1
        angle = hero["angle"] + turn * math.pi / 4
        cmd = dict(type="um-place", seq=seq, x=hero["x"] + math.sin(angle) * 10, z=hero["z"] + math.cos(angle) * 10, **envelope)
        clock.send(cmd)
        got = wait_for(clock, lambda o: any(x["src"] == "script" and x["seq"] == seq for x in o["acks"]), 4)
        result = next(x for x in got["acks"] if x["src"] == "script" and x["seq"] == seq)
        if result["ok"]:
            placed_cmd = cmd
            break
    clock.send(placed_cmd or cmd)                                       # the same seq again
    clock.send(dict(cmd, seq=seq + 1, epoch=saved_epoch))               # an epoch from before the load
    obs = wait_for(clock, lambda o: any(x["src"] == "script" and x["seq"] == seq + 1 for x in o["acks"]), 4)
    script_acks = [dict(seq=x["seq"], ok=x["ok"], code=x["code"]) for x in obs["acks"] if x["src"] == "script"]
    notes["script_acks"] = script_acks
    checks["script_places_with_same_command"] = placed_cmd is not None
    checks["duplicate_seq_ignored"] = script_acks[-2]["code"] == "duplicate"
    checks["stale_epoch_refused"] = script_acks[-1]["code"] == "stale-epoch"
    notes["hud_final"] = obs["hud"]
    shots.append(p.shot("9_final", 1.0))


def write_report(a, out: Path, checks: dict, shots: list, notes: dict):
    log = (out / "game.log").read_text(errors="replace")
    umx = [json.loads(m) for m in re.findall(r"^UMX (\{.*\})$", log, re.M)]
    errors = [line for line in log.splitlines() if "ERROR" in line or "JavaScript error" in line][:20]
    report = dict(checks=checks, passed=sum(checks.values()), total=len(checks), notes=notes, shots=shots,
                  game_version=lab.game_version(Path(a.public)), umx_lines=len(umx), log_errors=errors)
    (out / "hero_report.json").write_text(json.dumps(report, indent=2))
    (out / "umx.jsonl").write_text("".join(json.dumps(x) + "\n" for x in umx))
    print(json.dumps(dict(checks=checks, passed=report["passed"], total=report["total"], log_errors=errors), indent=2))
    exit(0 if all(checks.values()) else 1)


if __name__ == "__main__":
    main()
