"""Keep one stopped-world backup per mod version before the local launcher opens it."""
from pathlib import Path
import datetime
import fcntl
import hashlib
import json
import sys
import zipfile


def prepare(root: Path) -> Path | None:
    world = root / "run-challenge/saves/Sling Siege Challenge"
    if not (world / "level.dat").exists():
        return None
    version = dict(line.split("=", 1) for line in (root / "gradle.properties").read_text().splitlines() if "=" in line)["version"]
    # Java's session lock is a POSIX file lock on this Mac. Do not snapshot an active writer.
    with (world / "session.lock").open("a+b") as lock:
        try:
            fcntl.lockf(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Minecraft is still using this world. Save and Quit, close that game window, then open this launcher again.") from None
        backups = root / "run-challenge/backups"
        receipt = backups / f"before-{version}.json"
        if receipt.exists():
            previous = json.loads(receipt.read_text())
            archive = backups / previous["file"]
            if archive.exists() and hashlib.sha256(archive.read_bytes()).hexdigest() == previous["sha256"]:
                return archive
        backups.mkdir(parents=True, exist_ok=True)
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d-%H%M%S")
        archive = backups / f"world-before-{version}-{stamp}.zip"
        temporary = archive.with_suffix(".partial")
        try:
            with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as z:
                for file in sorted(world.rglob("*")):
                    if file.is_symlink():
                        raise RuntimeError("A world file is a symbolic link; preserve it separately before updating.")
                    if file.is_file() and file.name != "session.lock":
                        z.write(file, Path(world.name) / file.relative_to(world))
            with zipfile.ZipFile(temporary) as z:
                if z.testzip() is not None:
                    raise RuntimeError("The world backup did not verify; the game was not launched.")
            temporary.replace(archive)
            value = {"file": archive.name, "version": version, "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(), "state": "stopped-world-verified"}
            receipt_tmp = receipt.with_suffix(".tmp")
            receipt_tmp.write_text(json.dumps(value, indent=2) + "\n")
            receipt_tmp.replace(receipt)
        finally:
            temporary.unlink(missing_ok=True)
        return archive


if __name__ == "__main__":
    try:
        result = prepare(Path(__file__).resolve().parents[1])
        if result:
            print(f"World backup verified: {result}")
    except (OSError, ValueError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
