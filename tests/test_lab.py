"""Offline tests for `um lab`: the recipe format, the acceptance suite (against the adapter template, so no game is
needed), receipts and the MCP handshake. The real-game acceptance run is `um lab accept recipes/0ad-hero-squad`.

    uv run --with pytest pytest -q tests/test_lab.py
"""
import json
import shutil
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from um import lab  # noqa: E402

DEMO = ROOT / "recipes" / "templates" / "demo-counter"
HERO = ROOT / "recipes" / "0ad-hero-squad"
TEMPLATE = ROOT / "recipes" / "templates" / "adapter_template.py"


@pytest.fixture(autouse=True)
def lab_home(tmp_path, monkeypatch):
    monkeypatch.setenv("UM_HOME", str(tmp_path / "um-home"))
    return tmp_path / "um-home"


def demo_cmd():
    return [sys.executable, str(TEMPLATE)]


def copy_demo(tmp_path) -> Path:
    """The demo recipe and its adapter in a scratch folder, laid out like recipes/templates."""
    root = tmp_path / "recipes" / "templates"
    shutil.copytree(DEMO, root / "demo-counter")
    shutil.copy(TEMPLATE, root / "adapter_template.py")
    return root / "demo-counter"


# --------------------------------------------------------------------------- the format

def test_schema_subset():
    s = {"type": "object", "required": ["a"], "additionalProperties": False,
         "properties": {"a": {"type": "integer", "minimum": 1}, "b": {"enum": ["x", "y"]}}}
    assert lab.schema_errors({"a": 2, "b": "x"}, s) == []
    errs = lab.schema_errors({"a": 0, "b": "z", "c": 1}, s)
    assert any("must be >= 1" in e for e in errs) and any("one of" in e for e in errs) and any("'c'" in e for e in errs)
    assert lab.schema_errors({"a": True}, s)          # a bool is not an integer
    assert lab.schema_errors({}, s) == ["$: missing 'a'"]


def test_shipped_recipes_validate():
    for folder in (DEMO, HERO):
        r = lab.validate(folder)
        assert r["ok"], r["errors"]


def test_published_schema_matches_the_code():
    published = json.loads((ROOT / "recipes" / "umx-recipe-0.1.schema.json").read_text())
    assert published == lab.RECIPE_SCHEMA


def test_changed_artifact_is_caught(tmp_path):
    folder = copy_demo(tmp_path)
    (folder.parent / "adapter_template.py").write_text("# edited\n")
    r = lab.validate(folder)
    assert not r["ok"] and any("changed since it was recorded" in e for e in r["errors"])
    changes = lab.rehash(folder, write=True)
    assert changes and lab.validate(folder)["ok"]


def test_artifacts_cannot_leave_the_recipe(tmp_path):
    folder = copy_demo(tmp_path)
    recipe = json.loads((folder / "recipe.json").read_text())
    recipe["artifacts"][0]["path"] = "../../../../etc/hostname"
    (folder / "recipe.json").write_text(json.dumps(recipe))
    assert any("outside the recipe root" in e for e in lab.validate(folder)["errors"])


def test_wit_and_json_must_agree(tmp_path):
    folder = tmp_path / "hero"
    shutil.copytree(HERO, folder, ignore=shutil.ignore_patterns("fixtures"))
    shutil.copytree(HERO / "fixtures", folder / "fixtures")
    recipe = json.loads((folder / "recipe.json").read_text())
    recipe["root"] = str(HERO / recipe["root"])
    recipe["commands"]["um-place"]["properties"]["height"] = {"type": "number"}
    (folder / "recipe.json").write_text(json.dumps(recipe))
    errors = lab.validate(folder)["errors"]
    assert any("WIT: um-place fields differ" in e for e in errors), errors


def test_wit_parser_reads_cases_and_fields():
    cases = lab.wit_commands((HERO / "commands.wit").read_text())
    assert cases["um-place"] == {"x", "z", "block"}
    assert cases["um-squad"] == {"op", "radius", "x", "z", "target", "formation"}
    assert lab.kebab("timeMs") == "time-ms" and lab.kebab("line_closed") == "line-closed"


def test_bad_fixture_command_is_caught(tmp_path):
    folder = copy_demo(tmp_path)
    (folder / "fixtures" / "count-to-five.jsonl").write_text('{"send": {"type": "demo-add", "amount": 99}}\n')
    assert any("must be <= 10" in e for e in lab.validate(folder)["errors"])


