# FFmpeg Decoder Downmix

This repo supports two build modes for the patched FFmpeg decoder:

## Default mode

The app uses the patched prebuilt AAR in `app/libs/lib-decoder-ffmpeg-release.aar`.

This is the default. No local FFmpeg source tree is required.

## Local development mode

Use the source module `:ffmpeg-decoder-downmix` only when you need to rebuild or modify the patched decoder.

Enable it with one of:

- Gradle property: `-PuseLocalFfmpegDecoder=true`
- Environment variable: `USE_LOCAL_FFMPEG_DECODER=true`
- `local.properties`: `USE_LOCAL_FFMPEG_DECODER=true`

When local development mode is enabled, you must also provide:

- `FFMPEG_SOURCE_DIR`
- `FFMPEG_BUILD_DIR`

These can be set in `local.properties` or environment variables. See `local.example.properties`.

## Typical workflow

1. Build and test normally with the prebuilt AAR.
2. Switch on `USE_LOCAL_FFMPEG_DECODER` only when working on native decoder changes.
3. Rebuild the patched AAR after validating local decoder changes with `:ffmpeg-decoder-downmix:assembleRelease`.
4. Replace `app/libs/lib-decoder-ffmpeg-release.aar` with `ffmpeg-decoder-downmix/build/outputs/aar/ffmpeg-decoder-downmix-release.aar`.

## Reshaped combined decoder (2026-11-02)

The prebuilt combines upstream LFE downmix control with Reshaped per-format AC-3 transcode policy and end-of-stream draining/silence trimming. Java and JNI signatures include both LFE level and the end-of-stream flag.

Built from FFmpeg n7.1.4 using NDK 29.0.14206865, Android API 24, static avcodec/swresample/avutil and static libc++, for armeabi-v7a, arm64-v8a, x86 and x86_64. Native ELF segments use 16 KiB alignment. Enabled decoders: aac, mp3, ac3, eac3, truehd, dca, vorbis, opus, amrnb, amrwb, flac, alac, pcm_mulaw, pcm_alaw, h264 and hevc; encoder: ac3. The prebuilt retains the existing video decoder classes.

AAR SHA-256: `a7e0a11f977f3cea4312c101a8423f77ffbfdf7f7717beb98cc5807406ab0d93`. Rebuild the Java classes from this module and the native libraries from this module's `ffmpeg_jni.cc` together; do not combine Java/JNI artifacts from different revisions.
