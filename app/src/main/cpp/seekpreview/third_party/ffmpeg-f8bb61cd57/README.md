# FFmpeg headers for MPV 0.2.1

Public headers from https://github.com/FFmpeg/FFmpeg/tree/f8bb61cd57a714b4eb8a9f8002dbb5214eed48c7, matching the packaged runtime `git-2026-11-13-f8bb61c`. They are used only to compile the seek-preview adapter; no second FFmpeg implementation is linked.

ABIs: libavcodec 63.28.100, libavutil 61.20.100, libswscale 10.6.100. The strict runtime major checks remain enabled. The separate FFmpeg 6.0 fallback and previous headers remain intact.

`libavutil/avconfig.h` retains the little-endian/fast-unaligned configuration for the supported Android ABIs. Header licensing and copyright notices are retained; see COPYING.LGPLv2.1. The dependency and binary fingerprint are recorded in ../mpv-ffmpeg-abi.json.
