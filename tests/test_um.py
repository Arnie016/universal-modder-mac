"""Offline tests for the pieces that don't need a game, a GPU or a fal key.

    uv run --with pytest pytest -q
"""
import json
import shutil
import struct
import subprocess
import sys
from pathlib import Path

import pytest
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from um import backup, fal, publish, scan, sprite, video  # noqa: E402


# --------------------------------------------------------------------------- scan

def make(root: Path, files: dict):
    for rel, data in files.items():
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data if isinstance(data, bytes) else data.encode())


def engine_of(tmp_path, files):
    make(tmp_path, files)
    hits, _ = scan.detect(scan.Index(tmp_path))
    return hits[0][0], hits[0][3]


def test_unity_mono_and_version(tmp_path):
    key, det = engine_of(tmp_path, {
        "UnityPlayer.dll": b"MZ", "Game_Data/Managed/Assembly-CSharp.dll": b"MZ",
        "Game_Data/globalgamemanagers": b"\0" * 20 + b"2022.3.21f1\0" + b"\0" * 100,
        "Game_Data/app.info": "Studio\nCoolGame",
    })
    assert key == "unity-mono"
    assert det["version"] == "2022.3.21f1" and det["product"] == "CoolGame"


def test_unity_il2cpp(tmp_path):
    key, _ = engine_of(tmp_path, {"UnityPlayer.dll": b"MZ", "GameAssembly.dll": b"MZ",
                                  "Game_Data/il2cpp_data/Metadata/global-metadata.dat": b"\xaf\x1b\xb1\xfa"})
    assert key == "unity-il2cpp"


def test_unreal_version_from_exe(tmp_path):
    exe = b"MZ" + b"\0" * 5000 + "++UE5+Release-5.3".encode("utf-16-le") + b"\0" * 100
    key, det = engine_of(tmp_path, {"Proj/Binaries/Win64/Proj-Win64-Shipping.exe": exe, "Proj/Content/Paks/Proj-Windows.pak": b"x",
                                    "Proj/Content/Paks/Proj-Windows.utoc": b"x"})
    assert key == "unreal" and det["engine_version"] == "UE5+Release-5.3" and det["iostore"]


def test_godot_pck(tmp_path):
    key, det = engine_of(tmp_path, {"game.exe": b"MZ", "game.pck": b"GDPC" + struct.pack("<4I", 2, 4, 2, 1)})
    assert key == "godot" and det["version"].startswith("4.2.1")


def test_gamemaker_and_rpgmaker(tmp_path):
    assert engine_of(tmp_path / "a", {"data.win": b"FORM\0\0\0\0GEN8\0\0\0\0\0\x11"})[0] == "gamemaker"
    assert engine_of(tmp_path / "b", {"www/js/rpg_core.js": "//", "Game.exe": b"MZ"})[0] == "rpgmaker-mvmz"


def test_managed_pe(tmp_path):
    # minimal PE32 with a CLR header directory entry
    pe = bytearray(1024)
    pe[0:2] = b"MZ"
    struct.pack_into("<I", pe, 0x3C, 0x80)
    pe[0x80:0x84] = b"PE\0\0"
    struct.pack_into("<H", pe, 0x84, 0x14C)
    opt = 0x80 + 24
    struct.pack_into("<H", pe, opt, 0x10B)
    struct.pack_into("<I", pe, opt + 96 + 14 * 8, 0x2000)
    p = tmp_path / "Game.exe"
    p.write_bytes(bytes(pe))
    assert scan.pe_info(p) == {"arch": "x86", "managed": True}


def test_vdf():
    d = scan._vdf('"AppState" { "appid" "105600" "name" "Terraria" "installdir" "Terraria" }')
    assert d["AppState"]["installdir"] == "Terraria"


