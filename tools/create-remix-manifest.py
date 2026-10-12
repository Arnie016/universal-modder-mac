#!/usr/bin/env python3
"""Hash a reviewed source package. Does not certify licence, safety or authenticity."""
import argparse
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("folder", type=Path)
parser.add_argument("--source-license", required=True)
args = parser.parse_args()
root = args.folder.expanduser().resolve()
files = {}
for path in sorted(root.rglob("*")):
    relative = path.relative_to(root)
    if any(part.startswith(".") or part in ("node_modules", "proof", "__pycache__") for part in relative.parts):
        continue
    if path.is_symlink():
        raise SystemExit("Source packages must not contain symlinks")
    if path.is_file() and path.name not in ("remix-package.json", "source.zip"):
        files[relative.as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
for required in ("recipe.json", "host.json", "index.html", "LICENSE"):
    if required not in files:
        raise SystemExit(f"Missing required source file: {required}")
manifest = {"schema": "remix-package/1", "recipe": "recipe.json", "host": "host.json",
            "entry": "index.html", "sourceLicense": args.source_license, "files": files}
(root / "remix-package.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
print(f"Hashed {len(files)} source files. This is an integrity manifest, not a signature.")
