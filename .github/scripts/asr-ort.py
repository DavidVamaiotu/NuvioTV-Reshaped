#!/usr/bin/env python3
"""Prepare portable ORT models and compare fresh-process Sherpa startup against ONNX.

Tools only: pip install onnxruntime==1.28.0 sherpa-onnx==1.13.8 numpy
The app uses Sherpa 1.13.8 / ORT 1.28.2; the benchmark checks loading with that
native runtime. This is not an Android benchmark and does not flush OS file caches.
"""

import argparse
import hashlib
import json
import platform
import random
import resource
import shutil
import statistics
import subprocess
import sys
import time
import wave
from pathlib import Path


PARTS = ("encoder", "decoder", "joiner")
SOURCE_REVISION = "f7515a672ffb69232794aaaa44a0a360f7f25cd1"
SOURCE_SHA256 = {
    "encoder.int8.onnx": "d3eff4b1bd747bd781a47966795988539227d05638524b0313f26d3a166962d7",
    "decoder.int8.onnx": "2b0580feb757696fa929922670b7126aaee609630349a1809fd5a2aee926d56e",
    "joiner.int8.onnx": "80160e45cca71dd52f6b0a6d3d12be18126f5308b2d4ba03f001300fea377c64",
    "tokens.txt": "0ef7d736bf4de3ef947292e4b119ef13f6808cd5f3aec225a843a7135ac1c2ce",
}