@pytest.mark.parametrize("library_name,install_name,game_name", [
    ("SteamLibrary", "ExampleGame", "Example Game"),
    ("SteamLibrary", "ExampleGame", "Example Game\u2122"),
    ("SteamLibrary", "Jeu\u00e9", "Example Game"),
    ("Biblioth\u00e8que", "ExampleGame", "Example Game"),
])
def test_steam_games_utf8(tmp_path, monkeypatch, library_name, install_name, game_name):
    root = tmp_path / "Steam"
    library = tmp_path / library_name
    apps = library / "steamapps"
    game_path = apps / "common" / install_name
    game_path.mkdir(parents=True)
    (root / "steamapps").mkdir(parents=True)
    (root / "steamapps/libraryfolders.vdf").write_text(
        '"libraryfolders" { "0" { "path" "' + library.as_posix() + '" } }', encoding="utf-8",
    )
    (apps / "appmanifest_123.acf").write_text(
        f'"AppState" {{ "appid" "123" "name" "{game_name}" "installdir" "{install_name}" }}',
        encoding="utf-8",
    )
    monkeypatch.setattr(scan, "steam_roots", lambda: [root])

    # Emulate a non-UTF-8 Windows default on every test platform, using real files.
    original_read_text = Path.read_text

    def read_text(path, encoding=None, errors=None, **kwargs):
        return original_read_text(path, encoding=encoding or "cp1252", errors=errors, **kwargs)

    monkeypatch.setattr(Path, "read_text", read_text)

    assert scan.steam_games() == [{
        "store": "steam", "appid": "123", "name": game_name,
        "path": str(game_path), "workshop": None,
    }]

def test_known_game_longest_key_wins(tmp_path):
    # "grand theft auto v" is a substring of "grand theft auto v enhanced";
    # the more specific entry must win, not whichever lands first in the dict
    d = tmp_path / "Grand Theft Auto V Enhanced"
    d.mkdir()
    for i in range(6):
        (d / f"f{i}.txt").write_text("x")
    r = scan.scan(str(d))
    assert r["routes"][0]["route"] == scan.KNOWN["grand theft auto v enhanced"][0]


# --------------------------------------------------------------------------- sprite

def sprite_on_white(w=64, h=48):
    im = Image.new("RGBA", (w, h), (255, 255, 255, 255))
    for x in range(20, 40):
        for y in range(10, 30):
            im.putpixel((x, y), (200, 30, 30, 255))
    im.putpixel((30, 20), (255, 255, 255, 255))   # an interior white "eye" must survive
    return im


def test_cutout_keeps_interior_white():
    out = sprite.cutout(sprite_on_white())
    assert out.size == (20, 20)
    assert out.getpixel((10, 10))[3] == 255          # the interior white pixel is still opaque
    assert out.getpixel((0, 0))[:3] == (200, 30, 30)


def test_fit_and_hard_alpha():
    f = sprite.fit(sprite.cutout(sprite_on_white()), 10, 10, anchor="bottom")
    assert f.size == (10, 10) and f.getbbox()[3] == 10
    assert set(sprite.hard_alpha(f).getchannel("A").getdata()) <= {0, 255}


def test_sheet_slice_roundtrip():
    frames = [Image.new("RGBA", (8, 8), (i * 40, 0, 0, 255)) for i in range(5)]
    sh = sprite.sheet(frames, cols=3)
    assert sh.size == (24, 16)
    assert len(sprite.slice_sheet(sh, 8, 8)) == 5


@pytest.mark.parametrize("alpha", [1, 64, 128, 192, 254, 255])
@pytest.mark.parametrize("operation", ["fit", "sheet", "squash"])
def test_sprite_placement_preserves_rgba(alpha, operation):
    # Placing a frame on a transparent canvas must not apply its alpha twice.
    im = Image.new("RGBA", (8, 8), (200, 100, 50, alpha))
    if operation == "fit":
        out = sprite.fit(im, 8, 8)
    elif operation == "sheet":
        out = sprite.slice_sheet(sprite.sheet([im]), 8, 8)[0]
    else:
        out = sprite.simple_frames(im, n=1, kind="squash")[0]
    assert out.tobytes() == im.tobytes()


def test_team_mask():
    im = Image.new("RGBA", (4, 1), (0, 0, 0, 255))
    im.putpixel((0, 0), (20, 60, 240, 255))            # saturated blue -> player colour
    im.putpixel((1, 0), (200, 200, 200, 255))          # grey stays
    rgb, mask = sprite.team_mask(im)
    assert mask.getpixel((0, 0)) > 200 and mask.getpixel((1, 0)) == 0


