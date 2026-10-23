import importlib.util
from pathlib import Path
from types import SimpleNamespace

import pytest

spec = importlib.util.spec_from_file_location("preview_upload", Path(__file__).with_name("upload.py"))
upload = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upload)


def test_upload_disables_zip_and_uses_header_auth(tmp_path, monkeypatch, capsys):
    torrent = tmp_path / "preview.torrent"
    torrent.write_bytes(b"fixture")
    monkeypatch.setenv("TORBOX_API_KEY", "test-secret")
    monkeypatch.setattr("sys.argv", ["upload.py", str(torrent)])
    calls = []

    def post(url, **kwargs):
        calls.append((url, kwargs))
        assert kwargs["files"]["file"][1].read() == b"fixture"
        return SimpleNamespace(status_code=200, ok=True, json=lambda: {"success": True, "data": {"torrent_id": 123}})

    monkeypatch.setattr(upload.requests, "post", post)
    upload.main()
    assert len(calls) == 1
    assert calls[0][1]["data"]["allow_zip"] == "false"
    assert calls[0][1]["headers"]["Authorization"] == "Bearer test-secret"
    assert "test-secret" not in capsys.readouterr().out


def test_upload_stops_on_429_without_retry(tmp_path, monkeypatch, capsys):
    torrent = tmp_path / "preview.torrent"
    torrent.write_bytes(b"fixture")
    monkeypatch.setenv("TORBOX_API_KEY", "test-secret")
    monkeypatch.setattr("sys.argv", ["upload.py", str(torrent)])
    calls = []

    def post(*args, **kwargs):
        calls.append(1)
        return SimpleNamespace(status_code=429, headers={"Retry-After": "300"})

    monkeypatch.setattr(upload.requests, "post", post)
    with pytest.raises(SystemExit) as error:
        upload.main()
    assert error.value.code == 1
    assert len(calls) == 1
    assert "300" in capsys.readouterr().err
