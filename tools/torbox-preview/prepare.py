"""Prepare immutable TorBox sprite batches and append entries to a local catalog.

Run with the thumbnail project's Python. No credentials, uploads, or existing-pack deletion.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import tempfile


TRACKERS = [
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.demonii.com:1337/announce",
]


def bencode(value):
    if isinstance(value, int):
        return b"i" + str(value).encode() + b"e"
    if isinstance(value, str):
        value = value.encode("utf-8")
    if isinstance(value, bytes):
        return str(len(value)).encode() + b":" + value
    if isinstance(value, list):
        return b"l" + b"".join(bencode(v) for v in value) + b"e"
    if isinstance(value, dict):
        return b"d" + b"".join(bencode(k) + bencode(value[k]) for k in sorted(value)) + b"e"
    raise TypeError(type(value).__name__)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=path.name + "-", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, indent=2, ensure_ascii=False)
            stream.write("\n")
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def inside(root, relative):
    candidate = (root / relative).resolve()
    if not candidate.is_relative_to(root.resolve()):
        raise ValueError("Pack path leaves its directory")
    return candidate


def build_torrent(folder):
    files = sorted(p for p in folder.rglob("*") if p.is_file())
    if not files:
        raise ValueError("Empty batch")
    piece_length = 256 * 1024
    hashes = []
    buffered = bytearray()
    entries = []
    for path in files:
        entries.append({"length": path.stat().st_size, "path": list(path.relative_to(folder).parts)})
        with path.open("rb") as stream:
            while chunk := stream.read(piece_length - len(buffered)):
                buffered.extend(chunk)
                if len(buffered) == piece_length:
                    hashes.append(hashlib.sha1(buffered).digest())
                    buffered.clear()
    if buffered:
        hashes.append(hashlib.sha1(buffered).digest())
    info = {"name": folder.name, "piece length": piece_length,
            "pieces": b"".join(hashes), "files": entries}
    info_hash = hashlib.sha1(bencode(info)).hexdigest()
    metainfo = {"announce": TRACKERS[0], "announce-list": [[v] for v in TRACKERS],
                "created by": "Nuvio TV sprite preview test", "info": info}
    return bencode(metainfo), info_hash, entries


def find_completed(results_root, source_id):
    candidates = []
    for path in results_root.glob("famous-ten-sprites-*/results.json"):
        for result in read_json(path):
            if result.get("source_id") != source_id or not result.get("qualified"):
                continue
            # Old runs may include clips that were wrongly accepted as movies.
            if result.get("duration_seconds", 0) < 1800:
                continue
            pack = result.get("sprite_pack", {})
            if not pack.get("index"):
                continue
            directory = inside(path.parent, result["output_directory"])
            index = inside(directory, pack["index"])
            if index.is_file():
                candidates.append((path.parent.name, result, index))
    if not candidates:
        raise ValueError("No completed full-movie sprite pack for " + source_id)
    return max(candidates, key=lambda c: c[0])[1:]


def prepare(results_root, inventory_path, output, source_id, imdb_id, tmdb_id, revision, batch):
    if not re.fullmatch(r"tt\d{7,10}", imdb_id):
        raise ValueError("Supply an explicit IMDb ID")
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}", batch):
        raise ValueError("Batch name must contain only letters, numbers, dots, dashes or underscores")
    if revision < 1:
        raise ValueError("Pack revision must be positive")
    result, original_index = find_completed(results_root, source_id)
    original = read_json(original_index)
    identity = result["source_identity"]
    matches = [r for r in read_json(inventory_path)
               if r["torrent_id"] == identity["torrent_id"] and r["file_id"] == identity["file_id"]]
    if len(matches) != 1 or not re.fullmatch(r"[0-9a-fA-F]{40}", matches[0].get("hash", "")):
        raise ValueError("Inventory must identify the original movie torrent and exact file")
    movie = matches[0]
    source_hash = movie["hash"].lower()
    source_file = movie["name"]
    release = source_hash + "-f" + str(identity["file_id"])
    pack_id = imdb_id + ":" + release + ":v" + str(revision)
    relative_index = Path("movies") / imdb_id / release / ("v" + str(revision)) / "index.json"
    catalog_path = output / "catalog.json"
    catalog = read_json(catalog_path) if catalog_path.exists() else {"version": 1, "packs": [], "batches": []}
    if catalog.get("version") != 1 or not isinstance(catalog.get("packs"), list) or not isinstance(catalog.get("batches"), list):
        raise ValueError("Unsupported catalog format")
    if any(p.get("pack_id") == pack_id for p in catalog["packs"]):
        raise ValueError("This movie/release/revision is already cataloged; preserve it and use a higher revision for a replacement")
    batch_name = "NuvioPreviews-" + batch
    folder = output / batch_name
    torrent_path = output / (batch_name + ".torrent")
    if folder.exists() or torrent_path.exists() or any(b.get("name") == batch_name for b in catalog["batches"]):
        raise ValueError("Batch already exists; use a new batch name when appending")
    # Validate every image before publishing any catalog reference.
    for sheet in original["sheets"]:
        path = inside(original_index.parent, sheet["file"])
        if path.stat().st_size != sheet["size_bytes"] or digest(path) != sheet["sha256"]:
            raise ValueError("Sprite integrity check failed")
    if len(original["frames"]) != result["thumbnail_count"]:
        raise ValueError("Frame count differs from the qualified result")
    if not math.isfinite(original["duration_seconds"]) or original["duration_seconds"] <= 0:
        raise ValueError("Invalid video duration")
    known_sheets = {s["file"]: s for s in original["sheets"]}
    for frame in original["frames"]:
        sheet = known_sheets.get(frame["sheet"])
        x, y, w, h = frame["xywh"]
        if sheet is None or min(x, y) < 0 or min(w, h) <= 0 or x + w > sheet["width"] or y + h > sheet["height"]:
            raise ValueError("Invalid sprite rectangle")
    output.mkdir(parents=True, exist_ok=True)
    folder.mkdir()
    destination = folder / relative_index
    destination.parent.mkdir(parents=True)
    for sheet in original["sheets"]:
        copied = inside(destination.parent, sheet["file"])
        copied.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(inside(original_index.parent, sheet["file"]), copied)
    index = dict(original)
    index.update({"imdb_id": imdb_id, "tmdb_id": tmdb_id, "pack_revision": revision,
                  "pack_id": pack_id,
                  "source_identity": {**identity, "info_hash": source_hash,
                                      "file_path": source_file, "size_bytes": movie["size"]}})
    atomic_json(destination, index)
    torrent_bytes, info_hash, files = build_torrent(folder)
    torrent_path.write_bytes(torrent_bytes)
    entry = {"pack_id": pack_id, "imdb_id": imdb_id, "tmdb_id": tmdb_id,
             "title": original["title"], "year": original["year"], "revision": revision,
             "source_info_hash": source_hash, "source_file_path": source_file,
             "source_file_size_bytes": movie["size"], "video_duration_ms": round(original["duration_seconds"] * 1000),
             "batch_info_hash": info_hash, "index_path": relative_index.as_posix(),
             "index_sha256": digest(destination), "status": "prepared"}
    # Append, never replace: older movies, releases and revisions remain available.
    catalog["packs"].append(entry)
    catalog["batches"].append({"name": batch_name, "info_hash": info_hash,
                               "torrent_file": torrent_path.name, "file_count": len(files),
                               "size_bytes": sum(f["length"] for f in files)})
    atomic_json(catalog_path, catalog)
    return {"movie": original["title"], "thumbnails": len(original["frames"]),
            "sprite_sheets": len(original["sheets"]), "batch_directory": str(folder.resolve()),
            "torrent_file": str(torrent_path.resolve()), "seed_save_path": str(output.resolve()),
            "catalog": str(catalog_path.resolve()), "batch_info_hash": info_hash,
            "files": len(files), "size_bytes": sum(f["length"] for f in files)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-root", type=Path, default=Path("results"))
    parser.add_argument("--inventory", type=Path, default=Path("inventory.json"))
    parser.add_argument("--output", type=Path, default=Path("publish"))
    parser.add_argument("--source-id")
    parser.add_argument("--imdb")
    parser.add_argument("--tmdb", type=int)
    parser.add_argument("--revision", type=int, default=1)
    parser.add_argument("--batch")
    parser.add_argument("--mark-ready", metavar="BATCH_INFO_HASH",
                        help="After verifying TorBox finished this batch, mark its catalog entries ready")
    args = parser.parse_args()
    try:
        if args.mark_ready:
            path = args.output / "catalog.json"
            catalog = read_json(path)
            entries = [p for p in catalog["packs"] if p["batch_info_hash"] == args.mark_ready.lower()]
            if not entries:
                raise ValueError("Unknown batch hash")
            for entry in entries:
                entry["status"] = "ready"
            atomic_json(path, catalog)
            print("Marked batch ready; all other catalog entries preserved.")
        else:
            if not all((args.source_id, args.imdb, args.batch)):
                parser.error("--source-id, --imdb and --batch are required when preparing a batch")
            print(json.dumps(prepare(args.results_root, args.inventory, args.output, args.source_id,
                                     args.imdb, args.tmdb, args.revision, args.batch), indent=2))
    except (ValueError, KeyError, OSError) as exc:
        parser.exit(1, str(exc) + "\n")


if __name__ == "__main__":
    main()
