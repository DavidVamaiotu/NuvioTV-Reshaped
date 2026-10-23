<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="NuvioTV Reshaped" width="300" />

  <h1>NuvioTV Reshaped</h1>

  <p>
    A community fork of <a href="https://github.com/tapframe/NuvioTV">NuvioTV</a> for Android TV.
    <br />
    Adds MyAnimeList, AniList and Kitsu lists, watch-progress updates and tracker sync.
  </p>

  [Download](https://github.com/DavidVamaiotu/NuvioTV-Reshaped/releases/latest) · [All releases](https://github.com/DavidVamaiotu/NuvioTV-Reshaped/releases) · [Mobile edition](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped) · [Official Nuvio](https://nuvio.tv)

</div>

## What Reshaped adds

This branch adds anime tracking to upstream [NuvioTV](https://github.com/tapframe/NuvioTV).

### MyAnimeList, AniList and Kitsu on your home screen

Connect your tracker accounts under **Settings > Integrations**. Reshaped turns your lists into home-screen rows, so you can browse what you are watching, plan to watch, have completed, put on hold or dropped. Choose which status rows appear and their order separately for each tracker.

### Watch progress sent to your trackers

With **Send watch progress** enabled for a tracker, Reshaped updates it when an episode reaches the watched threshold (80%) or you mark an item watched from its details screen. It can send updates to multiple connected trackers.

Progress only moves forward: replaying an earlier episode does not lower your existing episode count. Rewatch counts must be managed on the tracker itself. Updates without a usable anime mapping are skipped.

### Episode mapping for anime seasons and parts

A TV season can cover several entries on an anime tracker, or a tracker entry can span multiple TV seasons. Reshaped adds episode-offset mapping to translate between those layouts and send progress to the corresponding entry. This also avoids marking an entire tracker entry complete just because one TV season has ended.

### Tracker sign-in from your phone

The TV's tracker connection flow provides a QR code or link you can open on your phone. Complete authorization there, then return to the TV to finish connecting.

### Tracker accounts and preferences across devices

Reshaped adds synchronization of tracker credentials and per-profile preferences: visible list statuses, row order and the send-progress toggle. This allows compatible devices using the same sync account and profile to pick up those tracker connections and choices. It requires the corresponding tracker-sync backend; a standalone build without that backend cannot provide this feature.

## Mobile edition

The [mobile edition](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped) has a separate set of Reshaped additions, documented in its README. Its subtitle AutoSync, seek buffer and player customizations are mobile features.

This is a community fork. Report fork issues in [this repository](https://github.com/DavidVamaiotu/NuvioTV-Reshaped/issues).

## Installation

Download an APK from [NuvioTV Reshaped Releases](https://github.com/DavidVamaiotu/NuvioTV-Reshaped/releases) and sideload it onto your Android TV device. If no release is available, build from source below.

## Build from source

Use Android Studio, JDK 17 and the Android SDK with API 36 installed. The app supports Android 7.0 (API 24) and later. Use the included Gradle wrapper.

```bash
git clone -b animeIntegration https://github.com/DavidVamaiotu/NuvioTV-Reshaped.git
cd NuvioTV-Reshaped
cp local.example.properties local.properties
# Set sdk.dir and any service credentials needed for your build in local.properties.
CI_USE_DEBUG_SIGNING=true ./gradlew :app:assembleRelease
```

`animeIntegration` is this fork's main branch. The command above uses debug signing for a local release build; distribution builds should use your own release signing configuration. Features backed by external services require their corresponding configuration.

## Credits and license

Built on [NuvioTV](https://github.com/tapframe/NuvioTV) by its upstream contributors and the [NuvioMedia](https://github.com/NuvioMedia) team. Consider [supporting Nuvio](https://nuvio.tv/support).

Licensed under the [GNU General Public License v3.0](./LICENSE), the same as upstream.

## Legal

NuvioTV Reshaped browses metadata and plays media from user-installed extensions or user-provided sources. Use it only for content you own or are authorized to access. It does not host, store or distribute media and is not affiliated with third-party extensions or content providers.

See the upstream [Legal & Disclaimer Page](https://tapframe.github.io/NuvioTV/#legal) for additional information about the original project.

## Maintaining the fork README

`README.md` belongs to NuvioTV Reshaped. Keep its fork branding, platform details, downloads and upstream credits when updating it. Describe only additions verified against the upstream version this branch builds on; keep upstream features out of the Reshaped feature list. Do not replace it with upstream documentation during syncs, releases or branch merges.

After fetching an upstream target, run `bash .github/scripts/merge-nuvio-upstream.sh <target> "<message>"`. This preserves the fork README and workflows even when upstream README changes would merge cleanly. Resolve any remaining source conflicts manually. The README branding workflow checks future pushes and pull requests for accidental reversions.
