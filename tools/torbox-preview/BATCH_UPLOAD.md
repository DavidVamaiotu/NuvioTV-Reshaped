# One torrent for all ten movies

The thumbnail results must include ten qualified full-movie packs. The builder
selects the latest qualified pack for each explicit mapping, validates every
sprite hash, and creates one torrent with individual sprite files and a root
catalog.json. The old Inception-only torrent is not modified.

From the thumbnail project directory:

```powershell
.\.venv\Scripts\python.exe tv-seek-preview\tools\torbox-preview\prepare_batch.py --mapping tv-seek-preview\tools\torbox-preview\famous-ten.json --batch famous-ten-v1
```

The torrent is publish/famous-ten/NuvioPreviews-famous-ten-v1.torrent.
In qBittorrent set Save at to the absolute publish/famous-ten directory.
Wait for 100% / Seeding, then add the .torrent to TorBox with allow_zip=false
so the 315 individual files remain available. upload.py sets this explicitly:

```powershell
.\.venv\Scripts\python.exe tv-seek-preview\tools\torbox-preview\upload.py publish/famous-ten/NuvioPreviews-famous-ten-v1.torrent
```

TORBOX_API_KEY is read from the invoking session's environment. It is never
saved or printed. The add request is attempted once. A 429 stops and reports
Retry-After; a connection failure requires checking the dashboard before retrying.

After verifying TorBox has finished, mark the catalog ready:

```powershell
.\.venv\Scripts\python.exe tv-seek-preview\tools\torbox-preview\prepare.py --output publish/famous-ten --mark-ready BATCH_INFO_HASH
```

To append more movies, create another mapping JSON and a new --batch name,
using the same --output publish/famous-ten. All previous catalog entries and
torrent files remain. Replacing an existing release requires a higher revision
in the new mapping. Never edit the contents of a folder already being seeded.

IMDb mappings are explicit movie identities; filenames/source torrent names are
not independent proof of identity. Source-release hashes, file paths and video
durations are included so the TV can separately assess timeline alignment.