def test_seamless_edges_match():
    import numpy as np
    ramp = np.tile(np.linspace(0, 255, 64)[None, :, None], (64, 1, 4)).astype(np.uint8)   # huge seam at the wrap
    ramp[..., 3] = 255
    out = np.asarray(sprite.seamless(Image.fromarray(ramp))).astype(int)
    before = np.abs(ramp[:, 0, :3].astype(int) - ramp[:, -1, :3].astype(int)).mean()
    after = np.abs(out[:, 0, :3] - out[:, -1, :3]).mean()
    assert before > 200 and after < 12


# --------------------------------------------------------------------------- fal (offline parts)

def test_kv_and_urls(tmp_path):
    assert fal._kv(["prompt=a cat", "num_images:=2", "flag:=true"]) == {"prompt": "a cat", "num_images": 2, "flag": True}
    res = {"images": [{"url": "https://v3.fal.media/a.png", "content_type": "image/png"}, {"url": "https://v3.fal.media/b.png"}],
           "mask_image": {"url": "https://v3.fal.media/m.png"}}
    assert [u for _, u, _ in fal._urls_in(res)] == ["https://v3.fal.media/a.png", "https://v3.fal.media/b.png", "https://v3.fal.media/m.png"]


# --------------------------------------------------------------------------- publish

def test_publish_check(tmp_path, capsys):
    # fixtures assembled at runtime so this file doesn't trip the toolkit's own publish check
    fake_key = "FAL" + "_KEY=" + "abcdefghijklmnopqrstuvwxyz0123"
    ghidra_name = "FUN" + "_00401000"
    make(tmp_path / "mod", {"src/Mod.cs": f"int {ghidra_name}();\n// " + "Decompiled with ILSpy", "README.md": "My mod, built with dnSpy notes",
                            "config.txt": fake_key})
    make(tmp_path / "game", {"data/big.bin": b"x" * 4096})
    (tmp_path / "mod" / "copied.bin").write_bytes(b"x" * 4096)
    assert publish.check(str(tmp_path / "mod"), str(tmp_path / "game")) == 1
    out = capsys.readouterr().out
    assert "game file copied verbatim" in out and "FAL_KEY assignment" in out and "Ghidra auto-name" in out
    normalized = out.replace("\\", "/")
    assert "decompiler header x1 in src/Mod.cs" in normalized and "README.md" not in normalized.split("decompiler header")[-1].split("\n")[0]


# --------------------------------------------------------------------------- video

@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="needs ffmpeg")
def test_compile_small_edl(tmp_path):
    for i, color in enumerate(["red", "blue"]):
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=s=640x360:d=3:r=30", "-f", "lavfi", "-i", "sine=f=440:d=3",
                        "-shortest", str(tmp_path / f"c{i}.mp4")], check=True)
    edl = {"size": [640, 360], "fps": 30, "bpm": 120, "beat_lock": True, "transition": {"type": "cut"},
           "segments": [{"clip": "c0.mp4", "in": 0, "beats": 4, "hook": "Hello"},
                        {"clip": "c1.mp4", "in": 0.5, "beats": 4, "title": "A title", "credit": "@someone", "transition": {"type": "fade", "duration": 0.3}},
                        {"card": {"title": "The end"}, "dur": 1.5}]}
    (tmp_path / "edl.json").write_text(json.dumps(edl))
    video.compile_edl(tmp_path / "edl.json", str(tmp_path / "out.mp4"))
    info = video.probe(tmp_path / "out.mp4")
    assert abs(info["duration"] - (2 + 2 + 1.5)) < 0.15 and info["audio"]


# --------------------------------------------------------------------------- knowledge base

from um import kb  # noqa: E402

REPO = Path(__file__).resolve().parents[1]


def test_repo_knowledge_is_valid():
    root = REPO / "knowledge"
    for p, _, _ in kb.notes(root):
        fails, _ = kb.check_note(p, root)
        assert not fails, (p, fails)
    idx, rows = kb.build_index(root)
    assert (root / "INDEX.md").read_text(encoding="utf-8") == idx, "run `um kb index`"
    assert len(rows) >= 7


