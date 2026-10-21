Nuvio RS: the FFmpeg and ExoPlayer AARs below are Nuvio Arc's (ported with Arc's sound format handling).

## Current FFmpeg AAR (0.9.3-beta-nt1)

`lib-decoder-ffmpeg-release.aar` SHA-256:
`0c6fd345c8f4911b9d03d86d2d45187f3b696c91d1adaad5b0e9917ad5238831`.

The shipped `lib-decoder-ffmpeg-release.aar` was built from the module sources in
`ffmpeg-aar-src/0.9.3-beta-nt1/` and FFmpeg n7.0.2 (https://github.com/FFmpeg/FFmpeg/tree/n7.0.2),
with the configure line in `.github/workflows/build-ffmpeg-decoder.yml`. FFmpeg is built without
`--enable-gpl` and `--enable-nonfree` and is used under the LGPL 2.1 or later.
The `ffmpeg-decoder-downmix` module holds newer source that is not in this binary yet.

`ffmpeg-aar-src/0.9.3-beta-nt1/` holds the three module files that have changed since this binary
was built (`ffmpeg_jni.cc`, `FfmpegAudioRenderer.java`, `FfmpegAudioDecoder.java`). The other module
files (`CMakeLists.txt`, `build_ffmpeg.sh`, `build.gradle.kts`, `FfmpegLibrary.java`,
`FfmpegDecoderException.java`, `package-info.java`, `NOTICE.md`) are the same as in
`ffmpeg-decoder-downmix`. These files are kept for reference and are not compiled into the app.

Combines official0.9.3 downmix limiter / multi-frame buffer-growth corrections with fork per-MIME denied-codec AC-3 routing and legal-rate high-sample-rate AC-3 resampling. Limiter timing on the planar transcode path uses the output encoder rate.

Native inputs: FFmpeg7.0.2 release archive (release signature verified, key fingerprint `FCF986EA15E6E293A5644F10B4322F04D67658D8`), Android NDK27.0.12077973, minimumAPI24. Decoder list follows `.github/workflows/build-ffmpeg-decoder.yml`, including the AC-3 encoder. ARMv7/ARM64/x86/x86_64 are included;16KB ELF LOAD alignment and Android system dependencies verified. Public Java API retains all entries from the previous fork AAR. The module's copyright, NOTICE and licences apply.

Host ASan/UBSan tests cover actual JNI decode/transcode code; they do not establish Android passthrough, MAT/DTS/DV or device performance acceptance.

The following text records the previous binary and its reconstruction; its statement that native code exists only in a binary is historical and does not describe this source-built artifact.

The binary ffmpeg extension was build with following decoders:

```
ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)
```

Complete [build instructions](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md).

To assemble ``.aar``:

```
./gradlew :extension-ffmpeg:bundleReleaseAar
```

## Fork modification: lib-decoder-ffmpeg-release.aar (2026-08)

`FfmpegAudioRenderer.class` inside `classes.jar` was rebuilt from the reconstruction
source at `ffmpeg-aar-src/FfmpegAudioRenderer.java` (CFR 0.152 decompilation of the
original class, plus one change, `setDeniedTranscodeMimes`: the AC-3 transcode
decision is now per-MIME, consulting an app-supplied denied set as well as the global
`forceOpticalPassthrough` flag). With the set empty the behaviour is equivalent to the
original. All other AAR entries, including every `libffmpegJNI.so`, are byte-identical
to the previous AAR. The custom audio pipeline (transcode/downmix, Java + native) is
the upstream NuvioTV developer's private work, present only in this binary; the
reconstruction source is provided as corresponding source for the modified class.

Rebuild recipe (JDK 21, CFR 0.152, media3-decoder 1.8.0 sources, android.jar API 34):
compile `ffmpeg-aar-src/FfmpegAudioRenderer.java` with `--release 11 -g` against
{android.jar, lib-common classes.jar, lib-exoplayer classes.jar, this AAR's
classes.jar, media3-decoder 1.8.0 classes, androidx-annotation, guava}; `jar uf` the
resulting class into `classes.jar`; `zip` it back into the AAR.

## Fork modification: DTS Express mapping

`FfmpegLibrary.class` inside `classes.jar` was rebuilt from `ffmpeg-decoder-downmix` with one change:
`getCodecName` maps `audio/vnd.dts.hd;profile=lbr` (DTS Express) to the `dca` decoder, as Media3 1.11 does.
The `dca` decoder in every `libffmpegJNI.so` already decodes DTS Express. All other AAR entries, including
every `libffmpegJNI.so` and every other class, are byte-identical to the previous AAR
(`24c571cb833b1dd38c127b94d63d43e74b31ff2c5c5ebcfe8535dd12e404cdb6`).

Rebuild recipe (JDK 17): compile `FfmpegLibrary.java` with `--release 11 -g -XDstringConcat=inline` against
{lib-common classes.jar, androidx-annotation, checker-qual}; the unchanged source compiles to the exact bytes of
the previous class. Replace the class in `classes.jar` and put `classes.jar` back into the AAR.

## Fork modification: lib-exoplayer-release.aar (playback engine)

`lib-exoplayer-release.aar` SHA-256:
`4c3047e76a9cb64f03c95762a696ad2aba0e200431fefe4d8c5b3803ac409cca`.

Base: the engine AAR built from halibiram/media 4dd1d98b9f (calloc sample chunks), with `DefaultAudioSink`
from halibiram/media #2 (TrueHD resync after a dropped chunk), SHA-256
`a3d513f0f1b53b474875bf96ad9db66fec1ebdf484be8b08b566f1cee0dd1bef`.

Frame release: `VideoFrameReleaseHelper` (with `$Api30`, `$DisplayHelper`, `$VSyncSampler`) and
`VideoFrameReleaseControl` are rebuilt from those sources with the media3 1.9.0 frame release fixes
5c9fd2366a, 49e2996647, ae3e5a4e5f, 5aa19e3142, be757e7e85 and 2656df9623 (vsync hysteresis, smoothing
kept through outliers and speed changes, reset at stream changes, vsync re-sampled after a display mode
change, `Surface.setFrameRate` only for changes of 0.1 fps or more). The vsync re-sample removes a pending
frame callback before posting one, as media3 does since fd8a6b2c57.

Audio clock: `AudioTrackPositionTracker`, `AudioTimestampPoller` (with `$AudioTimestampWrapper`) and
`DefaultAudioSink` are rebuilt from the same sources with bc0652cb54 (media3 1.11.0: the clock stops at the
written frames during an underrun and restarts from a fresh timestamp), 8ca4ef9db0 (media3 1.10.0: a new
timestamp is read when the output latency changes, latency limit 10 s). The playback head wrap check
stays as in 1.8.0.
`DefaultAudioSink` changes by one call; its inner classes are unchanged.

All other AAR entries, including every `libmedia3_exoplayer_jni.so`, and every other class are
byte-identical to the base AAR.

Rebuild recipe (JDK 17): compile the changed source files with `-source 8 -target 8 -g -proc:none` against
{android.jar API 35 and core-lambda-stubs as boot class path, this AAR's classes.jar, lib-common,
lib-datasource, lib-extractor, guava, androidx-annotation, checker-qual, error_prone_annotations}. The
unchanged sources compile to the base classes byte for byte, apart from one `MethodParameters` attribute
on `$DisplayHelper` and the constant pool order of `DefaultAudioSink`. `AudioTrackPositionTracker` and
`AudioTimestampPoller` then go through the typedef remover of the Android Gradle plugin
(`com.android.tools.lint:lint-typedef-remover`, typedefs `$PlayState` and `$State`), as in the library
build. Replace the classes in `classes.jar` and put `classes.jar` back into the AAR, keeping
entry order and timestamps.

