<div align="center">

  <h1>NuvioTV Reshaped</h1>

  <p>
    A community fork of <a href="https://github.com/tapframe/NuvioTV">NuvioTV</a> for Android TV.
    <br />
    Subtitle timing, seek previews, custom fonts, pill navigation, Live TV and Google sync — configured under Settings &gt; Nuvio Reshaped.
  </p>

  [Download](https://github.com/DavidVamaiotu/NuvioTV-Reshaped/releases/latest) · [All releases](https://github.com/DavidVamaiotu/NuvioTV-Reshaped/releases) · [Mobile edition](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped) · [Official Nuvio](https://nuvio.tv)

</div>

## Reshaped settings

Open **Settings > Nuvio Reshaped**. This page contains the following additions in the TV **tester** branch, `subtitle-autosync-tester`:

- **Auto Sync Subtitles.** Align an add-on subtitle with subtitles embedded in the video. Set a tolerance to leave small differences alone, or enable **Thorough Auto Sync Search** to compare more references before applying a confident match.
- **AutoSync bubble notifications.** Show synchronization progress and results in an optional bubble.
- **Automatically try another source.** If playback fails to start, try the next source using your **First Source** or **Regex** autoplay preference. Requires an internal player and one of those autoplay modes.
- **Sound and picture formats — Arc format handling.** Enable the format handling ported from Nuvio Arc for the built-in player. It adds TrueHD/DTS-HD passthrough handling, automatic or manual surround-format capabilities, system passthrough, an ARC soundbar mode, optional AC-3 conversion, and a stereo/5.1/7.1 decode target. You can retest lossless passthrough, clear learned audio refusals or tunnel stalls, and enable HDR10 metadata injection when supported Dolby Vision Profile 8 video is played as HDR10. Actual output depends on your device and audio system.
- **Seekr API key.** Supply your own Seekr key for seek-preview thumbnails.
- **Generate previews on device.** Make seek thumbnails from video already downloaded during playback. Seekr fills gaps for parts you have not watched yet.
- **Custom subtitle fonts.** Choose a subtitle font by uploading it from your phone.
- **Connection-aware stream ordering.** Use measured playback speed to move streams that are likely too heavy for your connection further down the list.
- **Pill menu.** Replace the sidebar with a floating menu at the top. Press Up to reach it and Back to jump to it.
- **Netflix-style Home.** Use a billboard at the top and rows whose focused title expands to show details. It changes the presentation while keeping the existing rows and actions. The pill menu stays enabled while this layout is on.
- **Live TV.** Configure your live-channel sources and programme guide from the Reshaped Live TV options.
- **Sync with Google.** Sync Reshaped settings and Live TV across your TVs and phone. Settings sync and Live TV sync have separate switches, plus a **Sync now** action. Buffer sizes and channel-preview options stay per device.

For the phone and tablet options, see the [mobile README](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped#readme).

This is a community fork. Report fork issues in [this repository](https://github.com/DavidVamaiotu/NuvioTV-Reshaped/issues).

## Installation

Download an APK from [NuvioTV Reshaped Releases](https://github.com/DavidVamaiotu/NuvioTV-Reshaped/releases) and sideload it onto your Android TV device. If no release is available, build from source below.

## Build from source

Use Android Studio, a JDK and the Android SDK.

```bash
git clone -b subtitle-autosync-tester https://github.com/DavidVamaiotu/NuvioTV-Reshaped.git
cd NuvioTV-Reshaped
./gradlew :app:assembleFullDebug
```

`subtitle-autosync-tester` contains the tester settings described above. Some options are not available in beta or stable builds. `subtitle-autosync` is the beta branch and `subtitle-autosync-stable` is the stable branch.

## Credits and license

Built on [NuvioTV](https://github.com/tapframe/NuvioTV) by its upstream contributors and the [NuvioMedia](https://github.com/NuvioMedia) team. Consider [supporting Nuvio](https://nuvio.tv/support).

Licensed under the [GNU General Public License v3.0](./LICENSE), the same as upstream.

## Legal

NuvioTV Reshaped browses metadata and plays media from user-installed extensions or user-provided sources. Use it only for content you own or are authorized to access. It does not host, store or distribute media and is not affiliated with third-party extensions or content providers.

See the upstream [Legal & Disclaimer Page](https://tapframe.github.io/NuvioTV/#legal) for additional information about the original project.

## Maintaining the fork README

`README.md` belongs to NuvioTV Reshaped. Keep its fork branding, platform details, downloads and upstream credits when updating it. The feature list must describe only controls present in **Settings > Nuvio Reshaped** on `subtitle-autosync-tester`. Check the tester settings page and its setting descriptions before changing the feature list. Do not add general Nuvio features or integrations from other settings pages. Do not replace it with upstream documentation during syncs, releases or branch merges.

After fetching an upstream target, run `bash .github/scripts/merge-nuvio-upstream.sh <target> "<message>"`. This preserves the fork README and workflows even when upstream README changes would merge cleanly. Resolve any remaining source conflicts manually. The README branding workflow checks future pushes and pull requests for accidental reversions.