def test_kb_new_check_search(tmp_path):
    import shutil
    root = tmp_path / "knowledge"
    root.mkdir()
    shutil.copy(REPO / "knowledge" / "TEMPLATE.md", root / "TEMPLATE.md")
    p = kb.new_note(root, "Hades II", "A new boon god", agent="Codex (gpt-6)", route="loader-api")
    fails, _ = kb.check_note(p, root)
    assert any("unfilled template text" in f for f in fails)          # a fresh scaffold must not pass
    good = p.read_text(encoding="utf-8")
    good = good.replace("FILL IN: exact build", "1.0.1 (Steam)").replace("anti_cheat: FILL IN", "anti_cheat: none")
    good = good.replace("> Two to four sentences: what you built", "> Added a boon god via a Lua mod loader")
    good = good.replace("The most valuable section. Numbered; each one symptom → cause → fix.", "")
    good = good.replace("1. **Symptom.** What you saw. **Cause:** what it really was. **Fix:** what worked.",
                        "1. **Boons never offered.** **Cause:** pool cached at load. **Fix:** register before the run starts.")
    p.write_text(good, encoding="utf-8")
    fails, _ = kb.check_note(p, root)
    assert not fails, fails
    res = kb.search(root, ["boon"])
    assert res and res[0]["path"].endswith("a-new-boon-god.md")
    assert kb.search(root, ["boon"], route="native-hook") == []


def test_kb_check_rejects_secrets_and_dumps(tmp_path):
    note = tmp_path / "n.md"
    code = "\n".join(f"int x{i} = {i};" for i in range(160))
    note.write_text("---\nkind: technique\ntitle: t\ntags: [x]\ndate: 2026-09-30\nagents: [a]\n---\n# t\n"
                    f"```c\n{code}\n```\n" + "FAL" + "_KEY=abcdefghijklmnopqrstuvwxyz0123\n")
    fails, _ = kb.check_note(note)
    assert any("code block" in f for f in fails) and any("FAL_KEY" in f for f in fails)


@pytest.mark.parametrize("url", ["https://github.com/alice/universal-modder.git", "https://github.com/alice/universal-modder",
                                 "git@github.com:alice/universal-modder.git", "ssh://git@github.com/alice/universal-modder.git"])
def test_pr_head_from_fork(url):
    # gh looks a bare --head branch up in the base repo; a PR from a fork needs "<owner>:<branch>"
    assert kb.pr_head("kb/a-b", url) == "alice:kb/a-b"


def test_pr_head_same_repo():
    assert kb.pr_head("kb/a-b", None) == "kb/a-b"


# --------------------------------------------------------------------------- backup

def test_backup_handles_pre_1980_timestamps(tmp_path, monkeypatch):
    import os
    monkeypatch.setattr(backup, "_root", lambda name: (tmp_path / "snaps" / name).mkdir(parents=True, exist_ok=True)
                        or tmp_path / "snaps" / name)
    src = tmp_path / "src"
    make(src, {"old.txt": "from 1970", "new.txt": "fresh"})
    os.utime(src / "old.txt", (0, 0))
    zp = backup.create(str(src), name="t")
    assert set(backup._manifest(zp)["files"]) == {"old.txt", "new.txt"}


def test_backup_diff_and_restore_round_trip(tmp_path, monkeypatch):
    monkeypatch.setattr(backup, "_root", lambda name: (tmp_path / "snaps" / name).mkdir(parents=True, exist_ok=True)
                        or tmp_path / "snaps" / name)
    src = tmp_path / "src"
    make(src, {"save.dat": "v1", "sub/cfg.ini": "a=1"})
    backup.create(str(src), name="t")
    (src / "save.dat").write_text("v2")
    (src / "sub" / "cfg.ini").unlink()
    make(src, {"extra.log": "new"})
    d = backup.diff("t", str(src))
    assert (d["changed"], d["removed"], d["added"]) == (["save.dat"], ["sub/cfg.ini"], ["extra.log"])
    with pytest.raises(SystemExit):  # no --yes: report only, touch nothing
        backup.restore("t", str(src))
    assert (src / "save.dat").read_text() == "v2"
    backup.restore("t", str(src), clean=True, yes=True)
    assert (src / "save.dat").read_text() == "v1"
    assert (src / "sub" / "cfg.ini").read_text() == "a=1"
    assert not (src / "extra.log").exists()
    assert backup.snapshots("t-pre-restore")  # the state before the restore was kept


# --------------------------------------------------------------------------- macOS port

def _fake_app(root, name, files, plist=None):
    import plistlib
    app = root / f"{name}.app"
    (app / "Contents" / "MacOS").mkdir(parents=True)
    with open(app / "Contents" / "Info.plist", "wb") as f:
        plistlib.dump(plist or {"CFBundleExecutable": name, "CFBundleIdentifier": f"com.test.{name.lower()}",
                                "LSApplicationCategoryType": "public.app-category.games"}, f)
    for rel, data in files.items():
        p = app / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
    return app