def test_find_0ad_uses_the_usual_places(tmp_path, monkeypatch):
    app = tmp_path / "app"
    (app / "public").mkdir(parents=True)
    (app / "public" / "mod.json").write_text("{}")
    (app / "pyrogenesis").write_text("")
    missing = tmp_path / "missing"
    monkeypatch.setattr(lab, "ZEROAD_PLACES", [(str(missing / "pyrogenesis"), str(missing / "public")),
                                               (str(app / "pyrogenesis"), str(app / "public"))])
    assert lab.find_0ad() == (str(app / "pyrogenesis"), str(app / "public"))
    assert lab.find_0ad(public=str(app / "public")) == (str(app / "pyrogenesis"), str(app / "public"))
    assert lab.find_0ad(exe="/opt/pyrogenesis") == ("/opt/pyrogenesis", str(app / "public"))
    with pytest.raises(lab.LabError, match="not found"):
        lab.find_0ad(public=str(missing / "public"))


def test_recipe_commands_need_permission():
    recipe, folder = lab.load(DEMO)
    with pytest.raises(lab.LabError, match="--allow-recipe-cmd"):
        lab.adapter_command(recipe, folder)
    assert lab.adapter_command(recipe, folder, allow_recipe_cmd=True)[-1].endswith("adapter_template.py")


# --------------------------------------------------------------------------- the acceptance suite

def test_template_passes_acceptance(tmp_path):
    r = lab.accept(DEMO, demo_cmd(), tmp_path / "run")
    rec = r["receipt"]
    assert rec["all_passed"], rec["checks"]
    assert rec["total"] == 14 and rec["checks"]["replay_is_identical:count-to-five"]
    assert (tmp_path / "run" / "transcript.jsonl").stat().st_size > 0
    assert not lab.leaks(rec)


BROKEN = '''
import sys
sys.path.insert(0, {folder!r})
import adapter_template as t

class World(t.World):
    def admit(self, cmd):
        verdict = super().admit(cmd)
        return dict(ok=True, gap=False) if verdict.get("code") == "{let_through}" else verdict

t.World = World
t.serve(t.Adapter())
'''


@pytest.mark.parametrize("let_through,check", [
    ("duplicate", "resend_is_ignored_as_duplicate"),
    ("stale-session", "other_session_is_refused"),
    ("stale-epoch", "other_epoch_is_refused"),
])
def test_acceptance_catches_a_broken_adapter(tmp_path, let_through, check):
    script = tmp_path / "broken.py"
    script.write_text(BROKEN.format(folder=str(TEMPLATE.parent), let_through=let_through))
    rec = lab.accept(DEMO, [sys.executable, str(script)], tmp_path / "run", replay=False)["receipt"]
    assert not rec["all_passed"] and rec["checks"][check] is False


def test_limits_and_types_are_enforced(tmp_path):
    exp = lab.Experiment(DEMO, demo_cmd(), tmp_path / "run")
    try:
        exp.start()
        with pytest.raises(lab.LabError, match="turns must be"):
            exp.step(51)
        with pytest.raises(lab.LabError, match="unknown command"):
            exp.send([{"type": "delete-everything"}])
        with pytest.raises(lab.LabError, match="must be <= 10"):
            exp.send([{"type": "demo-add", "amount": 11}])
        with pytest.raises(lab.LabError, match="at most 16"):
            exp.send([{"type": "demo-add", "amount": 1}] * 17)
        t = exp.observe()["timeMs"]
        assert exp.observe()["timeMs"] == t              # nothing moves between steps
    finally:
        exp.stop()


def test_fixture_expectations():
    assert lab.expect({"delta": -10}, 290, 300) == ""
    assert "changed by" in lab.expect({"delta": -10}, 300, 300)
    assert lab.expect({">=": 4}, 4) == "" and lab.expect({">=": 4}, 3)
    assert lab.expect("box", "box") == "" and lab.expect(True, False)
    obs = {"avatar": {"x": 10.0, "z": 20.0, "angle": 0.0}}
    assert lab.hero_point(obs, {"ahead_m": 12}) == (10.0, 32.0)
    assert lab.hero_point(obs, {"right_m": 5}) == (15.0, 20.0)     # right of +z is +x
    assert lab.dig({"squad": [{"hp": 3}]}, "squad.0.hp") == 3 and lab.dig({"squad": [1, 2]}, "squad.length") == 2


# --------------------------------------------------------------------------- receipts and metrics

def test_sanitize_strips_paths_names_and_keys():
    # the paths are split so `um publish check` does not take them for real user paths
    dirty = {"a": "/Users" + "/someone/Library/x", "b": "/home" + "/someone/game.log", "c": "key sk-abcdefghijklmnopqrstuv",
             "d": "mail me@example.com", "e": str(Path.home() / "secret"), "f": "token=abcdef123456"}
    clean = lab.sanitize(dirty)
    assert not lab.leaks(clean), clean
    assert "someone" not in json.dumps(clean)


