<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="NuvioTV Reshaped" width="300" />

  <h1>NuvioTV Reshaped</h1>

  <p>
    A community fork of <a href="https://github.com/tapframe/NuvioTV">NuvioTV</a> for Android TV.
    <br />
    TV-first browsing and playback with the Stremio addon ecosystem, anime trackers and phone-assisted tracker sign-in.
  </p>

  [Download](https://github.com/DavidVamaiotu/NuvioTV-Reshaped/releases/latest) · [All releases](https://github.com/DavidVamaiotu/NuvioTV-Reshaped/releases) · [Mobile edition](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped) · [Official Nuvio](https://nuvio.tv)

</div>

## About

NuvioTV Reshaped is the Android TV edition of the Reshaped forks. It builds on NuvioTV's Kotlin, Jetpack Compose and Media3 player, with support for user-installed Stremio addons for discovery and source resolution.

This branch includes MyAnimeList, AniList and Kitsu tracker integration, phone-assisted tracker sign-in, and synchronization of tracker tokens and per-profile settings across devices. The [mobile edition](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped) runs on Android phones and tablets; its player features and build instructions are documented in its own README.

This is a community fork, not an official Nuvio release. Report fork issues in [this repository](https://github.com/DavidVamaiotu/NuvioTV-Reshaped/issues).

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

`README.md` belongs to NuvioTV Reshaped. Keep its fork branding, platform details, downloads and upstream credits when updating it. Do not replace it with upstream documentation during syncs, releases or branch merges.

After fetching an upstream target, run `bash .github/scripts/merge-nuvio-upstream.sh <target> "<message>"`. This preserves the fork README and workflows even when upstream README changes would merge cleanly. Resolve any remaining source conflicts manually. The README branding workflow checks future pushes and pull requests for accidental reversions.