def prepare(source, output):
    import onnxruntime as ort

    if ort.__version__ != "1.28.0":
        raise RuntimeError("Rebuild with the pinned onnxruntime==1.28.0 converter")
    if source == output:
        raise ValueError("Use a separate output directory")
    for name, expected in SOURCE_SHA256.items():
        if hashlib.sha256((source / name).read_bytes()).hexdigest() != expected:
            raise ValueError(f"Source checksum mismatch: {name}")
    output.mkdir(parents=True, exist_ok=True)
    for part in PARTS:
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 2
        # Extended optimizations avoid hardware-dependent layout transformations.
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED
        options.optimized_model_filepath = str(output / f"{part}.int8.ort")
        options.add_session_config_entry("session.save_model_format", "ORT")
        original = ort.InferenceSession(str(source / f"{part}.int8.onnx"), options,
                                        providers=["CPUExecutionProvider"])
        check_options = ort.SessionOptions()
        check_options.intra_op_num_threads = 2
        check_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
        converted = ort.InferenceSession(str(output / f"{part}.int8.ort"), check_options,
                                         providers=["CPUExecutionProvider"])
        if original.get_modelmeta().custom_metadata_map != converted.get_modelmeta().custom_metadata_map:
            raise ValueError(f"Sherpa model metadata was lost: {part}")
    shutil.copyfile(source / "tokens.txt", output / "tokens.txt")
    (output / "cpu.conf").write_text("GraphOptimizationLevel=0\n")
    manifest = {"converter_ort": ort.__version__, "optimization": "extended",
                "provider": "CPUExecutionProvider", "source_revision": SOURCE_REVISION,
                "source_sha256": SOURCE_SHA256, "metadata_preserved": True, "files": {}}
    for path in sorted(output.iterdir()):
        if path.suffix == ".ort" or path.name == "tokens.txt":
            manifest["files"][path.name] = {
                "bytes": path.stat().st_size,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


def worker(directory, extension, wavs):
    started = time.perf_counter()
    import numpy as np
    import sherpa_onnx

    if sherpa_onnx.__version__ != "1.13.8" or sherpa_onnx.onnxruntime_version != "1.28.2":
        raise RuntimeError("Benchmark against the app's Sherpa 1.13.8 / ORT 1.28.2 runtime")
    imported = time.perf_counter()
    recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=str(directory / f"encoder.int8.{extension}"),
        decoder=str(directory / f"decoder.int8.{extension}"),
        joiner=str(directory / f"joiner.int8.{extension}"),
        tokens=str(directory / "tokens.txt"),
        num_threads=2,
        model_type="transducer",
        decoding_method="greedy_search",
        provider="cpu" if extension == "onnx" else f"cpu:{directory / 'cpu.conf'}",
    )
    loaded = time.perf_counter()
    result = {
        "format": extension, "import_seconds": imported - started,
        "load_seconds": loaded - imported, "import_and_load_seconds": loaded - started,
        "sherpa": sherpa_onnx.__version__, "runtime_ort": sherpa_onnx.onnxruntime_version,
        "samples": [],
    }
    for path in wavs:
        with wave.open(str(path)) as audio:
            if audio.getnchannels() != 1 or audio.getsampwidth() != 2:
                raise ValueError(f"{path}: expected mono signed 16-bit PCM WAV")
            rate = audio.getframerate()
            samples = np.frombuffer(audio.readframes(audio.getnframes()), dtype=np.int16)
            samples = samples.astype(np.float32) / 32768
        stream = recognizer.create_stream()
        before_decode = time.perf_counter()
        stream.accept_waveform(rate, samples)
        recognizer.decode_stream(stream)
        result["samples"].append({
            "file": path.name, "audio_seconds": len(samples) / rate,
            "decode_seconds": time.perf_counter() - before_decode,
            "tokens": stream.result.tokens, "timestamps": stream.result.timestamps,
        })
    result["peak_rss_kib"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return result


def benchmark(source, output, wavs, repeats):
    runs = []
    order = [extension for extension in ("onnx", "ort") for _ in range(repeats)]
    random.Random(0).shuffle(order)
    for extension in order:
        directory = source if extension == "onnx" else output
        command = [sys.executable, str(Path(__file__).resolve()), "worker", str(directory),
                   "--extension", extension, *[str(w) for w in wavs]]
        process = subprocess.run(command, check=True, capture_output=True, text=True)
        runs.append(json.loads(process.stdout))
    summary = {}
    for extension in ("onnx", "ort"):
        group = [r for r in runs if r["format"] == extension]
        summary[extension] = {
            "runs": len(group),
            "median_load_seconds": statistics.median(r["load_seconds"] for r in group),
            "max_load_seconds": max(r["load_seconds"] for r in group),
            "median_import_and_load_seconds": statistics.median(r["import_and_load_seconds"] for r in group),
            "max_peak_rss_kib": max(r["peak_rss_kib"] for r in group),
            "median_decode_seconds": statistics.median(
                sum(s["decode_seconds"] for s in r["samples"]) for r in group),
        }
    reference = next(r for r in runs if r["format"] == "onnx")
    tokens_match = True
    max_delta = 0.0
    for run in runs:
        for expected, actual in zip(reference["samples"], run["samples"]):
            if expected["tokens"] != actual["tokens"]:
                tokens_match = False
            elif expected["timestamps"]:
                max_delta = max(max_delta, max(abs(x-y) for x, y in
                                              zip(expected["timestamps"], actual["timestamps"])))
    return {
        "platform": platform.platform(), "machine": platform.machine(),
        "conditions": "Fresh processes; OS file cache not flushed; not an Android device",
        "summary": summary, "tokens_match": tokens_match,
        "maximum_token_timestamp_delta_seconds": max_delta, "runs": runs,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    preparation = sub.add_parser("prepare")
    preparation.add_argument("source", type=Path)
    preparation.add_argument("output", type=Path)
    comparison = sub.add_parser("benchmark")
    comparison.add_argument("source", type=Path)
    comparison.add_argument("output", type=Path)
    comparison.add_argument("wavs", type=Path, nargs="+")
    comparison.add_argument("--repeats", type=int, default=5)
    native = sub.add_parser("worker")
    native.add_argument("directory", type=Path)
    native.add_argument("--extension", choices=["onnx", "ort"], required=True)
    native.add_argument("wavs", type=Path, nargs="*")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.source.resolve(), args.output.resolve())
    elif args.action == "benchmark":
        if args.repeats < 1:
            parser.error("--repeats must be positive")
        print(json.dumps(benchmark(args.source.resolve(), args.output.resolve(),
                                   [w.resolve() for w in args.wavs], args.repeats), indent=2))
    else:
        print(json.dumps(worker(args.directory, args.extension, args.wavs)))


if __name__ == "__main__":
    main()
