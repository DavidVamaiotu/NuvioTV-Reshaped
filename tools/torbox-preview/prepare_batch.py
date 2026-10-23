"""Prepare several completed movies in one immutable torrent; append future batches separately."""
import argparse
import json
from pathlib import Path
import re
import shutil
import tempfile

from prepare import atomic_json, build_torrent, prepare, read_json


def prepare_batch(results_root, inventory, output, movies, batch):
    if not movies:
        raise ValueError("Supply at least one movie mapping")
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}", batch):
        raise ValueError("Invalid batch name")
    if len({m["imdb_id"] for m in movies}) != len(movies):
        raise ValueError("Movie IDs must be unique within the batch")
    name = "NuvioPreviews-" + batch
    folder = output / name
    torrent = output / (name + ".torrent")
    catalog_path = output / "catalog.json"
    catalog = read_json(catalog_path) if catalog_path.exists() else {"version": 1, "packs": [], "batches": []}
    if catalog.get("version") != 1 or not isinstance(catalog.get("packs"), list) or not isinstance(catalog.get("batches"), list):
        raise ValueError("Unsupported catalog format")
    if folder.exists() or torrent.exists() or any(b["name"] == name for b in catalog["batches"]):
        raise ValueError("Batch exists; choose a new batch name for additions")
    output.mkdir(parents=True, exist_ok=True)
    # Ordinary temporary staging is removed at the end. Existing published batches are never touched.
    with tempfile.TemporaryDirectory(prefix=".preview-stage-", dir=output) as temporary:
        staging = Path(temporary)
        packs = staging / "packs"
        prepared = []
        for number, movie in enumerate(movies):
            item = prepare(results_root, inventory, packs, movie["source_id"], movie["imdb_id"],
                           movie.get("tmdb_id"), movie.get("revision", 1), "stage-" + str(number))
            prepared.append(item)
        additions = read_json(packs / "catalog.json")["packs"]
        old_ids = {p["pack_id"] for p in catalog["packs"]}
        if any(p["pack_id"] in old_ids for p in additions):
            raise ValueError("A movie/release/revision already exists; use a new revision for replacements")
        combined = staging / name
        (combined / "movies").mkdir(parents=True)
        for item in prepared:
            for movie_dir in (Path(item["batch_directory"]) / "movies").iterdir():
                shutil.copytree(movie_dir, combined / "movies" / movie_dir.name)
        # Internal catalog has no circular reference to its own torrent hash.
        internal = {"version": 1, "packs": [{k: v for k, v in p.items()
                                            if k not in ("batch_info_hash", "status")} for p in additions]}
        atomic_json(combined / "catalog.json", internal)
        encoded, info_hash, files = build_torrent(combined)
        for entry in additions:
            entry["batch_info_hash"] = info_hash
        # Verify both paths before moving this newly created staging directory.
        if not combined.resolve().is_relative_to(output.resolve()) or folder.exists():
            raise ValueError("Unsafe staging destination")
        combined.rename(folder)
        torrent.write_bytes(encoded)
        catalog["packs"].extend(additions)
        catalog["batches"].append({"name": name, "info_hash": info_hash, "torrent_file": torrent.name,
                                   "file_count": len(files), "size_bytes": sum(f["length"] for f in files)})
        atomic_json(catalog_path, catalog)
    return {"movies": len(additions), "thumbnails": sum(p["thumbnails"] for p in prepared),
            "sprite_sheets": sum(p["sprite_sheets"] for p in prepared),
            "size_bytes": sum(f["length"] for f in files), "files": len(files),
            "torrent_file": str(torrent.resolve()), "seed_save_path": str(output.resolve()),
            "catalog": str(catalog_path.resolve()), "batch_info_hash": info_hash}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--batch", required=True)
    parser.add_argument("--results-root", type=Path, default=Path("results"))
    parser.add_argument("--inventory", type=Path, default=Path("inventory.json"))
    parser.add_argument("--output", type=Path, default=Path("publish/famous-ten"))
    args = parser.parse_args()
    try:
        print(json.dumps(prepare_batch(args.results_root, args.inventory, args.output,
                                       read_json(args.mapping), args.batch), indent=2))
    except (ValueError, KeyError, OSError) as exc:
        parser.exit(1, str(exc) + "\n")


if __name__ == "__main__":
    main()
