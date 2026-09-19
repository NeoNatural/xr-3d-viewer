# Agent instructions for this repository

Read `../quest3_2d_to_3d_media_browser_codex_plan.md` and `ARCHITECTURE_NOTES.md` before changing playback or rendering code.

This is an Android/Gradle native OpenXR project, not a Unity project. Use Gradle for builds and ADB for device deployment. Do not add Unity packages or migrate the renderer to Unity.

Work in small, runnable increments. Preserve the original Moonlight stream as a regression path. Before altering the frame source, verify the decoder `Surface` ownership, the renderer GL thread, the depth thread, and native OpenXR frame loop. Keep decoded video on the GPU path; avoid per frame CPU readback.

This checkout's parent directory contains a space, which breaks `ndk-build` even when Gradle itself can find the project. Use the verified no-space build wrapper here:

```sh
./tools/build-local.sh
./tools/build-local.sh testNonRootDebugUnitTest
```

The wrapper syncs source to a physical temporary path without spaces and copies debug APKs to `build/agent-apks/`. On a checkout whose path has no spaces, the baseline commands are:

```sh
git submodule update --init --recursive
./gradlew assembleNonRootDebug
make -C app/src/test/cpp test
./gradlew testNonRootDebugUnitTest
```

`local.properties` is machine specific and ignored by Git. Point `sdk.dir` to the installed Android SDK. The project requests SDK 37 and NDK 29.0.14206865. Deploy the debug APK from `build/agent-apks/` after a wrapper build, or `app/build/outputs/apk/nonRoot/debug/` after a direct build, with `adb install -r` once the Quest is connected and authorized.

The first product milestone, a static local image through the existing depth and stereo path, has been visually confirmed on Quest 3. Direct SMB browsing and image access are now being built on NOVA's jcifs-ng fork. Keep Media3 and broad renderer refactoring behind a verified SMB image path.
