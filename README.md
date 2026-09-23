<p align="center">
  <img src="moonlight-xr-logo-transparent.png" height="180" alt="XR 3D Viewer logo">
</p>

# XR 3D Viewer

**English** | [简体中文](README.zh-CN.md)

An open-source Android/OpenXR media viewer that turns ordinary 2D photos and
videos into stereoscopic 3D on a standalone headset. Media can be opened from
local storage or streamed directly from an SMB/NAS share; no desktop companion,
cloud upload, or pre-conversion step is required.

The project is based on [Moonlight XR](https://github.com/Gilleece/moonlight-android-xr)
and [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android).
It keeps the native OpenXR renderer and GPU video path while making local and
SMB media playback the primary application.

> **Project status:** early release. The current build has been developed for
> Meta Quest 3 and other Snapdragon XR2 Gen 2-class devices. Keep a comfortable
> stereo separation and stop viewing if the image causes eye strain.

## Features

- Browse photos and videos through Android's system folder picker.
- Connect directly to SMB 2/3 shares, with saved credentials encrypted by the
  Android Keystore.
- View JPEG, PNG, and WebP images with prepared Depth Anything V2 depth.
- Play MP4, MKV, WebM, M4V, and MOV through Android MediaCodec/Media3.
- Convert decoded video frames to stereo in real time with MiDaS Small and a
  depth-image-based rendering shader.
- Move and resize the virtual screen, tune stereo separation, swap eyes, and
  select passthrough or a bundled environment without leaving VR.
- Use controllers, hand tracking, or gaze as available on the headset.

The exact video codecs that play depend on the device's Android MediaCodec
implementation. File extensions describe the browser filter, not a guarantee
that every codec/profile inside the container is supported.

## How it works

Still images use a higher-detail prepared-depth path:

```text
image -> Depth Anything V2 Small (518x518)
      -> edge-aware depth preparation
      -> per-eye depth warp
      -> OpenXR composition layers
```

Videos stay on the GPU decode path and use a lower-latency model:

```text
Media3/MediaCodec -> SurfaceTexture (external OES texture)
                  -> 256x256 inference input
                  -> MiDaS Small on a dedicated LiteRT thread
                  -> edge-guided depth upsample
                  -> per-eye occlusion-aware warp
                  -> OpenXR composition layers
```

Monocular 2D-to-3D reconstruction cannot reveal pixels hidden behind objects.
Some stretching or smearing around high-contrast silhouettes is therefore
expected. Video depth is also updated at a configurable cadence and can lag the
picture slightly.

## Installation

Download the signed APK from the repository's
[Releases](https://github.com/NeoNatural/xr-3d-viewer/releases) page and sideload
it with Meta Quest Developer Hub, SideQuest, or ADB:

```sh
adb install -r xr-3d-viewer-v0.4.apk
```

Android refuses unsigned release APKs. Official release assets are verified by
CI before publication and are accompanied by a SHA-256 checksum.

## Usage

1. Launch **Moonlight XR** on the headset.
2. Choose **Browse local photos and videos** or **Browse SMB photos and videos**.
3. For local media, grant read access to a folder through Android's folder
   picker. For SMB, enter the NAS host, share, and credentials.
4. Select an image or video. Other supported items in the same folder become
   the next/previous playlist.
5. Open **Media Settings** to adjust depth cadence, eye order, passthrough, and
   performance diagnostics.

SMB credentials never leave the device. Saved passwords are encrypted with an
app-owned Android Keystore AES-GCM key. Bug reports are only created when the
user explicitly asks for one; unless a maintainer configures a report endpoint
at build time, the report remains as a local file for manual sharing.

## Supported hardware

- Meta Quest 3 is the primary tested target.
- Pico 4 Ultra and comparable OpenXR headsets are expected to work through the
  existing Moonlight XR renderer.
- Quest 2, Quest Pro, Pico 4, and older devices have less GPU headroom. The app
  applies a conservative profile, but prepared still-image depth or flat video
  may be more practical than real-time video conversion.

Hand tracking, eye/gaze input, and passthrough are optional. Controllers remain
the fallback input path.

## Building from source

This is an Android/Gradle native OpenXR project, not a Unity project.

Requirements:

- JDK 21
- Android SDK 37
- Android NDK `29.0.14206865`
- Git submodules

Clone with the native streaming submodule:

```sh
git clone --recursive https://github.com/NeoNatural/xr-3d-viewer.git
cd xr-3d-viewer
```

When the checkout path contains no spaces:

```sh
./gradlew testNonRootDebugUnitTest
make -C app/src/test/cpp test
./gradlew assembleNonRootDebug
```

This repository is often developed from a parent path containing spaces,
which `ndk-build` cannot handle. In that case use the provided wrapper; it
builds from a temporary no-space path and copies APKs back to
`build/agent-apks/`:

```sh
./tools/build-local.sh testNonRootDebugUnitTest
./tools/build-local.sh assembleNonRootRelease lintNonRootRelease
```

Debug output is normally under
`app/build/outputs/apk/nonRoot/debug/`. An unsigned local release is under
`app/build/outputs/apk/nonRoot/release/`.

### Signing a local release

Create a keystore once:

```sh
keytool -genkeypair -v -keystore release.keystore -alias moonlightvr \
  -keyalg RSA -keysize 2048 -validity 10000
cp keystore.properties.example keystore.properties
```

Fill in the keystore path, alias, and passwords, then build:

```sh
./tools/build-local.sh assembleNonRootRelease
```

`release.keystore` and `keystore.properties` are ignored by Git. Never commit
either file or print their contents in CI logs.

## Release process

The GitHub Actions workflow runs Java and native unit tests, release lint, and
debug/release builds. A tag named `v*` additionally requires the signing
secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, and (optionally) `KEY_ALIAS`.
It verifies the APK signature, generates a SHA-256 checksum, and publishes both
files to a GitHub Release.

Release versions are kept in sync in `app/build.gradle`:

- `versionName`: upstream Moonlight version plus `-xrX.Y`
- `versionCode`: monotonically increasing Android package version
- Git tag: `vX.Y`

## Code layout

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/limelight/local/` | Android Storage Access Framework browser |
| `app/src/main/java/com/limelight/smb/` | SMB browser, encrypted profiles, and random-access sources |
| `StaticImageXrActivity.java` | Still-image loading and prepared-depth playback |
| `VideoXrActivity.java` | Local/SMB Media3 playback into the XR surface |
| `binding/video/MidasDepthSource.java` | LiteRT real-time video depth |
| `binding/video/StillImageDepthBatcher.java` | Depth Anything V2 still-image depth |
| `app/src/main/jni/xr-renderer/` | OpenXR, OpenGL, input, layers, room, and warp renderer |
| `app/src/main/jni/libsmb2/` | Native SMB random-access bridge and vendored libsmb2 |
| `tools/` | Reproducible build helper, model conversion, and diagnostics |

For renderer and threading invariants, see
[`ARCHITECTURE_NOTES.md`](ARCHITECTURE_NOTES.md). Model measurements and
provenance are recorded in
[`DEPTH_MODEL_BENCHMARK.md`](DEPTH_MODEL_BENCHMARK.md).

## License and attribution

The application is distributed under **GNU GPL v3** because it is a modified
Moonlight for Android work. See [`LICENSE.txt`](LICENSE.txt).

All added runtime dependencies and assets were reviewed for GPLv3 compatibility.
Required copyright notices, full Apache-2.0 terms, LGPL component mapping,
model provenance, hashes, and Creative Commons attributions are in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). The same documents are
bundled in every APK and can be opened from **Media Settings > Open-source
licenses**.

In particular:

- `libsmb2` is LGPL-2.1-or-later with BSD-licensed DCE/RPC portions.
- NOVA's `jcifs-ng` fork is LGPL-2.1.
- LiteRT, Media3, OpenXR, and the Depth Anything V2 Small source model use
  Apache-2.0-compatible terms.
- MiDaS Small is MIT licensed.
- Poly Haven environments are CC0.
- PSX Cinema is adapted from *VR Cinema Environment* by fangzhangmnm under
  CC BY 4.0.

The Moonlight name and logos identify the upstream lineage; they are not a
statement of endorsement by the upstream projects.

## Contributing

Issues and focused pull requests are welcome. Please keep decoded video on the
GPU path, preserve the original renderer regression path, and run both Java and
native unit tests before submitting changes.
