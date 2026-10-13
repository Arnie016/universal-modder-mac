"""Inspect, verify, test and serve a local game remix. No automatic downloads or game hooks."""
from __future__ import annotations

import functools
import hashlib
import http.server
import json
import re
import shutil
import subprocess
import time
from pathlib import Path, PurePosixPath


class RemixError(ValueError):
    """A package or execution gate failed."""


def _json(path: Path) -> dict:
    if path.stat().st_size > 1_048_576:
        raise RemixError("Metadata exceeds the 1 MiB budget")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RemixError("Metadata must be an object")
    return value


def _file(root: Path, name: str) -> Path:
    if not isinstance(name, str) or "\\" in name:
        raise RemixError("Use relative POSIX file names")
    parts = PurePosixPath(name)
    if parts.as_posix() != name or parts.is_absolute() or not parts.parts or any(p in ("..", ".") for p in parts.parts):
        raise RemixError("Package path must stay inside its folder")
    candidate = root.joinpath(*parts.parts)
    if candidate.is_symlink() or not candidate.resolve().is_relative_to(root):
        raise RemixError("Package symlink escapes or aliases a source file")
    if not candidate.is_file():
        raise RemixError(f"Missing package file: {name}")
    return candidate


def match_host(recipe: dict, host: dict) -> dict:
    """Declarations only; never report native hooks or gameplay from metadata."""
    missing = []
    requires = recipe.get("requires")
    commands = recipe.get("capabilities")
    recipe_host = recipe.get("host", {})
    if not isinstance(recipe_host, dict):
        raise RemixError("Recipe host must be an object")
    owns = recipe_host.get("owns")
    if not isinstance(requires, dict) or not requires:
        raise RemixError("Recipe needs explicit host requirements")
    if not isinstance(commands, list) or not commands or not all(isinstance(c, str) for c in commands):
        raise RemixError("Recipe capabilities must be a nonempty list")
    if not isinstance(owns, list) or not owns or not all(isinstance(c, str) for c in owns):
        raise RemixError("Recipe needs explicit state authority")
    for key, value in requires.items():
        if host.get(key) != value or type(host.get(key)) is not type(value):
            missing.append(f"{key}: requires {value}")
    host_commands = host.get("commands", [])
    if not isinstance(host_commands, list):
        host_commands = []
    for command in commands:
        if command not in host_commands:
            missing.append(f"command: {command}")
    authority = host.get("authority", {})
    if not isinstance(authority, dict):
        authority = {}
    for field in owns:
        if authority.get(field) != "host":
            missing.append(f"single host authority: {field}")
    return {"declaredCompatible": not missing, "missing": missing,
            "hostId": host.get("id"), "gameplayProofRequired": True}


def inspect_package(folder: str | Path) -> dict:
    root = Path(folder).expanduser().resolve()
    manifest = _json(_file(root, "remix-package.json"))
    if manifest.get("schema") != "remix-package/1":
        raise RemixError("Unsupported remix package schema")
    files = manifest.get("files")
    if not isinstance(files, dict) or not files or len(files) > 2000:
        raise RemixError("Manifest needs 1–2000 source files")
    failures, total = [], 0
    for name, expected in files.items():
        if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise RemixError("File hashes must be lowercase SHA256")
        path = _file(root, name)
        total += path.stat().st_size
        if total > 64 * 1024 * 1024:
            raise RemixError("Source package exceeds the 64 MiB budget")
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            failures.append(name)
    for key in ("recipe", "host", "entry"):
        if manifest.get(key) not in files:
            raise RemixError(f"Manifest must hash its {key}")
    if failures:
        raise RemixError("Integrity mismatch: " + ", ".join(failures))
    recipe = _json(_file(root, manifest["recipe"]))
    host = _json(_file(root, manifest["host"]))
    if recipe.get("schema") != "remix-recipe/1":
        raise RemixError("Unsupported remix recipe schema")
    match = match_host(recipe, host)
    return {"packageStatus": "VERIFIED_AGAINST_MANIFEST", "id": recipe.get("id"),
            "version": recipe.get("version"), "sourceLicense": manifest.get("sourceLicense"),
            "sourceFiles": len(files), "sourceBytes": total,
            "integrityIsAuthenticity": False, "hostMatch": match,
            "gameplay": "NOT_RUN", "nativeHooks": "NOT_PROVEN"}


