# ComfyChair H3

An unofficial Android client for [ComfyUI](https://github.com/comfyanonymous/ComfyUI), focused on
running MiniMax H3 reference-to-video workflows from a phone — including multi-segment clips that
continue from the previous segment's last frame.

This is a derivative of [legal-hkr/comfychair](https://github.com/legal-hkr/comfychair)
(GPL-3.0). The upstream app is a general ComfyUI client; this fork adds an H3 workspace on top and
is **not** published on F-Droid.

- Package: `com.comfychair.h3` (installs alongside the upstream `sh.hnet.comfychair`)
- Minimum: Android 13 (API 33)
- Requires your own ComfyUI server, reachable from the phone (LAN or VPN)

<p align="center">
  <img src="https://github.com/mohuaji96/ComfyChair-H3/releases/download/v0.9.8.5/01-h3-studio.png" width="45%">
  <img src="https://github.com/mohuaji96/ComfyChair-H3/releases/download/v0.9.8.5/02-refs-tailframe.png" width="45%"><br>
  <img src="https://github.com/mohuaji96/ComfyChair-H3/releases/download/v0.9.8.5/03-params.png" width="45%">
  <img src="https://github.com/mohuaji96/ComfyChair-H3/releases/download/v0.9.8.5/04-models-lora.png" width="45%">
</p>
## What this fork adds

**H3 video workspace**
- 文生 / 图生 / 首尾帧 / 参考生视频, driven by the template's own node definitions
- Reference images, up to three reference audio slots, aspect-ratio and megapixel control
- Duration constrained to the model's documented 4–15 s range

**Last-frame continuation**
- Captures the closing frame on the phone (`MediaMetadataRetriever`), or from a video you pick
- Hangs that frame as the last `<Picture N>` and writes the matching declaration into the prompt
- The declaration is owned by the app: turning the switch off removes only what it wrote, never
  text you typed yourself
- 连拍 mode repeats the loop automatically, so a long clip becomes several short segments

**Gallery**
- Two-tier media cache: `cacheDir` holds a disposable copy (cap + LRU + favourites kept), saving to
  the gallery is permanent
- Survives a ComfyUI restart: the server keeps history in memory, so the app merges cached metadata
  back in instead of showing an empty page
- Filters (all / images / videos / favourites), day grouping, per-item badge for cached entries

## Building

Requires JDK 21 and Android SDK 36.

```bash
./gradlew :app:testDebugUnitTest   # 101 unit tests
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease     # unsigned unless a release key is configured
```

Release signing keys are **never** committed. To sign a release build, put a `keystore.properties`
next to the repository (or point `COMFYCHAIR_KEYSTORE_PROPERTIES` at it):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without it, `assembleRelease` produces an unsigned APK — that is the intended default for anyone
building from source.

> Note for Windows users: the Android Gradle Plugin fails on project paths containing non-ASCII
> characters. Build from an ASCII path (for example `C:\build\ComfyChairH3`) if your checkout lives
> in a Chinese-named folder.

## Requirements on the server

The H3 workspace expects the MiniMax H3 nodes used by the templates shipped with this app
(`MiniMaxH3ReferenceToVideo` and friends), plus the VHS nodes for reference audio. Other workflows
imported into the app use whatever nodes they were built with.

## Licence

GPL-3.0, inherited from upstream. See [LICENSE](LICENSE). Modifications in this fork are released
under the same licence; the full corresponding source is this repository.

## Privacy

No telemetry, no accounts, no analytics. See [PRIVACY.md](PRIVACY.md) for what is stored on the
device.

## Credits

- [ComfyUI](https://github.com/comfyanonymous/ComfyUI) — the server this app talks to
- [legal-hkr/comfychair](https://github.com/legal-hkr/comfychair) — the upstream Android client this
  fork is based on
