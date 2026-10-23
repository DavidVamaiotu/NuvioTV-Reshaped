# TorBox preview upload test

This directory prepares sprite assets; it does not change TV playback yet.

Run prepare.py with the Python from the thumbnail project, from that project's
working directory (where results/ and inventory.json exist):

```powershell
.\.venv\Scripts\python.exe tv-seek-preview\tools\torbox-preview\prepare.py --source-id inception-2010 --imdb tt1375666 --tmdb 27205 --batch inception-v1
```

The output is publish/NuvioPreviews-inception-v1/, the matching .torrent, and
publish/catalog.json. Only qualified full-movie packs are selected. Each index
retains timestamps, sprite rectangles and hashes, and gains an explicit movie
ID and original source torrent hash/file path. Generated TorBox URLs and
credentials do not belong in the catalog.

Add the .torrent to qBittorrent with Save at set to the absolute publish/
directory (the parent of NuvioPreviews-inception-v1). Keep the original folder
layout and let qBittorrent check the data. It should reach 100% and seed.
Add the same .torrent to TorBox and keep qBittorrent online until TorBox finishes.

## Append

Run the same command for another completed source with its explicitly checked
IMDb ID and a new --batch name. The script appends to catalog.json, keeping all
previous movies, releases, revisions and torrents. It refuses to reuse a batch
name or silently replace an existing movie/release/revision.

For a replacement of the same source release, use --revision 2 and a new batch
name. Do not modify the contents of a folder already being seeded. A new movie
or revision is distributed by a new torrent; it does not extend the old torrent.

Entries start as prepared. After verifying the batch has finished on TorBox:

```powershell
.\.venv\Scripts\python.exe tv-seek-preview\tools\torbox-preview\prepare.py --mark-ready BATCH_INFO_HASH
```

This edits only the catalog, which lives outside the immutable torrent folder.
A client can select ready entries, resolve account-local torrent/file IDs by
batch hash and paths, and request fresh download links when needed.
Publishing the catalog for TV discovery and integrating the track are separate
next steps. Prepared does not claim an upload or working TV playback.

Tests (pytest and Pillow required):

```powershell
.\.venv\Scripts\python.exe -m pytest tv-seek-preview\tools\torbox-preview\test_prepare.py -q
```