def test_macho_info_thin_and_fat(tmp_path):
    import struct
    from um.scan import macho_info
    thin = tmp_path / "thin"
    thin.write_bytes(b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x0100000C) + b"\0" * 64)
    assert macho_info(thin)["arch"] == "arm64" and not macho_info(thin)["rosetta"]
    fat = tmp_path / "fat"
    fat.write_bytes(b"\xca\xfe\xba\xbe" + struct.pack(">I", 2) + struct.pack(">I", 0x01000007) + b"\0" * 16
                    + struct.pack(">I", 0x0100000C) + b"\0" * 16)
    assert macho_info(fat)["arch"] == "x86_64+arm64"
    intel = tmp_path / "intel"
    intel.write_bytes(b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x01000007) + b"\0" * 64)
    assert macho_info(intel)["rosetta"]


def test_detect_mac_unity_bundle(tmp_path):
    from um.scan import Index, detect
    app = _fake_app(tmp_path, "Game", {
        "Contents/Frameworks/UnityPlayer.dylib": b"x",
        "Contents/Resources/Data/globalgamemanagers": b"x",
        "Contents/Resources/Data/Managed/Assembly-CSharp.dll": b"MZ",
    })
    hits, _ = detect(Index(app))
    assert hits[0][0] == "unity-mono"


def test_detect_mac_gamemaker_and_electron(tmp_path):
    from um.scan import Index, detect
    gm = _fake_app(tmp_path, "GM", {"Contents/Resources/game.ios": b"FORM\0\0\0\0GEN8" + b"\0" * 64})
    assert detect(Index(gm))[0][0][0] == "gamemaker"
    el = _fake_app(tmp_path, "El", {"Contents/Resources/app.asar": b"x"})
    assert detect(Index(el))[0][0][0] == "electron"


def test_detect_wine_wrapper(tmp_path):
    from um.scan import Index, detect
    app = _fake_app(tmp_path, "Port", {"Contents/SharedSupport/prefix/drive_c/Game/game.exe": b"MZ"})
    keys = [h[0] for h in detect(Index(app))[0]]
    assert "wine" in keys


def test_mac_keycodes_in_swift_source():
    # the Swift helper maps Windows VK hex so WinDrive scripts port over; keep the table and parser together
    src = (Path(__file__).resolve().parents[1] / "um" / "swift" / "umhelper.swift").read_text()
    for needle in ('"escape": 53', '0x1B: "escape"', "func keyCode", "SCRecordingOutput", "CGPreflightScreenCaptureAccess"):
        assert needle in src


def test_cli_registers_mac_group():
    from um.cli import GROUPS
    assert "mac" in GROUPS and "win" in GROUPS


# --------------------------------------------------------------------------- scout (offline: the network is stubbed)

from um import scout  # noqa: E402


def test_scout_repos_in_skips_github_pages():
    text = "see https://github.com/veloren/veloren and github.com/topics/mac plus https://github.com/a-b/c.d.git#readme"
    assert scout.repos_in(text) == ["veloren/veloren", "a-b/c.d"]


def test_scout_assess_dispositions():
    now = 1_790_000_000
    meta = dict(full_name="o/r", html_url="https://github.com/o/r", description="Fabric bridge for macOS",
                license=dict(spdx_id="MIT"), pushed_at="2026-10-01T00:00:00Z", topics=["mod-loader"])
    a = scout.assess(meta, "Runs on Apple Silicon. WebSocket bridge.", ["Package.swift"], ["tool-macos-arm64.dmg"], now)
    assert a["disposition"] == "BOUNDED_EXPERIMENT" and a["hooks"] and any("dmg" in m for m in a["mac_evidence"])
    prose_only = scout.assess(meta, "works on macOS", [], [], now)
    assert prose_only["disposition"] == "REFERENCE_ONLY"
    nothing = scout.assess(dict(meta, description="Windows mod"), "DirectX only", [], ["setup.exe"], now)
    assert nothing["disposition"] == "REJECTED"
    risky = scout.assess(meta, "macOS aimbot", ["Package.swift"], [], now)
    assert risky["disposition"] == "REJECTED" and any("aimbot" in f for f in risky["flags"])
    unlicensed = scout.assess(dict(meta, license=None), "macOS plugin", ["Package.swift"], [], now)
    assert unlicensed["disposition"] == "REFERENCE_ONLY" and any("licence" in f for f in unlicensed["flags"])


