"""Add a prepared preview torrent to TorBox without ZIP bundling. No automatic retries."""
import argparse
import os
from pathlib import Path

import requests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("torrent", type=Path)
    args = parser.parse_args()
    key = os.environ.get("TORBOX_API_KEY", "").strip()
    if not key:
        parser.exit(1, "Set TORBOX_API_KEY in this PowerShell session first.\n")
    if not args.torrent.is_file() or args.torrent.suffix.lower() != ".torrent":
        parser.exit(1, "Supply the prepared .torrent file.\n")
    try:
        with args.torrent.open("rb") as stream:
            response = requests.post("https://api.torbox.app/v1/api/torrents/createtorrent",
                headers={"Authorization": "Bearer " + key},
                files={"file": (args.torrent.name, stream, "application/x-bittorrent")},
                data={"allow_zip": "false", "seed": "2"}, timeout=(10, 60))
        if response.status_code == 429:
            wait = response.headers.get("Retry-After")
            parser.exit(1, "TorBox rate limited the upload; no retry attempted. Retry-After: " + str(wait or "not supplied") + "\n")
        if not response.ok:
            parser.exit(1, "TorBox returned HTTP " + str(response.status_code) + "; no retry attempted.\n")
        result = response.json()
        if result.get("success") is not True:
            parser.exit(1, "TorBox did not accept the torrent. Check the TorBox dashboard; no retry attempted.\n")
        data = result.get("data") or {}
        print("Preview torrent added with ZIP bundling disabled.")
        print("TorBox torrent ID:", data.get("torrent_id"))
        print("Keep qBittorrent seeding until TorBox shows this batch complete.")
    except requests.RequestException:
        parser.exit(1, "TorBox connection failed. Check the dashboard before repeating; the add may already have succeeded.\n")
    except ValueError:
        parser.exit(1, "TorBox returned an unreadable response. Check the dashboard before repeating.\n")


if __name__ == "__main__":
    main()
