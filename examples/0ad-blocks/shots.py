#!/usr/bin/env python3
"""Screenshots and a short clip of the block test in the rendered game (not the headless run).

    python3 shots.py --public /usr/share/games/0ad/mods/public --out shots/          # needs an X display
    xvfb-run -s "-screen 0 1280x720x24" python3 shots.py --public ... --out shots/   # Linux without a screen

It starts 0 A.D. with graphics and the RL interface, on Arcadia, with two mods: um_blocks (the blocks) and a
throwaway um_blocks_shots mod written to the user mod folder. That second mod adds one GUI notification type
("um-camera") so the simulation can move or attach the camera; it is not part of um_blocks. Then it replays
the lab: open lane, wall placed, the detour, a block broken, the walk through the gap, and saves PNG frames
plus an animated GIF of each walk. Frames are captured from the X display with ImageMagick's `import`.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import time
from pathlib import Path

import blocks_lab as lab

CAMERA_MOD = "um_blocks_shots"
CAMERA_JS = """// Test-only helper from universal-modder's blocks_lab: lets the simulation point the camera.
g_NotificationsTypes["um-camera"] = function(notification, player)
{
	if (notification.follow)
		Engine.CameraFollow(notification.follow);
	else
	{
		Engine.CameraFollow(0);
		Engine.CameraMoveTo(notification.x, notification.z);
	}
};
"""


def camera_mod(mods: Path, public: Path):
    d = mods / CAMERA_MOD
    (d / "gui" / "session").mkdir(parents=True, exist_ok=True)
    (d / "mod.json").write_text(json.dumps(dict(
        name=CAMERA_MOD, version="0.1.0", label="Universal Modder camera helper (tests only)",
        description="Lets a test move the camera through a GUI notification.", dependencies=[f"0ad={lab.game_version(public)}"]), indent=2))
    (d / "gui" / "session" / "um_shots.js").write_text(CAMERA_JS)


def camera(g: lab.Game, x: float = 0, z: float = 0, follow: int = 0):
    g.js(f"""Engine.QueryInterface(SYSTEM_ENTITY, IID_GuiInterface).PushNotification(
        {{ type: "um-camera", players: [1], x: {x}, z: {z}, follow: {follow} }}); return 1;""")
    g.step()        # the GUI reads notifications on a simulation update


def snap(display: str, path: Path, settle: float):
    time.sleep(settle)     # let a software renderer draw a few frames after the last step
    subprocess.run(["import", "-display", display, "-window", "root", str(path)], check=True)


def filmed_walk(g, unit, start, goal, out: Path, name: str, display: str, every: int, settle: float) -> list[Path]:
    lab.teleport(g, unit, *start)
    camera(g, follow=unit)
    g.step({"type": "walk", "entities": [unit], "x": goal[0], "z": goal[1], "queued": False})
    frames = []
    for t in range(200):
        state = g.step()
        p = state["entities"][str(unit)]["position"]
        if t % every == 0:
            frames.append(out / f"{name}_{len(frames):02d}.png")
            snap(display, frames[-1], settle)
        if ((p[0] - goal[0]) ** 2 + (p[1] - goal[1]) ** 2) ** 0.5 < 1.5:
            break
    frames.append(out / f"{name}_{len(frames):02d}.png")
    snap(display, frames[-1], settle)
    return frames


def gif(frames: list[Path], dst: Path):
    if shutil.which("convert"):
        subprocess.run(["convert", "-delay", "40", "-loop", "0", *map(str, frames), "-resize", "640x", str(dst)], check=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--exe", default=shutil.which("pyrogenesis") or "/usr/games/pyrogenesis")
    ap.add_argument("--public", required=True)
    ap.add_argument("--mods", default=str(Path.home() / ".local/share/0ad/mods"))
    ap.add_argument("--port", type=int, default=6002)
    ap.add_argument("--out", default="shots")
    ap.add_argument("--zoom", default="60", help="camera distance (the game's default is 120)")
    ap.add_argument("--every", type=int, default=3, help="capture a walk frame every N turns")
    ap.add_argument("--settle", type=float, default=1.0, help="seconds to wait before each capture")
    a = ap.parse_args()
    display = os.environ.get("DISPLAY") or exit("no DISPLAY; run under xvfb-run or on a desktop")
    mods, out = Path(a.mods), Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    lab.install(mods, Path(a.public))
    camera_mod(mods, Path(a.public))

    g = lab.Game(a.exe, a.port, "arcadia", out / "game.log", mods=(lab.MOD, CAMERA_MOD), visual=True,
                 extra=["-nosound", f"-conf=view.zoom.default:{a.zoom}"])
    shots = {}
    try:
        for _ in range(120):          # the rendered game answers before the map's entities exist
            unit = lab.spearman(g)
            if unit:
                break
            time.sleep(1)
        start, goal, d, angle, base = lab.lane(g, unit)
        mid = ((start[0] + goal[0]) / 2, (start[1] + goal[1]) / 2)
        lab.teleport(g, unit, *start)
        camera(g, *mid)
        snap(display, out / "1_open_lane.png", a.settle * 3)

        cells = lab.wall_cells(start, d, angle)
        ids = [lab.place(g, *c) for c in cells]
        camera(g, *mid)
        snap(display, out / "2_wall_placed.png", a.settle * 3)
        walk_wall = filmed_walk(g, unit, start, goal, out, "3_detour", display, a.every, a.settle)
        gif(walk_wall, out / "3_detour.gif")

        g.js(f"Engine.DestroyEntity({ids[lab.WALL // 2]}); return 1;")
        lab.teleport(g, unit, *start)
        camera(g, *mid)
        snap(display, out / "4_block_broken.png", a.settle * 3)
        walk_gap = filmed_walk(g, unit, start, goal, out, "5_through_gap", display, a.every, a.settle)
        gif(walk_gap, out / "5_through_gap.gif")
        shots = dict(lane=dict(start=start, goal=goal, angle=angle), blocks=[list(c) for c in cells],
                     broken=lab.WALL // 2, detour_frames=len(walk_wall), gap_frames=len(walk_gap))
    finally:
        g.close()
    (out / "shots.json").write_text(json.dumps(shots, indent=2))
    print(json.dumps(shots, indent=2))


if __name__ == "__main__":
    main()
