"""Package a gameplay mechanic as a recipe, test it in the real game through an adapter, and share receipts.

    um lab validate recipes/0ad-hero-squad        # format, file hashes, licences, commands vs WIT, fixtures
    um lab hash recipes/0ad-hero-squad --write    # after editing the mechanic's files: record their new sha256
    um lab accept recipes/0ad-hero-squad          # acceptance: envelope rules, restart, every fixture, replay
    um lab run recipes/0ad-hero-squad hero-blocks-squad   # one bounded experiment on one fixture
    um lab receipt <run folder>                   # the sanitized receipt to share: no paths, names or keys
    um lab metrics [--from DIR]                   # completed remixes, independent reproductions, repeat usage
    um lab mcp                                    # the same operations for agents (Model Context Protocol, stdio)
    um lab adapter 0ad                            # the 0 A.D. adapter process (the lab starts it for you)
    um lab schema                                 # the recipe format as JSON Schema

The 0 A.D. commands find the game in the usual places (the Debian/Ubuntu package, /Applications or
~/Applications). Elsewhere, pass --public <the game's mods/public folder> and --exe <pyrogenesis>.

A recipe (recipes/<id>/recipe.json, format umx-recipe/0.1) is one gameplay mechanic packaged so someone else can
run it: provenance and licences, the exact files it ships (sha256), typed commands and observation (JSON Schema,
mirrored in a WIT file), units, timing, the entities and effects it owns, limits, replay fixtures, a playbook and
evidence. recipes/README.md describes every field.

An adapter connects one game to the lab. It is a process that reads one JSON request per line on stdin and
answers one JSON line on stdout (umx-adapter/0.1: hello, start, send, step, observe, stop). The lab owns the
clock: the game advances only when the lab asks for turns, so an agent is never inside the frame loop. Built in:
`0ad` (runs the original engine headless through its RL interface). recipes/templates/adapter_template.py is a
complete adapter for a demo counter, to copy for a new game.

Runs write their evidence (transcript.jsonl, receipt.json, game log) to ~/.universal-modder/lab/runs/.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import platform
import queue
import re
import secrets
import shlex
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
from collections import defaultdict
from pathlib import Path

from um import __version__
from um.common import data_dir, die, emit

FORMAT = "umx-recipe/0.1"
ADAPTER_PROTOCOL = "umx-adapter/0.1"
RECEIPT_FORMAT = "umx-receipt/0.1"
ENVELOPE = ("src", "seq", "session", "epoch")
REFUSALS = ("stale-session", "stale-epoch", "bad-envelope", "duplicate")
REPO = Path(__file__).resolve().parent.parent
BUILTIN_ADAPTERS = ("0ad",)


class LabError(Exception):
    """A request the lab refuses (a limit, an unknown command, a dead game). The message says why."""


# --------------------------------------------------------------------------- JSON Schema (the subset recipes use)

def _is(value, kind: str) -> bool:
    if kind == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if kind == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    return {"object": dict, "array": list, "string": str, "boolean": bool, "null": type(None)}.get(kind) is type(value)


def schema_errors(value, schema: dict, where: str = "$") -> list[str]:
    """Check `value` against the JSON Schema keywords recipes use: type, const, enum, required, properties,
    additionalProperties, items, minimum, maximum, minLength, minItems, pattern. Returns one line per problem."""
    kinds = schema.get("type")
    if kinds is not None:
        kinds = kinds if isinstance(kinds, list) else [kinds]
        if not any(_is(value, k) for k in kinds):
            return [f"{where}: expected {' or '.join(kinds)}, got {type(value).__name__}"]
    errs = []
    if "const" in schema and value != schema["const"]:
        errs.append(f"{where}: must be {schema['const']!r}")
    if "enum" in schema and value not in schema["enum"]:
        errs.append(f"{where}: must be one of {schema['enum']}")
    if _is(value, "number"):
        if "minimum" in schema and value < schema["minimum"]:
            errs.append(f"{where}: must be >= {schema['minimum']}")
        if "maximum" in schema and value > schema["maximum"]:
            errs.append(f"{where}: must be <= {schema['maximum']}")
    if isinstance(value, str):
        if len(value) < schema.get("minLength", 0):
            errs.append(f"{where}: must not be empty")
        if "pattern" in schema and not re.search(schema["pattern"], value):
            errs.append(f"{where}: must match {schema['pattern']}")
    if isinstance(value, dict):
        errs += [f"{where}: missing {k!r}" for k in schema.get("required", []) if k not in value]
        props, extra = schema.get("properties", {}), schema.get("additionalProperties", True)
        for k, v in value.items():
            if k in props:
                errs += schema_errors(v, props[k], f"{where}.{k}")
            elif extra is False:
                errs.append(f"{where}: unexpected key {k!r}")
            elif isinstance(extra, dict):
                errs += schema_errors(v, extra, f"{where}.{k}")
    if isinstance(value, list):
        if len(value) < schema.get("minItems", 0):
            errs.append(f"{where}: needs at least {schema['minItems']} item(s)")
        if "items" in schema:
            for i, v in enumerate(value):
                errs += schema_errors(v, schema["items"], f"{where}[{i}]")
    return errs


_TEXT = {"type": "string", "minLength": 1}
_LIMIT = {"type": "integer", "minimum": 1}
RECIPE_SCHEMA = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "title": "Universal Modder recipe (umx-recipe/0.1)",
    "type": "object",
    "required": ["format", "id", "version", "title", "summary", "engine", "profile", "provenance", "licenses",
                 "artifacts", "units", "timing", "envelope", "commands", "observation", "ownership",
                 "owned_entities", "effects", "limits", "fixtures", "acceptance", "playbook", "evidence"],
    "properties": {
        "format": {"const": FORMAT},
        "id": {"type": "string", "pattern": "^[a-z0-9][a-z0-9-]*$"},
        "version": {"type": "string", "pattern": r"^\d+\.\d+\.\d+$"},
        "title": _TEXT,
        "summary": _TEXT,
        "status": {"type": "object"},
        "root": {"type": "string"},
        "engine": {"type": "object", "required": ["game", "versions", "interfaces"], "properties": {
            "game": _TEXT,
            "adapter": {"type": "string"},
            "adapter_cmd": {"type": "array", "items": {"type": "string"}},
            "versions": {"type": "array", "minItems": 1, "items": {"type": "string"}},
            "mod": {"type": "string"},
            "scenario": {"type": "string"},
            "player": {"type": "integer"},
            "interfaces": {"type": "array", "minItems": 1, "items": {
                "type": "object", "required": ["name", "use", "evidence"]}}}},
        "profile": {"type": "object", "required": ["kind", "sandbox", "why"], "properties": {
            "kind": {"enum": ["native-worker", "wasm-restricted", "reference"]}, "sandbox": _TEXT, "why": _TEXT}},
        "provenance": {"type": "object", "required": ["upstream", "reused", "new"], "properties": {
            "upstream": {"type": "array", "items": {"type": "object",
                                                    "required": ["name", "version", "url", "license", "use", "shipped"]}},
            "reused": {"type": "array", "items": {"type": "string"}},
            "new": {"type": "array", "minItems": 1, "items": {"type": "string"}}}},
        "licenses": {"type": "object", "required": ["recipe", "code", "assets"]},
        "artifacts": {"type": "array", "minItems": 1, "items": {"type": "object", "required": ["path", "sha256", "role"],
                      "properties": {"path": _TEXT, "sha256": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
                                     "role": _TEXT}}},
        "units": {"type": "object", "required": ["length", "angle", "time"]},
        "timing": {"type": "object", "required": ["turn_ms", "clock"], "properties": {
            "turn_ms": {"type": "integer", "minimum": 1}, "clock": _TEXT}},
        "envelope": {"type": "object", "required": ["fields", "refusals", "resume"]},
        "commands": {"type": "object", "additionalProperties": {"type": "object", "required": ["type", "properties"]}},
        "observation": {"type": "object", "required": ["type", "required", "properties"]},
        "ownership": {"type": "object", "required": ["movement", "collision", "health", "inventory", "persistence"]},
        "owned_entities": {"type": "array", "items": {"type": "object", "required": ["template", "owner", "max"]}},
        "effects": {"type": "array", "minItems": 1, "items": {"type": "object", "required": ["command", "does"]}},
        "limits": {"type": "object", "required": ["max_turns_per_step", "max_total_turns", "max_commands_per_send",
                                                  "max_wall_seconds"],
                   "properties": {"max_turns_per_step": _LIMIT, "max_total_turns": _LIMIT,
                                  "max_commands_per_send": _LIMIT, "max_wall_seconds": _LIMIT}},
        "fixtures": {"type": "array", "minItems": 1, "items": {"type": "object", "required": ["name", "file", "about"]}},
        "acceptance": {"type": "object", "required": ["probe", "fixtures"]},
        "playbook": _TEXT,
        "wit": {"type": "string"},
        "evidence": {"type": "array", "items": {"type": "object", "required": ["date", "kind", "result"]}},
    },
}

FIXTURE_LINE = {"type": "object", "additionalProperties": False, "properties": {
    "note": {"type": "string"}, "send": {"type": ["object", "array"]}, "turns": {"type": "integer", "minimum": 0},
    "as": {"type": "string"}, "expect": {"type": "object", "additionalProperties": False, "properties": {
        "ack": {"type": "object"}, "state": {"type": "object"}}}}}


# --------------------------------------------------------------------------- recipes

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def load(recipe: str | Path) -> tuple[dict, Path]:
    """(recipe dict, recipe folder) from a folder or its recipe.json."""
    p = Path(recipe)
    f = p / "recipe.json" if p.is_dir() else p
    if not f.is_file():
        raise LabError(f"no recipe.json at {p}")
    return json.loads(f.read_text(encoding="utf-8")), f.parent


def artifact_root(recipe: dict, folder: Path) -> Path:
    """Artifact paths are relative to `root` (relative to the recipe folder). They may point anywhere in the project
    that holds the `recipes` folder, and nowhere else (a recipe outside any `recipes` folder: only inside itself)."""
    folder = folder.resolve()
    fence = next((p.parent for p in (folder, *folder.parents) if p.name == "recipes"), folder)
    base = (folder / recipe.get("root", ".")).resolve()
    if not base.is_relative_to(fence):
        raise LabError(f"root {recipe.get('root')!r} leaves {fence.name or fence}")
    return base


def artifact_path(recipe: dict, folder: Path, rel: str) -> Path:
    base = artifact_root(recipe, folder)
    p = (base / rel).resolve()
    if not p.is_relative_to(base):
        raise LabError(f"artifact {rel!r} is outside the recipe root")
    return p


def recipe_digest(recipe: dict) -> str:
    """The recipe's identity in receipts: everything but its evidence list, so adding evidence does not change it."""
    body = {k: v for k, v in recipe.items() if k != "evidence"}
    return hashlib.sha256(json.dumps(body, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def rehash(recipe_path: str | Path, write: bool = False) -> list[dict]:
    recipe, folder = load(recipe_path)
    changes = []
    for a in recipe["artifacts"]:
        new = sha256(artifact_path(recipe, folder, a["path"]))
        if new != a["sha256"]:
            changes.append(dict(path=a["path"], old=a["sha256"], new=new))
            a["sha256"] = new
    if write and changes:
        (folder / "recipe.json").write_text(json.dumps(recipe, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return changes


def kebab(name: str) -> str:
    return re.sub(r"(?<=[a-z0-9])([A-Z])", r"-\1", name).replace("_", "-").lower()


def wit_commands(text: str) -> dict[str, set[str]]:
    """{case name: record field names} for `variant command` in a WIT file (kebab-case names)."""
    text = re.sub(r"//[^\n]*", "", text)
    records = {m.group(1): {f.split(":")[0].strip().lstrip("%") for f in m.group(2).split(",") if ":" in f}
               for m in re.finditer(r"record\s+([\w-]+)\s*\{([^}]*)\}", text)}
    variant = re.search(r"variant\s+command\s*\{([^}]*)\}", text)
    cases = {}
    for case in (variant.group(1).split(",") if variant else []):
        m = re.match(r"\s*%?([\w-]+)\s*(?:\(\s*([\w-]+)\s*\))?", case)
        if m and m.group(1):
            cases[m.group(1)] = records.get(m.group(2), set()) if m.group(2) else set()
    return cases


def read_fixture(recipe: dict, folder: Path, name: str) -> list[dict]:
    meta = next((f for f in recipe["fixtures"] if f["name"] == name), None)
    if not meta:
        raise LabError(f"no fixture {name!r}; this recipe has {[f['name'] for f in recipe['fixtures']]}")
    lines = (folder / meta["file"]).read_text(encoding="utf-8").splitlines()
    return [json.loads(line) for line in lines if line.strip()]


def _probe_payload(cmd: dict, schema: dict) -> dict:
    """A fixture command with its lab-side placeholders filled with dummies, so its fields can be type-checked."""
    out = {k: (1 if isinstance(v, dict) and "$ref" in v else v) for k, v in cmd.items() if k != "at"}
    if "at" in cmd:
        out.update({k: 0.0 for k in ("x", "z") if k in schema.get("properties", {})})
    return out


def validate(recipe_path: str | Path) -> dict:
    """Everything that can be checked without running the game."""
    errors, warnings, notes = [], [], []
    try:
        recipe, folder = load(recipe_path)
    except (LabError, json.JSONDecodeError) as e:
        return dict(ok=False, errors=[str(e)], warnings=[], notes=[])
    errors += schema_errors(recipe, RECIPE_SCHEMA)
    if errors:
        return dict(ok=False, recipe=recipe.get("id"), errors=errors, warnings=warnings, notes=notes)

    for a in recipe["artifacts"]:
        try:
            p = artifact_path(recipe, folder, a["path"])
        except LabError as e:
            errors.append(str(e))
            continue
        if not p.is_file():
            errors.append(f"artifact missing: {a['path']}")
        elif sha256(p) != a["sha256"]:
            errors.append(f"artifact changed since it was recorded: {a['path']} (run `um lab hash --write` "
                          f"if you meant to change it, then re-run the evidence)")
    notes.append(f"{len(recipe['artifacts'])} artifact hashes checked")

    commands = recipe["commands"]
    if not commands:
        errors.append("commands: a recipe needs at least one command")
    for name, schema in commands.items():
        props = schema.get("properties", {})
        if props.get("type") != {"const": name}:
            errors.append(f"commands.{name}: properties.type must be {{\"const\": \"{name}\"}}")
        if schema.get("additionalProperties") is not False:
            warnings.append(f"commands.{name}: additionalProperties is not false, so typos pass")
        for k in ENVELOPE:
            if k in props:
                errors.append(f"commands.{name}: {k!r} belongs to the envelope, which the lab adds")

    if recipe.get("wit"):
        wit_file = folder / recipe["wit"]
        if not wit_file.is_file():
            errors.append(f"wit file missing: {recipe['wit']}")
        else:
            cases = wit_commands(wit_file.read_text(encoding="utf-8"))
            for name, schema in commands.items():
                fields = {kebab(k) for k in schema.get("properties", {}) if k != "type"}
                if name not in cases:
                    errors.append(f"WIT: variant command has no case {name}")
                elif fields != cases[name]:
                    errors.append(f"WIT: {name} fields differ: JSON {sorted(fields)} vs WIT {sorted(cases[name])}")
            for extra in sorted(set(cases) - set(commands)):
                errors.append(f"WIT: case {extra} has no JSON command")
            tool = shutil.which("wasm-tools")
            if tool:
                r = subprocess.run([tool, "component", "wit", str(wit_file)], capture_output=True, text=True)
                ver = subprocess.run([tool, "--version"], capture_output=True, text=True).stdout.strip()
                if r.returncode:
                    errors.append(f"WIT does not parse ({ver}): {r.stderr.strip()[:400]}")
                else:
                    notes.append(f"WIT parsed by {ver}")
            else:
                warnings.append("wasm-tools is not installed, so the WIT file was only cross-checked, not parsed")

    probe = recipe["acceptance"].get("probe", {})
    if probe.get("type") not in commands:
        errors.append(f"acceptance.probe: {probe.get('type')!r} is not one of the recipe's commands")
    names = [f["name"] for f in recipe["fixtures"]]
    for name in recipe["acceptance"].get("fixtures", []):
        if name not in names:
            errors.append(f"acceptance.fixtures: no fixture {name!r}")
    for meta in recipe["fixtures"]:
        f = folder / meta["file"]
        if not f.is_file():
            errors.append(f"fixture missing: {meta['file']}")
            continue
        for i, raw in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            if not raw.strip():
                continue
            where = f"{meta['file']}:{i}"
            try:
                line = json.loads(raw)
            except json.JSONDecodeError as e:
                errors.append(f"{where}: not JSON ({e})")
                continue
            errors += [f"{where}: {e}" for e in schema_errors(line, FIXTURE_LINE)]
            sends = line.get("send", [])
            for cmd in sends if isinstance(sends, list) else [sends]:
                schema = commands.get(cmd.get("type"))
                if not schema:
                    errors.append(f"{where}: unknown command {cmd.get('type')!r}")
                else:
                    errors += [f"{where}: {e}" for e in schema_errors(_probe_payload(cmd, schema), schema, cmd["type"])]

    for key in ("playbook",):
        if not (folder / recipe[key]).is_file():
            errors.append(f"{key} missing: {recipe[key]}")
    for ev in recipe["evidence"]:
        if ev.get("file"):
            p = folder / ev["file"]
            if not p.is_file():
                errors.append(f"evidence file missing: {ev['file']}")
            elif ev.get("sha256") and sha256(p) != ev["sha256"]:
                errors.append(f"evidence file changed: {ev['file']}")
            elif p.suffix == ".json":
                rec = json.loads(p.read_text(encoding="utf-8"))
                if rec.get("format") == RECEIPT_FORMAT and rec["recipe"].get("digest") != recipe_digest(recipe):
                    warnings.append(f"{ev['file']} was made with an earlier version of this recipe")
    for up in recipe["provenance"]["upstream"]:
        if not isinstance(up.get("license"), (str, dict)) or not up.get("license"):
            errors.append(f"provenance: {up.get('name')} needs a licence")
    if not recipe["evidence"]:
        warnings.append("no evidence yet: run `um lab accept` and add its receipt")
    return dict(ok=not errors, recipe=recipe["id"], version=recipe["version"], errors=errors, warnings=warnings,
                notes=notes)


def find_recipes(root: Path = REPO / "recipes") -> list[dict]:
    out = []
    for f in sorted(root.rglob("recipe.json")):
        try:
            r = json.loads(f.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        if r.get("format") == FORMAT:
            out.append(dict(id=r["id"], version=r["version"], title=r["title"], folder=f.parent,
                            commands=list(r.get("commands", {})), adapter=r.get("engine", {}).get("adapter"),
                            profile=r.get("profile", {}).get("kind")))
    return out


# --------------------------------------------------------------------------- adapter processes

class Adapter:
    """One adapter process: a JSON request per line on its stdin, a JSON answer per line on its stdout."""

    def __init__(self, cmd: list[str], log: list, stderr_path: Path, timeout: float = 120.0):
        self.cmd, self.log, self.timeout = cmd, log, timeout
        self.t0 = time.monotonic()
        self.stderr = open(stderr_path, "a", encoding="utf-8")
        self.proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.stderr,
                                     text=True, bufsize=1, env=_child_env())
        self.lines: queue.Queue = queue.Queue()
        self.n = 0
        self.lock = threading.Lock()
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for line in self.proc.stdout:
            self.lines.put(line)
        self.lines.put(None)

    def call(self, op: str, timeout: float | None = None, **params) -> dict:
        with self.lock:
            self.n += 1
            req = dict(id=self.n, op=op, **params)
            try:
                self.proc.stdin.write(json.dumps(req) + "\n")
                self.proc.stdin.flush()
            except (BrokenPipeError, ValueError):
                raise LabError("the adapter process has exited")
            try:
                line = self.lines.get(timeout=timeout or self.timeout)
            except queue.Empty:
                raise LabError(f"the adapter did not answer {op!r} within {timeout or self.timeout:.0f} s")
            if line is None:
                raise LabError("the adapter process has exited")
            resp = json.loads(line)
            self.log.append(dict(t=round(time.monotonic() - self.t0, 3), req=req, resp=resp))
            if not resp.get("ok"):
                raise LabError(f"{op}: {resp.get('error') or resp}")
            return resp

    def close(self):
        try:
            if self.proc.poll() is None:
                self.call("stop", timeout=30)
        except LabError:
            pass
        try:
            self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.wait(10)
        except subprocess.TimeoutExpired:
            self.proc.kill()     # the exact process we started
            self.proc.wait(5)
        self.stderr.close()


def adapter_command(recipe: dict, folder: Path, adapter: str | None = None, adapter_cmd: str | None = None,
                    adapter_args: list[str] | None = None, allow_recipe_cmd: bool = False) -> list[str]:
    """Which process to start. A command written in a recipe runs only with allow_recipe_cmd, after a human has
    read it: a recipe from someone else is untrusted until then."""
    if adapter_cmd:
        return shlex.split(adapter_cmd)
    name = adapter or recipe["engine"].get("adapter")
    if name in BUILTIN_ADAPTERS:
        return [sys.executable, "-m", "um", "lab", "adapter", name, *(adapter_args or [])]
    cmd = recipe["engine"].get("adapter_cmd")
    if cmd:
        cmd = [c.replace("{dir}", str(folder)) for c in cmd]
        if not allow_recipe_cmd:
            raise LabError("this recipe starts its own adapter: " + shlex.join(cmd) + "\nRead that file first, then "
                           "pass --allow-recipe-cmd (or give your own with --adapter-cmd).")
        return cmd
    raise LabError(f"no adapter: the recipe names {name!r}; built in: {', '.join(BUILTIN_ADAPTERS)}")


def runs_dir() -> Path:
    d = data_dir() / "lab" / "runs"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _child_env() -> dict:
    """The adapter runs `python -m um`: make sure it finds this checkout."""
    env = dict(os.environ)
    env["PYTHONPATH"] = str(REPO) + (os.pathsep + env["PYTHONPATH"] if env.get("PYTHONPATH") else "")
    return env


# --------------------------------------------------------------------------- experiments

class Experiment:
    """One bounded run of one recipe against one adapter: the lab fills the envelope, enforces the recipe's limits
    and keeps a transcript of every request and answer."""

    def __init__(self, recipe_path: str | Path, cmd: list[str], out: Path | None = None):
        self.recipe, self.folder = load(recipe_path)
        self.limits = self.recipe["limits"]
        self.cmd = cmd
        self.id = secrets.token_hex(4)
        self.date = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d")
        self.out = Path(out).resolve() if out else runs_dir() / f"{self.date}-{self.recipe['id']}-{self.id}"
        self.out.mkdir(parents=True, exist_ok=True)
        self.transcript: list = []
        self.adapter: Adapter | None = None
        self.hello: dict = {}
        self.seq: dict[str, int] = {}
        self.obs: dict | None = None
        self.turns = self.commands = 0
        self.started = time.monotonic()
        self.expired = False
        self.checks: dict[str, dict] = {}
        self.notes: dict = {}
        self.timer: threading.Timer | None = None
        self.artifacts_ok = not any("artifact" in e for e in validate(self.folder)["errors"])

    # lifecycle ------------------------------------------------------------
    def start(self) -> dict:
        if not self.adapter:
            os.environ.setdefault("PYTHONUNBUFFERED", "1")
            self.adapter = Adapter(self.cmd, self.transcript, self.out / "adapter.log")
            self.hello = self.adapter.call("hello", timeout=60)
            if self.hello.get("protocol") != ADAPTER_PROTOCOL:
                raise LabError(f"the adapter speaks {self.hello.get('protocol')!r}, not {ADAPTER_PROTOCOL}")
            self.timer = threading.Timer(self.limits["max_wall_seconds"], self._expire)
            self.timer.daemon = True
            self.timer.start()
        engine = self.recipe["engine"]
        mod = str(artifact_path(self.recipe, self.folder, engine["mod"])) if engine.get("mod") else None
        r = self.adapter.call("start", timeout=300, mod=mod, scenario=engine.get("scenario"),
                              player=engine.get("player", 1), commands=list(self.recipe["commands"]),
                              evidence_dir=str(self.out))
        self.seq = {}
        self.observe()
        return r

    def restart(self) -> dict:
        """Start the game again inside the same experiment: a new session, so old commands are stale."""
        self._alive()
        return self.start()

    def _expire(self):
        self.expired = True
        self.notes["expired"] = f"stopped after max_wall_seconds={self.limits['max_wall_seconds']}"
        self.stop()

    def _alive(self):
        if self.expired:
            raise LabError(f"this experiment hit its wall-clock limit ({self.limits['max_wall_seconds']} s)")
        if not self.adapter:
            raise LabError("the experiment is not running")

    def stop(self) -> dict:
        info = {}
        if self.timer:
            self.timer.cancel()
        if self.adapter:
            try:
                if self.adapter.proc.poll() is None:
                    info = self.adapter.call("stop", timeout=60)
            except LabError as e:
                info = dict(error=str(e))
            self.adapter.close()
            self.adapter = None
        self.notes.setdefault("stop", {k: v for k, v in info.items() if k not in ("id", "ok")})
        return info

    # the same typed commands as the GUI and scripts ----------------------
    def envelope(self, cmd: dict, src: str) -> dict:
        out = dict(cmd)
        out.setdefault("src", src)
        if "seq" not in out:
            if out["src"] not in self.seq:   # resume after a disconnect: carry on from what the game has seen
                self.seq[out["src"]] = int((self.obs or {}).get("lastSeq", {}).get(out["src"], 0))
            self.seq[out["src"]] += 1
            out["seq"] = self.seq[out["src"]]
        out.setdefault("session", (self.obs or {}).get("session"))
        out.setdefault("epoch", (self.obs or {}).get("epoch"))
        return out

    def send(self, commands: list[dict], src: str = "lab") -> list[dict]:
        self._alive()
        if not isinstance(commands, list) or not commands:
            raise LabError("send needs a list of commands")
        if len(commands) > self.limits["max_commands_per_send"]:
            raise LabError(f"at most {self.limits['max_commands_per_send']} commands per send")
        typed = []
        for c in commands:
            payload = {k: v for k, v in c.items() if k not in ENVELOPE}
            schema = self.recipe["commands"].get(payload.get("type"))
            if not schema:
                raise LabError(f"unknown command {payload.get('type')!r}; this recipe has {list(self.recipe['commands'])}")
            errs = schema_errors(payload, schema, payload["type"])
            if errs:
                raise LabError("; ".join(errs))
            typed.append(self.envelope(c, src))
        self.adapter.call("send", commands=typed)
        self.commands += len(typed)
        return typed

    def step(self, turns: int = 1) -> dict:
        self._alive()
        turns = int(turns)
        if not 1 <= turns <= self.limits["max_turns_per_step"]:
            raise LabError(f"turns must be 1..{self.limits['max_turns_per_step']} per step")
        if self.turns + turns > self.limits["max_total_turns"]:
            raise LabError(f"the turn budget ({self.limits['max_total_turns']}) is used up")
        r = self.adapter.call("step", turns=turns, timeout=60 + turns * 5)
        self.turns += turns
        return dict(timeMs=r.get("timeMs"), acks=r.get("acks", []), **({"acksDropped": True} if r.get("acks_dropped") else {}))

    def observe(self) -> dict:
        self._alive()
        self.obs = self.adapter.call("observe", player=self.recipe["engine"].get("player", 1))["state"]
        return self.obs

    # evidence ------------------------------------------------------------
    def check(self, name: str, ok: bool, **detail):
        self.checks[name] = dict(ok=bool(ok), **detail)
        return ok

    def write(self, kind: str) -> dict:
        tpath = self.out / "transcript.jsonl"
        tpath.write_text("".join(json.dumps(e, default=str) + "\n" for e in self.transcript), encoding="utf-8")
        (self.out / "checks.json").write_text(json.dumps(self.checks, indent=2, default=str) + "\n", encoding="utf-8")
        rec = receipt(self, kind, sha256(tpath))
        (self.out / "receipt.json").write_text(json.dumps(rec, indent=2) + "\n", encoding="utf-8")
        keep = data_dir() / "lab" / "receipts"
        keep.mkdir(parents=True, exist_ok=True)
        (keep / f"{self.out.name}.json").write_text(json.dumps(rec, indent=2) + "\n", encoding="utf-8")
        return rec


# --------------------------------------------------------------------------- fixtures

def hero_point(obs: dict, at: dict) -> tuple[float, float]:
    """A point relative to the avatar: ahead_m along its facing, right_m to its right. Angle 0 faces +z and turning
    right increases it, so forward = (sin a, cos a) and right = (cos a, -sin a)."""
    hero = (obs or {}).get("avatar")
    if not hero:
        raise LabError("`at` needs an avatar in the observation")
    a, ahead, right = hero["angle"], at.get("ahead_m", 0.0), at.get("right_m", 0.0)
    return (round(hero["x"] + ahead * math.sin(a) + right * math.cos(a), 3),
            round(hero["z"] + ahead * math.cos(a) - right * math.sin(a), 3))


def dig(obj, path: str):
    for part in path.split("."):
        if part == "length" and isinstance(obj, (list, dict)):
            obj = len(obj)
        elif isinstance(obj, list) and part.isdigit():
            obj = obj[int(part)] if int(part) < len(obj) else None
        elif isinstance(obj, dict):
            obj = obj.get(part)
        else:
            return None
    return obj


_OPS = {"==": lambda a, b: a == b, "!=": lambda a, b: a != b, ">": lambda a, b: a is not None and a > b,
        ">=": lambda a, b: a is not None and a >= b, "<": lambda a, b: a is not None and a < b,
        "<=": lambda a, b: a is not None and a <= b, "in": lambda a, b: a in b}


def expect(want, value, before=None) -> str:
    """'' when `value` meets `want`, else why not. `want` is a literal or {op: operand}; "delta" compares the change
    since before the line ran."""
    if isinstance(want, dict) and want and set(want) <= set(_OPS) | {"delta"}:
        for op, operand in want.items():
            if op == "delta":
                if not _is(value, "number") or not _is(before, "number"):
                    return f"cannot take a change of {before!r} -> {value!r}"
                why = expect(operand, round(value - before, 6))
                if why:
                    return f"changed by {round(value - before, 6)}: {why}"
            elif not _OPS[op](value, operand):
                return f"{value!r} is not {op} {operand!r}"
        return ""
    return "" if value == want else f"{value!r} != {want!r}"


def _resolve(cmd: dict, obs: dict, named: dict) -> dict:
    out = {}
    for k, v in cmd.items():
        if k == "at":
            out["x"], out["z"] = hero_point(obs, v)
        elif isinstance(v, dict) and "$ref" in v:
            name, _, field = v["$ref"].partition(".")
            if name not in named:
                raise LabError(f"$ref {v['$ref']!r}: no earlier line stored its ack as {name!r}")
            out[k] = dig(named[name], field)
        else:
            out[k] = v
    return out


def run_fixture(exp: Experiment, lines: list[dict], src: str = "fixture") -> dict:
    named, results = {}, []
    for i, line in enumerate(lines, 1):
        before = exp.observe()
        sent = []
        if "send" in line:
            cmds = line["send"] if isinstance(line["send"], list) else [line["send"]]
            sent = exp.send([_resolve(c, before, named) for c in cmds], src=src)
        turns = line.get("turns", 1 if sent else 0)
        acks = exp.step(turns)["acks"] if turns else []
        after = exp.observe()
        keys = {(c["src"], c["seq"]) for c in sent}
        mine = [a for a in acks if (a.get("src"), a.get("seq")) in keys]
        ack = mine[-1] if mine else None
        if line.get("as") and ack:
            named[line["as"]] = ack
        fails = []
        want = line.get("expect", {})
        if "ack" in want:
            if not ack:
                fails.append("no ack for the command")
            else:
                fails += [f"ack.{k}: {why}" for k, w in want["ack"].items() if (why := expect(w, ack.get(k)))]
        for path, w in want.get("state", {}).items():
            if path.startswith("travel:"):
                a, b = dig(before, path[7:]), dig(after, path[7:])
                moved = round(math.dist((a["x"], a["z"]), (b["x"], b["z"])), 2) if a and b else None
                why = expect(w, moved)
            else:
                why = expect(w, dig(after, path), dig(before, path))
            if why:
                fails.append(f"{path}: {why}")
        results.append(dict(line=i, note=line.get("note", ""), ok=not fails, fails=fails, turns=turns,
                            ack={k: ack.get(k) for k in ("type", "ok", "code", "text", "entity") if k in ack} if ack else None))
    return dict(ok=all(r["ok"] for r in results), lines=results)


def _strip(obs: dict) -> dict:
    """An observation without what differs between two runs of the same commands (the random session number)."""
    return {k: v for k, v in obs.items() if k != "session"}


def digest(obj) -> str:
    return hashlib.sha256(json.dumps(obj, sort_keys=True, default=str).encode()).hexdigest()


# --------------------------------------------------------------------------- acceptance

def protocol_checks(exp: Experiment) -> None:
    """Envelope rules every adapter must keep, whatever the game: refusals, resends, gaps, resume, restart."""
    turn_ms = exp.hello.get("turn_ms") or exp.recipe["timing"]["turn_ms"]
    probe = exp.recipe["acceptance"]["probe"]
    o1, o2 = exp.observe(), exp.observe()
    exp.check("observe_names_session_and_epoch", _is(o1.get("session"), "integer") and _is(o1.get("epoch"), "integer"),
              session_is_int=_is(o1.get("session"), "integer"), epoch=o1.get("epoch"))
    exp.check("observe_has_no_side_effects", o1 == o2)
    t0 = o1["timeMs"]
    exp.step(5)
    t1 = exp.observe()["timeMs"]
    exp.check("step_advances_exactly_n_turns", t1 - t0 == 5 * turn_ms, before_ms=t0, after_ms=t1, turn_ms=turn_ms)

    src = f"accept-{exp.id}"

    def one(cmd: dict) -> tuple[dict | None, dict]:
        sent = exp.send([cmd], src=src)[0]
        acks = exp.step(1)["acks"]
        return next((a for a in acks if a.get("src") == sent["src"] and a.get("seq") == sent["seq"]), None), sent

    a1, first = one(probe)
    exp.check("first_command_is_admitted", bool(a1) and a1.get("code") not in REFUSALS, ack=a1)
    before = _strip(exp.observe())
    a2, _ = one(dict(first))                       # the same command again: same src and seq
    after = _strip(exp.observe())
    same = {k: v for k, v in before.items() if k not in ("timeMs", "acks", "ackCount")} == \
           {k: v for k, v in after.items() if k not in ("timeMs", "acks", "ackCount")}
    exp.check("resend_is_ignored_as_duplicate", bool(a2) and a2.get("code") == "duplicate" and same, ack=a2,
              state_unchanged=same)
    skip = exp.envelope(probe, src)
    skip["seq"] += 1
    exp.seq[src] = skip["seq"]
    a3, _ = one(skip)
    exp.check("skipped_seq_is_flagged_as_gap", bool(a3) and a3.get("gap") is True, ack=a3)
    obs = exp.observe()
    a4, _ = one(dict(probe, session=(obs["session"] % 2147483646) + 1))
    exp.check("other_session_is_refused", bool(a4) and a4.get("code") == "stale-session", ack=a4)
    a5, _ = one(dict(probe, epoch=obs["epoch"] + 1))
    exp.check("other_epoch_is_refused", bool(a5) and a5.get("code") == "stale-epoch", ack=a5)
    a6, _ = one(dict(probe, seq=0))
    exp.check("bad_envelope_is_refused", bool(a6) and a6.get("code") == "bad-envelope", ack=a6)

    # A controller that lost its connection comes back knowing nothing. The game is the record: it observes, carries
    # on after lastSeq, and a resend of its last command does no harm. (Refused commands never reach lastSeq.)
    exp.seq.pop(src)
    seen = exp.observe().get("lastSeq", {}).get(src)
    a7, _ = one(dict(probe, seq=seen or 1))
    a8, nxt = one(probe)
    exp.check("resume_after_disconnect", seen == skip["seq"] and bool(a7) and a7.get("code") == "duplicate" and
              bool(a8) and a8.get("code") not in REFUSALS and nxt["seq"] == skip["seq"] + 1,
              last_seq_seen=seen, resend=a7 and a7.get("code"), next_seq=nxt["seq"], next=a8 and a8.get("code"))

    old = exp.observe()
    exp.restart()
    new = exp.observe()
    exp.check("restart_gets_a_new_session", new["session"] != old["session"] and new["epoch"] == 0,
              epoch=new["epoch"])
    stale = dict(probe, src=src, seq=nxt["seq"] + 1, session=old["session"], epoch=old["epoch"])
    a9, _ = one(stale)
    exp.check("command_from_before_restart_is_refused", bool(a9) and a9.get("code") == "stale-session", ack=a9)


def accept(recipe_path: str | Path, cmd: list[str], out: Path | None = None, replay: bool = True,
           fixtures: list[str] | None = None) -> dict:
    exp = Experiment(recipe_path, cmd, out)
    results = {}
    try:
        exp.start()
        protocol_checks(exp)
        names = fixtures or exp.recipe["acceptance"]["fixtures"]
        digests = {}
        for name in names:
            exp.restart()
            r = run_fixture(exp, read_fixture(exp.recipe, exp.folder, name))
            results[name] = r
            digests[name] = digest(dict(lines=r["lines"], state=_strip(exp.observe())))
            exp.check(f"fixture:{name}", r["ok"], failed=[f"{x['line']}: {x['fails']}" for x in r["lines"] if not x["ok"]])
        if replay and names:
            name = names[0]
            exp.restart()
            r = run_fixture(exp, read_fixture(exp.recipe, exp.folder, name))
            again = digest(dict(lines=r["lines"], state=_strip(exp.observe())))
            exp.check(f"replay_is_identical:{name}", again == digests[name], first=digests[name][:16], second=again[:16])
    except LabError as e:
        exp.notes["aborted"] = str(e)
    finally:
        exp.stop()
    (exp.out / "fixtures.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    rec = exp.write("acceptance")
    return dict(receipt=rec, folder=str(exp.out), fixtures=results)


# --------------------------------------------------------------------------- receipts and metrics

def machine_id() -> str:
    """A random number made once per install, so receipts from one machine can be told apart from another's without
    saying whose machine it is."""
    f = data_dir() / "lab" / "machine-id"
    if not f.is_file():
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(secrets.token_hex(16) + "\n")
    return hashlib.sha256(("umx-lab:" + f.read_text().strip()).encode()).hexdigest()[:16]


_SECRETS = [r"sk-[A-Za-z0-9_-]{16,}", r"gh[pousr]_[A-Za-z0-9]{20,}", r"github_pat_[A-Za-z0-9_]{20,}",
            r"AKIA[0-9A-Z]{16}", r"xox[abprs]-[A-Za-z0-9-]{10,}", r"AIza[0-9A-Za-z_-]{30,}", r"hf_[A-Za-z0-9]{20,}",
            r"(?i)\b(api[_-]?key|token|secret|password|bearer)\b\s*[:=]?\s*[^\s,;\"']{8,}"]
_PATHS = [r"/Users/[^/\s\"']+", r"/home/[^/\s\"']+", r"[A-Za-z]:\\\\Users\\\\[^\\\s\"']+", r"/root\b"]


def _secret_values() -> list[str]:
    return [v for k, v in os.environ.items() if re.search(r"KEY|TOKEN|SECRET|PASSWORD", k) and len(v) >= 8]


def sanitize(obj):
    """Strip what a receipt must never carry: home folders and user names in paths, emails, API keys."""
    if isinstance(obj, dict):
        return {k: sanitize(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [sanitize(v) for v in obj]
    if not isinstance(obj, str):
        return obj
    s = obj.replace(str(Path.home()), "~")
    for v in _secret_values():
        s = s.replace(v, "<redacted>")
    for pat in _SECRETS:
        s = re.sub(pat, "<redacted>", s)
    for pat in _PATHS:
        s = re.sub(pat, "~", s)
    return re.sub(r"[\w.+-]+@[\w-]+\.[\w.-]+", "<email>", s)


def leaks(obj) -> list[str]:
    text = json.dumps(obj)
    found = [m.group(0) for pat in _SECRETS + _PATHS for m in re.finditer(pat, text)]
    found += [v[:4] + "..." for v in _secret_values() if v in text]
    found += re.findall(r"[\w.+-]+@[\w-]+\.[\w.-]+", text)
    if str(Path.home()) in text:
        found.append("home folder")
    return found


def receipt(exp: Experiment, kind: str, transcript_sha: str) -> dict:
    checks = {k: v["ok"] for k, v in exp.checks.items()}
    rec = dict(
        format=RECEIPT_FORMAT, kind=kind, date=exp.date, machine=machine_id(),
        recipe=dict(id=exp.recipe["id"], version=exp.recipe["version"], digest=recipe_digest(exp.recipe),
                    artifacts_match=exp.artifacts_ok),
        adapter=dict(name=exp.hello.get("adapter"), protocol=exp.hello.get("protocol")),
        game=exp.hello.get("game"),
        platform=f"{sys.platform}-{platform.machine()}", lab=f"universal-modder {__version__}",
        checks=checks, passed=sum(checks.values()), total=len(checks),
        all_passed=bool(checks) and all(checks.values()) and exp.artifacts_ok and "aborted" not in exp.notes,
        totals=dict(turns=exp.turns, commands=exp.commands, wall_seconds=round(time.monotonic() - exp.started, 1)),
        transcript_sha256=transcript_sha,
        notes={k: v for k, v in exp.notes.items()},
    )
    return sanitize(rec)


def read_receipts(extra: list[str] | None = None) -> list[dict]:
    files = list((data_dir() / "lab" / "receipts").glob("*.json"))
    for d in extra or []:
        p = Path(d)
        files += [p] if p.is_file() else sorted(p.rglob("*.json"))
    out, seen = [], set()
    for f in files:
        try:
            r = json.loads(f.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            continue
        if isinstance(r, dict) and r.get("format") == RECEIPT_FORMAT and r.get("transcript_sha256") not in seen:
            seen.add(r.get("transcript_sha256"))
            out.append(r)
    return out


def metrics(extra: list[str] | None = None) -> dict:
    """Counts, not forecasts: what the receipts on this machine (and in the folders given) show."""
    rs = read_receipts(extra)
    by = defaultdict(list)
    for r in rs:
        by[r["recipe"]["id"]].append(r)
    recipes = {}
    for rid, items in sorted(by.items()):
        passed = [r for r in items if r.get("all_passed")]
        machines = sorted({r["machine"] for r in passed})
        days = defaultdict(set)
        for r in items:
            days[r["machine"]].add(r["date"])
        recipes[rid] = dict(receipts=len(items), passing=len(passed), completed=bool(passed),
                            machines_passing=len(machines), independent_reproductions=max(0, len(machines) - 1),
                            machines_back_on_another_day=sum(1 for d in days.values() if len(d) >= 2),
                            versions=sorted({r["recipe"]["version"] for r in items}),
                            platforms=sorted({r.get("platform", "?") for r in passed}))
    return dict(
        completed_remixes=sum(1 for v in recipes.values() if v["completed"]),
        independent_reproductions=sum(v["independent_reproductions"] for v in recipes.values()),
        repeat_usage=sum(v["machines_back_on_another_day"] for v in recipes.values()),
        receipts_read=len(rs), recipes=recipes,
        meaning=("completed remix: a recipe with at least one receipt where every check passed. Independent "
                 "reproduction: another machine id with a passing receipt for the same recipe. Repeat usage: a "
                 "machine with receipts for one recipe on two or more days. A machine id is a random number per "
                 "install, not a person, and receipts only count once they are collected here."))


# --------------------------------------------------------------------------- the 0 A.D. adapter

class AdapterError(Exception):
    def __init__(self, msg: str, code: str = "error"):
        super().__init__(msg)
        self.code = code


def serve_adapter(impl, stdin=None, stdout=None):
    """Answer umx-adapter/0.1 requests, one JSON object per line, until stdin closes."""
    stdin, stdout = stdin or sys.stdin, stdout or sys.stdout
    for line in stdin:
        if not line.strip():
            continue
        rid = None
        try:
            req = json.loads(line)
            rid = req.get("id")
            fn = getattr(impl, "op_" + str(req.get("op")), None)
            if not fn:
                raise AdapterError(f"unknown op {req.get('op')!r}", "unknown-op")
            resp = dict(ok=True, **fn(**{k: v for k, v in req.items() if k not in ("id", "op")}))
        except AdapterError as e:
            resp = dict(ok=False, error=str(e), code=e.code)
        except Exception as e:                     # report, keep serving
            resp = dict(ok=False, error=f"{type(e).__name__}: {e}", code="error")
        stdout.write(json.dumps(dict(id=rid, **resp)) + "\n")
        stdout.flush()
    if hasattr(impl, "op_stop"):
        impl.op_stop()


# Where 0 A.D. usually lives. The Linux path is the Debian/Ubuntu package; the macOS paths are the app bundle
# (untested).
ZEROAD_PLACES = [
    ("/usr/games/pyrogenesis", "/usr/share/games/0ad/mods/public"),
    ("/Applications/0 A.D..app/Contents/MacOS/pyrogenesis", "/Applications/0 A.D..app/Contents/Resources/data/mods/public"),
    ("~/Applications/0 A.D..app/Contents/MacOS/pyrogenesis", "~/Applications/0 A.D..app/Contents/Resources/data/mods/public"),
]


def find_0ad(exe: str | None = None, public: str | None = None) -> tuple[str, str]:
    """The game's executable and mods/public folder: the ones given, else the first usual place that has the game.
    A known public folder brings its own executable, so a Linux pyrogenesis is never paired with a macOS bundle."""
    for e, p in ZEROAD_PLACES:
        e, p = os.path.expanduser(e), os.path.expanduser(p)
        if (public is None and Path(p, "mod.json").is_file()) or (public and Path(public) == Path(p)):
            public, exe = p, exe or (e if Path(e).is_file() else None)
            break
    if not public or not Path(public, "mod.json").is_file():
        raise LabError("0 A.D. not found: pass --public <the game's mods/public folder> (and --exe <pyrogenesis>)")
    return exe or shutil.which("pyrogenesis") or "pyrogenesis", public


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class ZeroAD:
    """0 A.D. (Pyrogenesis), the original engine, headless: -autostart-nonvisual and the RL interface (/step runs
    one 200 ms turn with the commands posted to it, /evaluate reads the simulation). Only the recipe's command types
    are forwarded, the RL port listens on 127.0.0.1 only, and the game gets a CPU-time cap. Not an OS sandbox: the
    game runs with your permissions.

    Linux: everything the game writes (mods, config, logs, cache) goes to a lab folder via XDG_*_HOME. macOS has no
    such switch (Paths.cpp), so there the mod is copied to ~/Library/Application Support/0ad/mods (untested)."""

    UM = "Engine.QueryInterface(SYSTEM_ENTITY, IID_UmCrossover)"

    def __init__(self, exe: str, public: str, data: str | None, cpu_seconds: int):
        self.exe, self.public = exe, Path(public)
        self.data = Path(data) if data else data_dir() / "lab" / "0ad"
        self.cpu = cpu_seconds
        self.proc = None
        self.url = ""
        self.queue: list = []
        self.allowed: set = set()
        self.player, self.last_n = 1, 0
        self.version = json.loads((self.public / "mod.json").read_text(encoding="utf-8"))["version"]

    def mods_dir(self) -> Path:
        if sys.platform == "darwin":
            return Path.home() / "Library/Application Support/0ad/mods"
        return self.data / "xdg" / "data" / "0ad" / "mods"

    def env(self) -> dict:
        env = dict(os.environ)
        if sys.platform != "darwin":
            for k, sub in (("XDG_DATA_HOME", "data"), ("XDG_CONFIG_HOME", "config"), ("XDG_CACHE_HOME", "cache")):
                env[k] = str(self.data / "xdg" / sub)
        return env

    def post(self, route: str, body: str) -> bytes:
        req = urllib.request.Request(f"{self.url}/{route}", data=body.encode(), method="POST")
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read()

    def js(self, body: str):
        return json.loads(self.post("evaluate", f"(()=>{{ {body} }})()") or b"null")

    def alive(self):
        if not self.proc:
            raise AdapterError("the game is not running; send start first", "not-started")
        if self.proc.poll() is not None:
            raise AdapterError(f"the game exited (code {self.proc.returncode}); see game.log", "session-lost")

    def op_hello(self, **_):
        return dict(protocol=ADAPTER_PROTOCOL, adapter="0ad", game=dict(name="0 A.D.", version=self.version),
                    turn_ms=200, profile="native-worker")

    def install(self, mod: Path) -> Path:
        dst = self.mods_dir() / mod.name
        for f in mod.rglob("*"):
            if f.is_file():
                (dst / f.relative_to(mod)).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(f, dst / f.relative_to(mod))
        meta = json.loads((dst / "mod.json").read_text(encoding="utf-8"))
        meta["dependencies"] = [f"0ad={self.version}"]       # 0 A.D. loads a mod only for its own version
        (dst / "mod.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
        return dst

    def op_start(self, mod=None, scenario=None, player=1, commands=(), evidence_dir=None, **_):
        if self.proc:
            self.op_stop()
        if not mod:
            raise AdapterError("start needs the recipe's mod folder", "bad-args")
        self.allowed, self.player, self.queue, self.last_n = set(commands), int(player), [], 0
        self.data.mkdir(parents=True, exist_ok=True)
        name = self.install(Path(mod)).name
        port = _free_port()
        self.url = f"http://127.0.0.1:{port}"
        cmd = [self.exe, "-mod=public", f"-mod={name}", "-autostart-nonvisual",
               f"-autostart=scenarios/{scenario or 'arcadia'}", f"-rl-interface=127.0.0.1:{port}"]
        log = open(Path(evidence_dir) / "game.log" if evidence_dir else self.data / "game.log", "a", encoding="utf-8")
        log.write(f"\n# {dt.datetime.now().isoformat(timespec='seconds')} {' '.join(cmd)}\n")
        log.flush()
        limit = None
        if os.name == "posix" and self.cpu:
            import resource

            def limit():
                resource.setrlimit(resource.RLIMIT_CPU, (self.cpu, self.cpu))
        t0 = time.monotonic()
        self.proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, env=self.env(), preexec_fn=limit)
        log.close()
        for _ in range(240):
            try:
                ms = self.js("return Engine.QueryInterface(SYSTEM_ENTITY, IID_Timer).GetTime();")
                if ms is None:
                    raise OSError("simulation not loaded yet")
                if ms > 1000:      # someone else's game answered on this port
                    self.op_stop()
                    raise AdapterError("the RL interface that answered is not the game just started", "wrong-game")
                break
            except OSError:
                if self.proc.poll() is not None:
                    raise AdapterError(f"the game exited while starting (code {self.proc.returncode})", "start-failed")
                time.sleep(0.5)
        else:
            self.op_stop()
            raise AdapterError("the RL interface never answered", "start-failed")
        if not self.js(f"return !!{self.UM};"):
            self.op_stop()
            raise AdapterError(f"the mod {name} is not loaded (no UmCrossover component)", "mod-missing")
        # Autostart seeds the simulation's random numbers with 0 unless -autostart-seed is given (A26 GameSetup.cpp,
        # Simulation2.cpp InitRNGSeedSimulation), so the session number UmCrossover draws repeats on every restart.
        # The adapter tags each game with a fresh one instead.
        session = secrets.randbelow(2147483646) + 1
        self.js(f"return {self.UM}.SetSession({session});")
        state = self.js(f"return {self.UM}.Observe({self.player});")
        return dict(session=state["session"], epoch=state["epoch"], timeMs=state["timeMs"],
                    started_seconds=round(time.monotonic() - t0, 1))

    def op_send(self, commands=(), **_):
        self.alive()
        for c in commands:
            if not isinstance(c, dict) or c.get("type") not in self.allowed:
                raise AdapterError(f"command {c.get('type') if isinstance(c, dict) else c!r} is not one of this "
                                   f"recipe's", "not-allowed")
        self.queue += list(commands)
        return dict(queued=len(self.queue))

    def op_step(self, turns=1, **_):
        self.alive()
        body, self.queue = "\n".join(f"{self.player};{json.dumps(c)}" for c in self.queue), []
        self.post("step", body)
        for _ in range(int(turns) - 1):
            self.post("step", "")
        acks = self.js(f"return {self.UM}.acks.filter(a => a.n > {self.last_n});") or []
        dropped = bool(acks) and acks[0]["n"] > self.last_n + 1
        if acks:
            self.last_n = acks[-1]["n"]
        out = dict(timeMs=self.js("return Engine.QueryInterface(SYSTEM_ENTITY, IID_Timer).GetTime();"), acks=acks)
        if dropped:
            out["acks_dropped"] = True
        return out

    def op_observe(self, player=None, **_):
        self.alive()
        return dict(state=self.js(f"return {self.UM}.Observe({int(player or self.player)});"))

    def op_stop(self, **_):
        if not self.proc:
            return dict(stopped=False)
        import resource      # POSIX; this adapter runs on Linux and macOS
        before = resource.getrusage(resource.RUSAGE_CHILDREN)
        self.proc.terminate()                  # the exact process this adapter started
        try:
            self.proc.wait(15)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait(5)
        after = resource.getrusage(resource.RUSAGE_CHILDREN)
        code, self.proc = self.proc.returncode, None
        rss = after.ru_maxrss / (1024 * 1024 if sys.platform == "darwin" else 1024)
        return dict(stopped=True, exit_code=code, cpu_seconds=round((after.ru_utime + after.ru_stime) -
                                                                    (before.ru_utime + before.ru_stime), 1),
                    peak_rss_mb=round(rss))


# --------------------------------------------------------------------------- MCP over stdio

MODERN = "2026-07-28"
SUPPORTED_VERSIONS = [MODERN, "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"]
VERSION_KEY = "io.modelcontextprotocol/protocolVersion"
SERVER_INFO = dict(name="umx-lab", version=__version__)
INSTRUCTIONS = ("Run gameplay-mechanic recipes in a real game, one bounded experiment at a time. Start one, send "
                "typed commands, step the clock (the game does not move between steps), observe, and export the "
                "receipt. Commands are the recipe's own JSON types; the lab adds src/seq/session/epoch.")


class LabServer:
    """The lab's operations as MCP tools. Each tool takes recipe ids and typed commands, never paths or shell
    commands: which adapter runs, and with which game, is fixed when the server is started."""

    def __init__(self, recipes_root: Path, adapter_args: list[str], adapter_cmd: str | None = None):
        self.root, self.adapter_args, self.adapter_cmd = recipes_root, adapter_args, adapter_cmd
        self.exp: Experiment | None = None
        self.negotiated: str | None = None
        self.tools = [
            ("lab_capabilities", "What this lab can run: recipes (commands, adapter, profile), limits, protocol.",
             {}, [], self.t_capabilities),
            ("lab_validate", "Check a recipe without running it: format, file hashes, licences, WIT, fixtures.",
             {"recipe": {"type": "string"}}, ["recipe"], self.t_validate),
            ("lab_start", "Start one bounded experiment of a recipe in its game (stops any running one). Returns the "
                          "session, epoch and limits.", {"recipe": {"type": "string"}}, ["recipe"], self.t_start),
            ("lab_send", "Queue typed commands for the next step. Each is the recipe's JSON command (type plus fields); "
                         "src/seq/session/epoch are filled in unless given.",
             {"commands": {"type": "array", "items": {"type": "object"}}, "src": {"type": "string"}}, ["commands"],
             self.t_send),
            ("lab_step", "Advance the game by N turns (the recipe's turn length each) and return the acks.",
             {"turns": {"type": "integer", "minimum": 1}}, [], self.t_step),
            ("lab_observe", "The recipe's observation: session, epoch, avatar, squad, HUD numbers, lastSeq, newest acks.",
             {}, [], self.t_observe),
            ("lab_replay", "Restart the game and run one of the recipe's replay fixtures, reporting each line and a digest "
                           "of the result (the same fixture gives the same digest).",
             {"fixture": {"type": "string"}}, ["fixture"], self.t_replay),
            ("lab_export", "Write the evidence (transcript, checks, sanitized receipt) and return the receipt.",
             {}, [], self.t_export),
            ("lab_stop", "Stop the experiment and its game.", {}, [], self.t_stop),
        ]

    def recipe_dir(self, rid: str) -> Path:
        for r in find_recipes(self.root):
            if r["id"] == rid:
                return r["folder"]
        raise LabError(f"no recipe {rid!r}; have {[r['id'] for r in find_recipes(self.root)]}")

    def running(self) -> Experiment:
        if not self.exp:
            raise LabError("no experiment running; call lab_start")
        return self.exp

    def t_capabilities(self, a):
        return dict(protocol=ADAPTER_PROTOCOL, recipe_format=FORMAT, negotiated_mcp=self.negotiated,
                    recipes=[{k: v for k, v in r.items() if k != "folder"} for r in find_recipes(self.root)],
                    running=dict(recipe=self.exp.recipe["id"], turns=self.exp.turns, limits=self.exp.limits)
                    if self.exp else None,
                    frame_loop="outside: the game advances only on lab_step")

    def t_validate(self, a):
        return validate(self.recipe_dir(a["recipe"]))

    def t_start(self, a):
        if self.exp:
            self.t_stop({})
        folder = self.recipe_dir(a["recipe"])
        recipe, _ = load(folder)
        cmd = adapter_command(recipe, folder, adapter_cmd=self.adapter_cmd, adapter_args=self.adapter_args)
        self.exp = Experiment(folder, cmd)
        try:
            r = self.exp.start()
        except LabError:
            self.exp.stop()
            self.exp = None
            raise
        return dict(experiment=self.exp.id, recipe=recipe["id"], game=self.exp.hello.get("game"),
                    session=r.get("session"), epoch=r.get("epoch"), limits=self.exp.limits,
                    commands=list(recipe["commands"]))

    def t_send(self, a):
        return dict(sent=self.running().send(a["commands"], src=a.get("src") or "mcp"))

    def t_step(self, a):
        return self.running().step(a.get("turns", 1))

    def t_observe(self, a):
        return self.running().observe()

    def t_replay(self, a):
        exp = self.running()
        lines = read_fixture(exp.recipe, exp.folder, a["fixture"])
        exp.restart()                    # a fixture starts from a fresh game, so the replay compares like with like
        r = run_fixture(exp, lines, src="mcp-replay")
        exp.check(f"fixture:{a['fixture']}", r["ok"])
        return dict(r, session=exp.obs.get("session"), digest=digest(dict(lines=r["lines"], state=_strip(exp.obs))))

    def t_export(self, a):
        exp = self.running()
        rec = exp.write("mcp")
        return dict(receipt=rec, folder=sanitize(str(exp.out)))

    def t_stop(self, a):
        if not self.exp:
            return dict(stopped=False)
        info = self.exp.stop()
        rec = self.exp.write("mcp")
        self.exp = None
        return dict(stopped=True, receipt=rec, game=sanitize({k: v for k, v in info.items() if k not in ("id", "ok")}))

    # JSON-RPC -------------------------------------------------------------
    def handle(self, msg: dict) -> dict | None:
        mid, method, params = msg.get("id"), msg.get("method"), msg.get("params") or {}
        if mid is None:
            return None                       # a notification
        asked = (params.get("_meta") or {}).get(VERSION_KEY)

        def ok(result):
            return dict(jsonrpc="2.0", id=mid, result=result)

        def err(code, message, data=None):
            return dict(jsonrpc="2.0", id=mid, error=dict(code=code, message=message, **({"data": data} if data else {})))

        if asked and asked not in SUPPORTED_VERSIONS:
            return err(-32022, f"unsupported protocol version {asked}",
                       dict(supported=SUPPORTED_VERSIONS, requested=asked))
        if method == "server/discover":
            self.negotiated = asked or MODERN
            return ok(dict(supportedVersions=SUPPORTED_VERSIONS, capabilities=dict(tools=dict(listChanged=False)),
                           instructions=INSTRUCTIONS, ttlMs=0, cacheScope="private", resultType="complete",
                           _meta={"io.modelcontextprotocol/serverInfo": SERVER_INFO}))
        if method == "initialize":
            want = params.get("protocolVersion")
            self.negotiated = want if want in SUPPORTED_VERSIONS[1:] else SUPPORTED_VERSIONS[1]
            return ok(dict(protocolVersion=self.negotiated, capabilities=dict(tools=dict(listChanged=False)),
                           serverInfo=SERVER_INFO, instructions=INSTRUCTIONS))
        if method == "ping":
            return ok({})
        if method == "tools/list":
            tools = [dict(name=n, description=d, inputSchema=dict(type="object", properties=p, required=r))
                     for n, d, p, r, _ in self.tools]
            return ok(dict(tools=tools, ttlMs=0, cacheScope="private", resultType="complete"))
        if method == "tools/call":
            tool = next((t for t in self.tools if t[0] == params.get("name")), None)
            if not tool:
                return err(-32602, f"unknown tool {params.get('name')!r}")
            try:
                res = tool[4](params.get("arguments") or {})
                is_error = False
            except (LabError, KeyError, ValueError, TypeError) as e:
                res, is_error = dict(error=str(e)), True
            res = sanitize(res if isinstance(res, dict) else dict(result=res))
            return ok(dict(content=[dict(type="text", text=json.dumps(res, indent=1, default=str))],
                           structuredContent=res, isError=is_error, resultType="complete"))
        return err(-32601, f"method not found: {method}")

    def serve(self, stdin=None, stdout=None):
        stdin, stdout = stdin or sys.stdin, stdout or sys.stdout
        try:
            for line in stdin:
                if not line.strip():
                    continue
                try:
                    resp = self.handle(json.loads(line))
                except json.JSONDecodeError:
                    resp = dict(jsonrpc="2.0", id=None, error=dict(code=-32700, message="parse error"))
                if resp is not None:
                    stdout.write(json.dumps(resp, default=str) + "\n")
                    stdout.flush()
        finally:
            if self.exp:
                self.t_stop({})


# --------------------------------------------------------------------------- CLI

def _adapter_args(a) -> list[str]:
    out = []
    for flag in ("exe", "public", "data"):
        if getattr(a, flag, None):
            out += [f"--{flag}", str(getattr(a, flag))]
    return out


def _print_validation(r: dict):
    print(f"{r.get('recipe')} {r.get('version', '')}: {'valid' if r['ok'] else 'INVALID'}")
    for k in ("errors", "warnings", "notes"):
        for line in r.get(k, []):
            print(f"  {k[:-1]}: {line}")


def _print_receipt(rec: dict, folder: str):
    print(f"{rec['recipe']['id']} {rec['recipe']['version']} on {rec.get('game', {}).get('name')} "
          f"{rec.get('game', {}).get('version')} ({rec['platform']}): {rec['passed']}/{rec['total']} checks"
          f"{'' if rec['all_passed'] else '  NOT ALL PASSED'}")
    for name, ok in rec["checks"].items():
        print(f"  {'pass' if ok else 'FAIL'}  {name}")
    for k, v in rec.get("notes", {}).items():
        print(f"  note {k}: {v}")
    print(f"evidence: {sanitize(folder)}")


def main(args):
    try:
        if args.cmd == "validate":
            r = validate(args.recipe)
            emit(r) if args.json else _print_validation(r)
            sys.exit(0 if r["ok"] else 1)
        elif args.cmd == "hash":
            changes = rehash(args.recipe, write=args.write)
            emit(dict(changed=changes, written=bool(args.write and changes)))
        elif args.cmd == "schema":
            emit(RECIPE_SCHEMA)
        elif args.cmd in ("accept", "run"):
            recipe, folder = load(args.recipe)
            cmd = adapter_command(recipe, folder, args.adapter, args.adapter_cmd, _adapter_args(args),
                                  args.allow_recipe_cmd)
            out = Path(args.out) if args.out else None
            if args.cmd == "accept":
                r = accept(folder, cmd, out, replay=not args.no_replay)
                _print_receipt(r["receipt"], r["folder"])
                sys.exit(0 if r["receipt"]["all_passed"] else 1)
            exp = Experiment(folder, cmd, out)
            try:
                exp.start()
                res = run_fixture(exp, read_fixture(exp.recipe, exp.folder, args.fixture))
                exp.check(f"fixture:{args.fixture}", res["ok"])
                for line in res["lines"]:
                    print(f"  {'pass' if line['ok'] else 'FAIL'} {line['line']:2} {line['note']}"
                          f"{'' if line['ok'] else '  ' + '; '.join(line['fails'])}")
            except LabError as e:
                exp.notes["aborted"] = str(e)
            finally:
                exp.stop()
            rec = exp.write("fixture")
            _print_receipt(rec, str(exp.out))
            sys.exit(0 if rec["all_passed"] else 1)
        elif args.cmd == "receipt":
            rec = json.loads((Path(args.folder) / "receipt.json").read_text(encoding="utf-8"))
            rec = sanitize(rec)
            found = leaks(rec)
            if found:
                die(f"the receipt still contains {found[:3]}; not printing it")
            emit(rec)
        elif args.cmd == "metrics":
            emit(metrics(args.from_))
        elif args.cmd == "mcp":
            LabServer(Path(args.recipes), _adapter_args(args), args.adapter_cmd).serve()
        elif args.cmd == "adapter":
            if args.name != "0ad":
                die(f"unknown adapter {args.name!r}")
            exe, public = find_0ad(args.exe, args.public)
            serve_adapter(ZeroAD(exe, public, args.data, args.cpu_seconds))
    except LabError as e:
        die(str(e))


def register(sub):
    p = sub.add_parser("lab", help="package a mechanic as a recipe, run acceptance tests in the real game, receipts, MCP")
    p.description = __doc__
    p.formatter_class = argparse.RawDescriptionHelpFormatter
    cs = p.add_subparsers(dest="cmd", required=True)
    v = cs.add_parser("validate", help="check a recipe without running it")
    v.add_argument("recipe")
    v.add_argument("--json", action="store_true")
    h = cs.add_parser("hash", help="recompute the recipe's artifact sha256 (--write to record them)")
    h.add_argument("recipe")
    h.add_argument("--write", action="store_true")
    cs.add_parser("schema", help="print the recipe format as JSON Schema")

    def game_flags(x):
        x.add_argument("--adapter", help=f"built-in adapter ({', '.join(BUILTIN_ADAPTERS)}); default: the recipe's")
        x.add_argument("--adapter-cmd", help="run this adapter command instead (any language, umx-adapter/0.1)")
        x.add_argument("--allow-recipe-cmd", action="store_true",
                       help="run the adapter command written in the recipe (read it first)")
        x.add_argument("--exe", help="0ad: the pyrogenesis binary (default: on PATH, or /usr/games/pyrogenesis)")
        x.add_argument("--public", help="0ad: the game's mods/public folder (its mod.json gives the version)")
        x.add_argument("--data", help="0ad: lab folder for the game's mods, config and logs (Linux)")

    a = cs.add_parser("accept", help="acceptance suite: envelope rules, restart, every fixture, replay")
    a.add_argument("recipe")
    game_flags(a)
    a.add_argument("--out", help="evidence folder (default ~/.universal-modder/lab/runs/<date>-<recipe>-<id>)")
    a.add_argument("--no-replay", action="store_true", help="skip running the first fixture a second time")
    r = cs.add_parser("run", help="one bounded experiment: run one fixture and keep the evidence")
    r.add_argument("recipe")
    r.add_argument("fixture")
    game_flags(r)
    r.add_argument("--out")
    cs.add_parser("receipt", help="print a run's sanitized receipt").add_argument("folder")
    m = cs.add_parser("metrics", help="completed remixes, independent reproductions, repeat usage (from receipts)")
    m.add_argument("--from", dest="from_", action="append", help="also read receipts in this folder (repeatable)")
    s = cs.add_parser("mcp", help="serve the lab to an agent over stdio (Model Context Protocol)")
    s.add_argument("--recipes", default=str(REPO / "recipes"))
    game_flags(s)
    ad = cs.add_parser("adapter", help="run a built-in adapter process (the lab starts it for you)")
    ad.add_argument("name", choices=BUILTIN_ADAPTERS)
    ad.add_argument("--exe")
    ad.add_argument("--public", help="the game's mods/public folder (default: the usual install places)")
    ad.add_argument("--data")
    ad.add_argument("--cpu-seconds", type=int, default=1800, help="CPU-time cap for the game process (POSIX)")
    p.set_defaults(func=main)
