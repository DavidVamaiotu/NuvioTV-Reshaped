# GigaSpeech ORT model for tester audio sync

## Compatibility and expected performance

The tester app pins Sherpa 1.13.8. Its AAR, SHA-256
`633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`,
contains ONNX Runtime 1.28.2 for arm64-v8a, armeabi-v7a, x86 and x86_64.
The native version was inspected in all four binaries. Sherpa's transducer
implementation passes file paths directly to `Ort::Session`. The existing
`modelType = "transducer"` avoids the extra model load used for type guessing.

ONNX Runtime accepts `.ort` model paths. The converted encoder, decoder and
joiner retain the original custom metadata, input/output names and dynamic shapes.
The original tokens file is unchanged. The app uses the same sample rate, feature
configuration, greedy decoding, thread count and BPE-to-word timestamp conversion.

Conversion uses `onnxruntime==1.28.0`, CPU execution and extended optimizations.
It avoids hardware-specific layout optimization, NNAPI partitioning, GPU providers
and reduced-operator runtime builds. Sherpa is configured with `GraphOptimizationLevel=0`
only for ORT files; ordinary weight prepacking remains enabled. Legacy ONNX files
keep their existing CPU configuration.

This should reduce initialization because the graph is optimized ahead of time
and saved in ORT's serialized format, rather than rebuilding it from ONNX protobuf
at every cold load. Storage reads, allocation, kernel initialization and weight
prepacking still happen. Neither a fixed speedup nor a load below ten seconds
is guaranteed. ORT does not substantially shrink this model.

## Native host validation

The local candidate files were loaded with Sherpa 1.13.8 and its native ONNX
Runtime 1.28.2. Five fresh-process runs per format, in mixed order,
decoded three upstream test clips. OS file caches were not flushed. These are
Linux x86_64 host results, **not Android or ARM device measurements**.

| Measurement | ONNX | ORT |
| --- | ---: | ---: |
| Median model load | 1.168 s | 0.620 s |
| Largest observed model load | 2.709 s | 0.738 s |
| Median library import plus model load | 1.226 s | 0.791 s |
| Median decoding of the three clips | 0.782 s | 1.098 s |
| Largest observed process RSS | 313.0 MiB | 304.1 MiB |

The median native load was about 1.9 times faster in this run. An earlier run
of the same conversion configuration measured about 2.7 times faster. Host load
and serialization/runtime differences prevent treating either number as a TV
prediction. Recognition was slower in the current run; faster initialization does
not imply faster decoding. Tokens matched on the sampled clips, and the largest
token timestamp difference was about 40 ms. Three clean speech clips do not
establish movie-dialogue accuracy or subtitle-offset equivalence.

The model-publishing workflow repeats native validation on the actual files it
publishes; its `host-benchmark.json` records that job's own measurements. The
table above records the local preparatory run, not the Actions runner. The source WAVs are
`1089-134686-0001.wav`, `1221-135766-0001.wav` and `1221-135766-0002.wav` from the
upstream model's `test_wavs/` directory.

The actual Android JNI libraries have not been executed on a TV in this workspace.
An instrumentation test, `AsrOrtCompatibilityTest`, loads and runs the installed
ORT model through Android JNI. Download the model in the target app first, then
run the test on each supported ABI. Until that happens, Android compatibility is
supported by the runtime API/source and native host test, not a completed TV test.

## Distribution and migration

The standalone model release is `asr-models-ort-v1` in `DavidVamaiotu/NuvioTV-Reshaped`.
It is a prerelease with `latest=false`; it is not an APK release or app update.
The app pins each downloaded file's size and SHA-256 in `AsrModelFiles.kt`.
Downloads are written to `.part`, hashed, and renamed only after verification.
Failures, truncation and checksum mismatches cannot replace a valid final file.
Readiness checks only stat the already-verified files, avoiding another 74 MB read
at each startup.

ORT files live in `audiosync/asr/gigaspeech-2023-12-12-ort-v1`. Existing downloads in
`audiosync/asr/gigaspeech-2023-12-12` remain untouched. The first ORT use needs a
one-time download of about 74 MB; network download time is separate from model
initialization. Automatic download still requires an unmetered connection. If
the download cannot complete or the network is metered, a complete existing ONNX
model remains usable. Without either model, the existing speech-detection fallback
remains available. Deleting the speech model from Settings removes both versions.

The statistics panel reports the loaded format. An already-cached ONNX recognizer
continues serving its users rather than being replaced mid-playback. The next
fresh model acquisition uses ORT; restarting the app also clears the old cache.

## Rebuild

Install `onnxruntime==1.28.0`, `sherpa-onnx==1.13.8` and `numpy` in an isolated
Python environment. Download the original files at upstream revision
`f7515a672ffb69232794aaaa44a0a360f7f25cd1`, using these local filenames:

- `encoder-epoch-30-avg-1.int8.onnx` as `encoder.int8.onnx`
- `decoder-epoch-30-avg-1.int8.onnx` as `decoder.int8.onnx`
- `joiner-epoch-30-avg-1.int8.onnx` as `joiner.int8.onnx`
- `tokens.txt` unchanged

```sh
python .github/scripts/asr-ort.py prepare /path/to/onnx /path/to/ort
python .github/scripts/asr-ort.py benchmark /path/to/onnx /path/to/ort /path/to/test1.wav /path/to/test2.wav /path/to/test3.wav --repeats 5 > host-benchmark.json
```

The script validates source hashes before conversion, checks retained metadata,
and emits `manifest.json` with output sizes and hashes. ORT serialization ordering
can change binary hashes across conversions; a rebuild must get a new model
release version and updated app pins rather than overwriting published assets.
The source model is Apache-2.0; the model release includes that license.

## Primary references

- [ORT format and loading API](https://onnxruntime.ai/docs/performance/model-optimizations/ort-format-models.html)
- [Offline graph optimization and hardware constraints](https://onnxruntime.ai/docs/performance/model-optimizations/graph-optimizations.html)
- [Sherpa 1.13.8 transducer session construction](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-transducer-model.cc)
- [Sherpa 1.13.8 model-type dispatch](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer-impl.cc)
- [Sherpa 1.13.8 provider/session options](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/session.cc)
- [Pinned model source](https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-gigaspeech-2023-12-12/tree/f7515a672ffb69232794aaaa44a0a360f7f25cd1)
