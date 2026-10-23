import hashlib
import importlib.util
import json
from pathlib import Path

import pytest
from PIL import Image

spec = importlib.util.spec_from_file_location("preview_prepare", Path(__file__).with_name("prepare.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def fixture(tmp_path):
    results = tmp_path / "results"
    run = results / "famous-ten-sprites-fixture"
    run.mkdir(parents=True)
    rows = []
    inventory = []
    for number in (1, 2):
        pack = run / str(number) / "pack"
        (pack / "sprites").mkdir(parents=True)
        sheet = pack / "sprites/sheet-000.webp"
        Image.new("RGB", (320, 180), (number * 50, 30, 100)).save(sheet, "WEBP")
        index = {"version": 1, "title": "Movie " + str(number), "year": 2000,
                 "duration_seconds": 3600, "interval_seconds": 10,
                 "sheets": [{"file": "sprites/sheet-000.webp", "size_bytes": sheet.stat().st_size,
                             "sha256": hashlib.sha256(sheet.read_bytes()).hexdigest(), "width": 320, "height": 180}],
                 "frames": [{"sheet": "sprites/sheet-000.webp", "xywh": [0, 0, 320, 180]}]}
        (pack / "index.json").write_text(json.dumps(index))
        rows.append({"source_id": str(number), "qualified": True, "duration_seconds": 3600,
                     "output_directory": str(number), "thumbnail_count": 1,
                     "sprite_pack": {"index": "pack/index.json"},
                     "source_identity": {"torrent_id": number, "file_id": 0}})
        inventory.append({"torrent_id": number, "file_id": 0, "hash": str(number) * 40,
                          "name": "movie-" + str(number) + ".mkv", "size": 1000000})
    (run / "results.json").write_text(json.dumps(rows))
    inv = tmp_path / "inventory.json"
    inv.write_text(json.dumps(inventory))
    return results, inv


def test_append_preserves_existing_catalog_and_seed_files(tmp_path):
    results, inv = fixture(tmp_path)
    output = tmp_path / "publish"
    first = m.prepare(results, inv, output, "1", "tt0000001", None, 1, "first")
    original_torrent = Path(first["torrent_file"]).read_bytes()
    original_catalog = m.read_json(output / "catalog.json")
    m.prepare(results, inv, output, "2", "tt0000002", None, 1, "second")
    appended = m.read_json(output / "catalog.json")
    assert appended["packs"][0] == original_catalog["packs"][0]
    assert appended["batches"][0] == original_catalog["batches"][0]
    assert len(appended["packs"]) == 2
    assert Path(first["torrent_file"]).read_bytes() == original_torrent
    assert appended["packs"][0]["status"] == "prepared"
    with pytest.raises(ValueError, match="already cataloged"):
        m.prepare(results, inv, output, "1", "tt0000001", None, 1, "duplicate")
    assert m.read_json(output / "catalog.json") == appended


def test_revised_pack_preserves_old_revision(tmp_path):
    results, inv = fixture(tmp_path)
    output = tmp_path / "publish"
    first = m.prepare(results, inv, output, "1", "tt0000001", None, 1, "first")
    old = Path(first["torrent_file"]).read_bytes()
    m.prepare(results, inv, output, "1", "tt0000001", None, 2, "updated")
    catalog = m.read_json(output / "catalog.json")
    assert [p["revision"] for p in catalog["packs"]] == [1, 2]
    assert Path(first["torrent_file"]).read_bytes() == old


def decode(data, offset=0):
    tag = data[offset:offset + 1]
    if tag == b"i":
        end = data.index(b"e", offset)
        return int(data[offset + 1:end]), end + 1
    if tag in (b"l", b"d"):
        value = [] if tag == b"l" else {}
        offset += 1
        while data[offset:offset + 1] != b"e":
            item, offset = decode(data, offset)
            if tag == b"l":
                value.append(item)
            else:
                member, offset = decode(data, offset)
                value[item] = member
        return value, offset + 1
    colon = data.index(b":", offset)
    size = int(data[offset:colon])
    return data[colon + 1:colon + 1 + size], colon + 1 + size


def test_torrent_piece_hashes_cross_file_boundaries(tmp_path):
    folder = tmp_path / "batch"
    folder.mkdir()
    a = b"a" * 200000
    b = b"b" * 200000
    (folder / "a.bin").write_bytes(a)
    (folder / "b.bin").write_bytes(b)
    encoded, info_hash, entries = m.build_torrent(folder)
    parsed, end = decode(encoded)
    assert end == len(encoded)
    info = parsed[b"info"]
    assert info[b"files"] == [{b"length": 200000, b"path": [b"a.bin"]},
                               {b"length": 200000, b"path": [b"b.bin"]}]
    content = a + b
    assert info[b"pieces"] == b"".join(hashlib.sha1(content[i:i + 262144]).digest()
                                      for i in range(0, len(content), 262144))
    assert info_hash == hashlib.sha1(m.bencode(info)).hexdigest()


def test_invalid_sheet_leaves_catalog_unchanged(tmp_path):
    results, inv = fixture(tmp_path)
    output = tmp_path / "publish"
    m.prepare(results, inv, output, "1", "tt0000001", None, 1, "first")
    old = (output / "catalog.json").read_bytes()
    (results / "famous-ten-sprites-fixture/2/pack/sprites/sheet-000.webp").write_bytes(b"corrupt")
    with pytest.raises(ValueError, match="integrity"):
        m.prepare(results, inv, output, "2", "tt0000002", None, 1, "second")
    assert (output / "catalog.json").read_bytes() == old
    assert not (output / "NuvioPreviews-second").exists()