def test_scout_search_survives_a_dead_source(monkeypatch):
    def fake(url, headers=None):
        if "algolia" in url:
            raise OSError("offline")
        return {"items": [dict(full_name="o/r", html_url="https://github.com/o/r", owner=dict(login="o"),
                               pushed_at="2026-10-02T00:00:00Z", stargazers_count=5, description="mac mashup")]}
    monkeypatch.setattr(scout, "fetch_json", fake)
    r = scout.search("mac mashup", ("github", "hn", "nope"), 3)
    assert [x["repos"] for x in r["results"]] == [["o/r"]]
    assert "hn" in r["errors"] and "nope" in r["errors"]


def test_scout_x_post_via_oembed(monkeypatch):
    body = ('<blockquote><p>New Mac mod bridge <a href="https://github.com/o/r">github.com/o/r</a> '
            '<a href="https://twitter.com/hashtag/mac">#mac</a></p>&mdash; Someone (@s)</blockquote>')
    monkeypatch.setattr(scout, "fetch_json", lambda url, headers=None: dict(html=body, author_name="Someone"))
    monkeypatch.setattr(scout, "assess_repo", lambda r: dict(repo=r, disposition="REFERENCE_ONLY"))
    r = scout.resolve_post("https://x.com/s/status/123")
    assert r["kind"] == "x" and r["author"] == "Someone" and r["repos"] == ["o/r"]
    assert r["assessed"][0]["repo"] == "o/r" and "Mac mod bridge" in r["text"]


def test_scout_x_search_needs_a_token(monkeypatch):
    monkeypatch.delenv("X_BEARER_TOKEN", raising=False)
    r = scout.search("anything", ("x",), 3)
    assert r["results"] == [] and "X_BEARER_TOKEN" in r["errors"]["x"]


def test_scout_ledger_round_trip(tmp_path, monkeypatch):
    monkeypatch.setenv("UM_HOME", str(tmp_path))
    scout.log_finding("https://github.com/o/r", "REFERENCE_ONLY", "readable bridge protocol")
    with pytest.raises(ValueError):
        scout.log_finding("u", "MAYBE", "x")
    assert scout.findings()["count"] == 1


def test_scout_mcp_handshake_over_stdio():
    msgs = [dict(jsonrpc="2.0", id=1, method="initialize", params=dict(protocolVersion="2025-06-18", capabilities={},
                                                                       clientInfo=dict(name="t", version="0"))),
            dict(jsonrpc="2.0", method="notifications/initialized"),
            dict(jsonrpc="2.0", id=2, method="tools/list"),
            dict(jsonrpc="2.0", id=3, method="tools/call", params=dict(name="log_finding", arguments=dict(url="u", disposition="BAD", insight="i"))),
            dict(jsonrpc="2.0", id=4, method="nope")]
    root = Path(__file__).resolve().parents[1]
    p = subprocess.run([sys.executable, "-m", "um", "scout", "mcp"], input="\n".join(json.dumps(m) for m in msgs) + "\n",
                       capture_output=True, text=True, cwd=root, timeout=30)
    out = [json.loads(l) for l in p.stdout.splitlines()]
    assert [o["id"] for o in out] == [1, 2, 3, 4]
    assert out[0]["result"]["serverInfo"]["name"] == "mashup-scout"
    assert {t["name"] for t in out[1]["result"]["tools"]} >= {"scout_search", "assess_repo", "resolve_post", "installed_games"}
    assert out[2]["result"]["isError"] is True
    assert out[3]["error"]["code"] == -32601


def test_epic_manifests_on_mac(tmp_path, monkeypatch):
    man = tmp_path / "Manifests"
    game = tmp_path / "Cyberpunk2077"
    game.mkdir()
    make(man, {"x.item": json.dumps(dict(AppName="Ginger", DisplayName="Cyberpunk 2077", InstallLocation=str(game)))})
    monkeypatch.setattr(scan, "epic_manifest_dir", lambda: man)
    assert scan.epic_games() == [dict(store="epic", appid="Ginger", name="Cyberpunk 2077", path=str(game))]