def run_tests(folder: str | Path, trust_code: bool, timeout: int = 90) -> dict:
    if not trust_code:
        raise RemixError("Tests execute contributed code. Review it, then pass --trust-code. This is not a sandbox.")
    if not 1 <= timeout <= 300:
        raise RemixError("Test timeout must be 1–300 seconds")
    report = inspect_package(folder)
    if not report["hostMatch"]["declaredCompatible"]:
        raise RemixError("Host declaration is incompatible")
    root = Path(folder).expanduser().resolve()
    # Fixed runner, no commands supplied by recipe. Imports and tests still run
    # arbitrary code: the trust flag is an acknowledgement, not containment.
    manifest = _json(root / "remix-package.json")
    if "node-register.mjs" not in manifest["files"]:
        raise RemixError("This runner requires the reviewed Node import hook")
    tests = sorted(name for name in manifest["files"] if name.startswith("tests/") and name.endswith(".test.mjs"))
    if not tests:
        raise RemixError("No tests declared")
    node = shutil.which("node")
    if not node:
        raise RemixError("Node is missing; install the runtime required by the source README")
    started = time.monotonic()
    try:
        result = subprocess.run([node, "--import", "./node-register.mjs", "--test", *tests],
                                cwd=root, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        raise RemixError("Test process exceeded its timeout; gameplay remains unproven") from exc
    report.update(gameplay="TEST_PROCESS_PASSED" if result.returncode == 0 else "TEST_PROCESS_FAILED",
                  exitCode=result.returncode, elapsedSeconds=round(time.monotonic()-started, 3),
                  output=(result.stdout + result.stderr)[-200_000:],
                  humanPlaytest="NOT_TESTED", independentContributor="NOT_TESTED")
    return report


class PackageHandler(http.server.SimpleHTTPRequestHandler):
    """Only manifest-listed public source files; never directory listings."""
    def __init__(self, *args, root: Path, allowed: set[str], **kwargs):
        self.root, self.allowed = root, allowed
        super().__init__(*args, directory=str(root), **kwargs)

    def send_head(self):
        path = Path(self.translate_path(self.path)).resolve()
        if not path.is_relative_to(self.root):
            self.send_error(403)
            return None
        if path.is_dir():
            path = path / "index.html"
        if path.relative_to(self.root).as_posix() not in self.allowed:
            self.send_error(404)
            return None
        return super().send_head()

    def list_directory(self, path):
        self.send_error(404)
        return None


def serve(folder: str | Path, port: int, seconds: int, trust_code: bool):
    if not trust_code:
        raise RemixError("Review the browser code, then pass --trust-code. Serving is not sandboxing.")
    if not 1 <= seconds <= 3600 or not 0 <= port <= 65535:
        raise RemixError("Use a 1–3600 second session and valid port")
    report = inspect_package(folder)
    if not report["hostMatch"]["declaredCompatible"]:
        raise RemixError("Host declaration is incompatible")
    root = Path(folder).expanduser().resolve()
    manifest = _json(root / "remix-package.json")
    allowed = set(manifest["files"])
    handler = functools.partial(PackageHandler, root=root, allowed=allowed)
    with http.server.ThreadingHTTPServer(("127.0.0.1", port), handler) as server:
        server.timeout = 0.5
        print(f"Play: http://127.0.0.1:{server.server_port}/{manifest['entry']}", flush=True)
        print(f"Local session expires after {seconds}s. Ctrl+C stops it. No public server or game hook was created.", flush=True)
        deadline = time.monotonic() + seconds
        try:
            while time.monotonic() < deadline:
                server.handle_request()
        except KeyboardInterrupt:
            pass


def _command(args):
    try:
        if args.action == "match":
            result = match_host(_json(Path(args.recipe)), _json(Path(args.host)))
        elif args.action == "test":
            result = run_tests(args.folder, args.trust_code, args.timeout)
        elif args.action == "serve":
            serve(args.folder, args.port, args.seconds, args.trust_code)
            return
        else:
            result = inspect_package(args.folder)
        print(json.dumps(result, indent=2))
        if result.get("exitCode", 0) or result.get("declaredCompatible") is False or result.get("hostMatch", {}).get("declaredCompatible") is False:
            raise SystemExit(1)
    except (RemixError, OSError, ValueError) as exc:
        raise SystemExit(f"Remix check failed: {exc}") from exc


def register(sub):
    group = sub.add_parser("remix", help=__doc__, description=__doc__)
    commands = group.add_subparsers(dest="action")
    inspect = commands.add_parser("inspect", help="Verify source hashes and inspect declared capabilities")
    inspect.add_argument("folder")
    match = commands.add_parser("match", help="Compare host declarations; does not prove gameplay")
    match.add_argument("recipe")
    match.add_argument("host")
    test = commands.add_parser("test", help="Run reviewed Node acceptance tests (not a sandbox)")
    test.add_argument("folder")
    test.add_argument("--trust-code", action="store_true")
    test.add_argument("--timeout", type=int, default=90)
    preview = commands.add_parser("serve", help="Serve reviewed browser source on localhost only")
    preview.add_argument("folder")
    preview.add_argument("--trust-code", action="store_true")
    preview.add_argument("--port", type=int, default=8849)
    preview.add_argument("--seconds", type=int, default=900)
    for parser in (inspect, match, test, preview):
        parser.set_defaults(func=_command)
