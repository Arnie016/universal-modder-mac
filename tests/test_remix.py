import functools
import hashlib
import http.server
import json
import threading
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import urlopen

import pytest

from um import remix


EXAMPLE = Path(__file__).resolve().parents[1] / "examples/tactics-possession"


def fixture(tmp):
    recipe = {"schema": "remix-recipe/1", "id": "fixture", "version": "1",
              "requires": {"tickHz": 60, "distanceUnit": "metre"},
              "capabilities": ["walk", "attack"], "host": {"owns": ["damage", "collision"]}}
    host = {"id": "fixture-host", "tickHz": 60, "distanceUnit": "metre",
            "commands": ["walk", "attack"], "authority": {"damage": "host", "collision": "host"}}
    for name, value in {"recipe.json": json.dumps(recipe), "host.json": json.dumps(host),
                        "index.html": "<h1>Fixture</h1>", "LICENSE": "Example notice"}.items():
        (tmp / name).write_text(value)
    manifest = {"schema": "remix-package/1", "recipe": "recipe.json", "host": "host.json",
                "entry": "index.html", "sourceLicense": "TEST",
                "files": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in tmp.iterdir()}}
    (tmp / "remix-package.json").write_text(json.dumps(manifest))
    return recipe, host, manifest


def test_real_example_records_limits_without_promoting_claims():
    report = remix.inspect_package(EXAMPLE)
    assert report["sourceLicense"] == "GPL-2.0-or-later"
    assert report["hostMatch"]["declaredCompatible"] is True
    assert report["hostMatch"]["gameplayProofRequired"] is True
    assert report["gameplay"] == "NOT_RUN"
    assert report["integrityIsAuthenticity"] is False
    assert report["nativeHooks"] == "NOT_PROVEN"


@pytest.mark.parametrize("field,value", [("tickHz", 30), ("tickHz", True), ("distanceUnit", "block"),
                                         ("commands", ["walk"]), ("authority", {"damage": "donor", "collision": "host"})])
def test_contract_rejects_semantic_mismatch(tmp_path, field, value):
    recipe, host, _ = fixture(tmp_path)
    host[field] = value
    result = remix.match_host(recipe, host)
    assert not result["declaredCompatible"]
    assert result["missing"]


def test_source_change_fails_integrity(tmp_path):
    fixture(tmp_path)
    (tmp_path / "index.html").write_text("changed")
    with pytest.raises(remix.RemixError, match="Integrity mismatch"):
        remix.inspect_package(tmp_path)


@pytest.mark.parametrize("name", ["../escape", "/absolute", "x/../escape", "x//alias", "x\\alias"])
def test_manifest_paths_do_not_expand_scope(tmp_path, name):
    _, _, manifest = fixture(tmp_path)
    manifest["files"][name] = "0" * 64
    (tmp_path / "remix-package.json").write_text(json.dumps(manifest))
    with pytest.raises(remix.RemixError):
        remix.inspect_package(tmp_path)


def test_symlink_cannot_escape_package(tmp_path):
    fixture(tmp_path)
    outside = tmp_path.parent / "outside.html"
    outside.write_text("secret")
    (tmp_path / "index.html").unlink()
    (tmp_path / "index.html").symlink_to(outside)
    with pytest.raises(remix.RemixError, match="symlink"):
        remix.inspect_package(tmp_path)


def test_execution_gate_precedes_subprocess(monkeypatch):
    def forbidden(*a, **k):
        raise AssertionError("Untrusted code was executed")
    monkeypatch.setattr(remix.subprocess, "run", forbidden)
    with pytest.raises(remix.RemixError, match="trust-code"):
        remix.run_tests(EXAMPLE, False)
    with pytest.raises(remix.RemixError, match="trust-code"):
        remix.serve(EXAMPLE, 0, 1, False)


def test_serve_allows_entry_but_blocks_unlisted_private_files(tmp_path):
    fixture(tmp_path)
    (tmp_path / ".env").write_text("private")
    (tmp_path / "unlisted.txt").write_text("private")
    handler = functools.partial(remix.PackageHandler, root=tmp_path, allowed={"index.html"})
    with http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            assert urlopen(base + "/").read() == b"<h1>Fixture</h1>"
            assert urlopen(base + "/index.html").status == 200
            for name in (".env", "unlisted.txt", "missing/", "../outside.html"):
                with pytest.raises(HTTPError):
                    urlopen(base + "/" + name)
        finally:
            server.shutdown()
            thread.join(timeout=2)


def test_metadata_cannot_supply_a_runner_command(tmp_path, monkeypatch):
    fixture(tmp_path)
    with pytest.raises(remix.RemixError, match="import hook"):
        remix.run_tests(tmp_path, True)