def test_metrics_count_receipts(lab_home, tmp_path):
    def rec(machine, date, ok=True, rid="r1"):
        return dict(format=lab.RECEIPT_FORMAT, recipe=dict(id=rid, version="0.1.0"), machine=machine, date=date,
                    all_passed=ok, platform="linux-x86_64", transcript_sha256=f"{machine}{date}{ok}{rid}")
    folder = tmp_path / "receipts"
    folder.mkdir()
    for i, r in enumerate([rec("m1", "2026-10-12"), rec("m1", "2026-10-13"), rec("m2", "2026-10-12"),
                           rec("m3", "2026-10-12", ok=False), rec("m1", "2026-10-12", rid="r2", ok=False)]):
        (folder / f"{i}.json").write_text(json.dumps(r))
    m = lab.metrics([str(folder)])
    assert m["completed_remixes"] == 1                 # r2 never passed
    assert m["independent_reproductions"] == 1         # m2 reproduced m1's pass; m3 failed
    assert m["repeat_usage"] == 1                      # m1 came back on another day
    assert m["receipts_read"] == 5


def test_machine_id_is_stable_and_not_personal(lab_home):
    a, b = lab.machine_id(), lab.machine_id()
    assert a == b and len(a) == 16
    assert (lab_home / "lab" / "machine-id").read_text().strip() not in a


# --------------------------------------------------------------------------- MCP

def rpc(server, method, params=None, mid=1):
    return server.handle(dict(jsonrpc="2.0", id=mid, method=method, params=params or {}))


def test_mcp_modern_and_legacy_handshakes():
    s = lab.LabServer(ROOT / "recipes", [])
    d = rpc(s, "server/discover", {"_meta": {lab.VERSION_KEY: "2026-07-28"}})["result"]
    assert d["supportedVersions"][0] == "2026-07-28" and d["resultType"] == "complete"
    assert d["ttlMs"] == 0 and d["cacheScope"] == "private" and d["capabilities"]["tools"] == {"listChanged": False}
    assert d["_meta"]["io.modelcontextprotocol/serverInfo"]["name"] == "umx-lab"
    i = rpc(s, "initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "t"}})
    assert i["result"]["protocolVersion"] == "2025-06-18"
    e = rpc(s, "tools/list", {"_meta": {lab.VERSION_KEY: "2099-01-01"}})
    assert e["error"]["code"] == -32022 and "2026-07-28" in e["error"]["data"]["supported"]
    assert s.handle(dict(jsonrpc="2.0", method="notifications/initialized")) is None


def test_mcp_tools():
    s = lab.LabServer(ROOT / "recipes", [])
    tools = rpc(s, "tools/list")["result"]
    names = [t["name"] for t in tools["tools"]]
    assert names == ["lab_capabilities", "lab_validate", "lab_start", "lab_send", "lab_step", "lab_observe",
                     "lab_replay", "lab_export", "lab_stop"]
    assert tools["resultType"] == "complete"
    r = rpc(s, "tools/call", {"name": "lab_validate", "arguments": {"recipe": "demo-counter"}})["result"]
    assert r["isError"] is False and r["structuredContent"]["ok"] is True
    r = rpc(s, "tools/call", {"name": "lab_send", "arguments": {"commands": [{"type": "demo-add", "amount": 1}]}})
    assert r["result"]["isError"] is True and "lab_start" in r["result"]["structuredContent"]["error"]
    r = rpc(s, "tools/call", {"name": "lab_validate", "arguments": {"recipe": "../../etc"}})["result"]
    assert r["isError"] is True                        # recipes are ids, not paths
    assert rpc(s, "tools/call", {"name": "nope"})["error"]["code"] == -32602


def test_mcp_runs_an_experiment_with_the_template(tmp_path):
    s = lab.LabServer(ROOT / "recipes", [], adapter_cmd=" ".join([sys.executable, str(TEMPLATE)]))

    def call(name, **args):
        r = rpc(s, "tools/call", {"name": name, "arguments": args})["result"]
        assert not r["isError"], r["structuredContent"]
        return r["structuredContent"]
    started = call("lab_start", recipe="demo-counter")
    assert started["session"] and started["epoch"] == 0
    call("lab_send", commands=[{"type": "demo-add", "amount": 3}])
    acks = call("lab_step", turns=2)["acks"]
    assert acks[0]["code"] == "added" and acks[0]["src"] == "mcp"
    assert call("lab_observe")["counter"] == 3
    assert call("lab_replay", fixture="count-to-five")["ok"]
    assert call("lab_stop")["stopped"]
