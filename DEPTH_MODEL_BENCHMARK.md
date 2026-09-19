# Depth model trial — 2026-09-19

## Scope and inputs

- Compared the current MiDaS v2.1 Small source ONNX model (256×256) with Depth Anything V2 Small, using five private illustrations from `../Figs/`. The examples and rendered previews stay under ignored `build/depth-comparison/` and are not committed.
- V2 weights: [Depth-Anything-ONNX v2.0.0](https://github.com/fabio-sim/Depth-Anything-ONNX/releases/tag/v2.0.0), `depth_anything_v2_vits_dynamic.onnx` (99,092,268 bytes). This is a community export of the official checkpoint. Its dynamic dimensions are multiples of 14.
- MiDaS weights: official `model-small.onnx`, same source used to produce the APK's LiteRT model. Its graph expects RGB floats in 0–1 and internally applies ImageNet normalization.
- V2 preprocessing uses ImageNet mean and standard deviation, with the image's aspect ratio preserved. The longest input side is 392. MiDaS uses its fixed square input, matching the existing app model size. Each output is independently mapped from its 2nd–98th percentile for display. **These grayscale levels cannot be compared as absolute distance.**

## Quest 3 CPU reference

Debug-only ONNX Runtime Android 1.22.0 test Activity. A single ONNX model is loaded from the app's external files directory. Input is a zero-filled tensor to isolate inference time; no image decode, color conversion, XR rendering, or stereo warp is included. Four CPU threads. Device was connected through ADB and not inside an active OpenXR session.

| Backend | Square input | First run | Later runs |
| --- | --- | ---: | ---: |
| ONNX CPU | 252×252 | 608 ms | 597, 598 ms |
| ONNX CPU | 392×392 | 1263 ms | 1210, 1202 ms |
| ONNX CPU | 518×518 | 2134 ms | 2043, 2028 ms |
| ONNX NNAPI | 252×252 | 661 ms | 585, 584 ms |
| ONNX NNAPI | 392×392 | 1320 ms | 1256, 1300 ms |
| ONNX NNAPI | 518×518 | 2148 ms | 2106, 2086 ms |
| ONNX XNNPACK | all | — | Session creation fails in the bilinear Resize operator |

CPU session creation: 402 ms; NNAPI session creation: 429 ms. NNAPI showed no material speedup for this export. This does not prove whether any operators were delegated; that needs provider profiling. XNNPACK fails during session initialization with `xnn_create_resize_bilinear2d_nhwc_fp32 failed. Status:2`, likely requiring a different fixed-shape export or graph change. Direct QNN was not tested. By comparison, the shipping MiDaS model previously measured about 18.6 ms on the Quest LiteRT GPU delegate and 183–265 ms on its CPU path (see `ARCHITECTURE_NOTES.md`). The backend and model both differ, so this does not isolate model architecture cost.

## Quest 3 LiteRT GPU trial

[Qualcomm's published V2 Small TFLite float export](https://huggingface.co/qualcomm/Depth-Anything-V2/blob/main/README.md) is a fixed 518×518 NHWC model. The zip release `v0.62.2/depth_anything_v2-tflite-float.zip` contains a 98,920,480-byte `.tflite` file. Its metadata specifies RGB float input in 0–1 and a 518×518 float depth output. We pushed it to the Quest app's external files and used the existing LiteRT 1.4.2 GPU delegate. This is a separate model export from the dynamic ONNX benchmark above.

| Backend, same TFLite model | Session creation | 518×518 inference |
| --- | ---: | ---: |
| LiteRT GPU delegate | 4.6–4.7 s | 180–183 ms |
| LiteRT CPU, 4 threads | 139 ms | 3.93–4.04 s |

With one real MMD frame, 60 consecutive GPU inferences stayed at 180–183 ms (about 5.5 depth updates/s) over roughly 11 seconds. This is an isolated benchmark while OpenXR rendering was inactive; it does not establish concurrent XR frame rate or long-session thermals. GPU session creation includes delegate graph compilation, so prewarming will matter for first-image latency.

The GPU output for that MMD frame was finite and ranged from 0 to 4.4453. Compared with the ONNX V2 Small output from the same stretched 518×518 input, its pixelwise Pearson correlation was **0.9996**. This validates the GPU output orientation and broad numerical behavior, though the two exports and resize implementations can differ slightly. Ignored depth dumps and PNG previews are in `build/depth-comparison/`.

## Quest 3 ncnn Vulkan trial

Tested [ncnn's official 20260526 Android Vulkan SDK](https://github.com/Tencent/ncnn/releases/tag/20260526) with the `dptv2_s.param` and `dptv2_s.bin` Depth Anything V2 Small export from [ncnn-android-depth_anything](https://github.com/FeiGeChuanShu/ncnn-android-depth_anything). A standalone arm64 Android executable ran through ADB on the Quest 3's Adreno 740. Its 518×518 float RGB input used the example's ImageNet mean and scale; the real MMD frame was stretched to the same size. Timing measures `Extractor::extract("depth")`, including the CPU-visible output transfer. OpenXR was inactive.

| Backend, ncnn V2 Small export | Model load | First extraction | Later extractions |
| --- | ---: | ---: | ---: |
| ncnn Vulkan | 4.02 s | 956 ms | 826.0 ms (real MMD frame); 826.5 ms mean of 9 later runs on zero input |
| ncnn CPU, 4 threads | 104 ms | 1138 ms | 1122 ms (real MMD frame); 1131 ms mean of 4 later runs on zero input |

The ncnn Vulkan output on the MMD frame was finite, 518×518, and correlated **0.9991** with the same ncnn model on CPU. Its correlation with the separate Qualcomm LiteRT export's depth map was **0.973**. The exports use different weights/graphs and the Android Bitmap and Pillow input resizers may differ, so that last number is a broad output sanity check, not an accuracy ranking.

At this size, ncnn Vulkan is about **4.6× slower** than the measured 180–183 ms LiteRT GPU path, with similar four-second model initialization. The ncnn result is specific to this public conversion, SDK version, device, and readback-inclusive extraction. It does not rule out a faster ncnn conversion or lower resolution, but does not justify replacing the working LiteRT GPU candidate for 518×518 still images. Neither backend has yet been profiled concurrently with OpenXR.

## LiteRT batch-4 trial

The Qualcomm batch-one model cannot be resized in place. Strict resize rejects its fixed batch dimension; non-strict resize reaches an internal Reshape whose element count remains fixed for batch one (`2102784 != 525696`). Two fixed `[4,518,518,3]` exports were then tested on Quest 3:

| Fixed batch-4 export | GPU delegated nodes | Session creation | Inference per four images |
| --- | ---: | ---: | ---: |
| onnx2tf 2.6.9, float32 | 82 / 709 | 20.8 s | 13.1–13.6 s |
| LiteRT Torch 0.9.4, float32 | 735 / 742 | 25.8 s | 1.20–1.23 s |

The LiteRT Torch export is a real batch-4 graph built from the official Hugging Face V2 Small weights. It runs mostly on the GPU, but costs roughly **300–307 ms per image**, while four runs of the Qualcomm batch-one graph take about **724 ms total** at the isolated 181 ms result. Its model initialization is also over five times longer. The onnx2tf graph falls back heavily to XNNPACK and is unusable. Consequently the app retains the Qualcomm batch-one model and schedules its prefetch jobs separately; adopting either batch-4 export would reduce throughput and increase the freeze interval.

## Visual observations

The V2 maps consistently separate the principal character from the background and often retain more scene structure than MiDaS. On the MMD-like rendered image, it distinguishes the sofa back, halo, face, forearms, and raised leg. In some illustrations it still guesses broad, smooth depth for the body and objects. The 2D artwork has no ground-truth depth, and a grayscale map alone cannot establish which result produces a more comfortable stereo view. The preview sheets place **RGB | V2 | MiDaS** left to right.

## MMD clip

The user supplied `../Video/【MMD】Tda式改変ミクさんで”Classic” 1080P 60FPS.mp4` (H.264, 1920×1080, 60 fps, 120.1 s). We sampled seconds 20–23 at 6 fps, giving 18 frames. V2 was run at 392×224; MiDaS at 256×256. Preview sheets are in `build/depth-comparison/mmd/`.

The sheets show V2 retaining a distinct foreground character and dark background through the sampled dance and wide shot. MiDaS frequently makes the bright floor very near, which may create an unwanted stereo plane. V2 does not resolve fine hair/hand depth perfectly, and without ground truth this remains a visual judgment.

`tools/measure_depth_flicker.py` aligns consecutive frames using Farneback RGB optical flow and measures the mean absolute difference between aligned grayscale depth maps. It only summarizes pairs where at least 90% of pixels agree with the warped RGB image, excluding obvious cuts and large motion pairs. On **9 stable pairs**, normalized depth difference was **0.010 for V2 versus 0.100 for MiDaS**. This is a useful warning about MiDaS depth variation on this clip, not a validated temporal benchmark: the preview depth maps were independently percentile-normalized and JPEG-compressed, and the score also reflects those steps. See ignored `build/depth-comparison/mmd/flicker.json` for each pair.

## Decision

V2 Small is worth an optional static-image path with prefetch and caching. The fixed 518 LiteRT GPU export runs correctly and is over 20 times faster than the same TFLite model on CPU, but takes nearly five seconds to initialize and produces only about 5.5 depth updates/s in isolation. The tested ncnn Vulkan export runs correctly but is slower at 518×518. NNAPI did not materially accelerate the dynamic ONNX export, and that export cannot initialize with XNNPACK. The next step is to prewarm V2 during 2D browsing, test it concurrently with OpenXR, and judge the actual stereo output in-headset before changing the default. For video, consider lower input resolution and measure XR frame timing under load. Keep MiDaS as the known working fallback.

## Reproduce

```sh
curl -L -o /private/tmp/depth_anything_v2_vits_dynamic.onnx \
  https://github.com/fabio-sim/Depth-Anything-ONNX/releases/download/v2.0.0/depth_anything_v2_vits_dynamic.onnx
curl -L -o /private/tmp/midas_model-small.onnx \
  https://github.com/isl-org/MiDaS/releases/download/v2_1/model-small.onnx
curl -L -o /private/tmp/depth_anything_v2-tflite-float.zip \
  https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/depth_anything_v2/releases/v0.62.2/depth_anything_v2-tflite-float.zip
unzip -p /private/tmp/depth_anything_v2-tflite-float.zip \
  depth_anything_v2-tflite-float/depth_anything_v2.tflite \
  > /private/tmp/depth_anything_v2_qualcomm_518.tflite
python3 -m venv /private/tmp/quest-depth-bench-venv
/private/tmp/quest-depth-bench-venv/bin/pip install onnxruntime pillow numpy
/private/tmp/quest-depth-bench-venv/bin/pip install opencv-python-headless
/private/tmp/quest-depth-bench-venv/bin/python tools/compare_depth_v2.py \
  /private/tmp/depth_anything_v2_vits_dynamic.onnx build/depth-comparison \
  ../Figs/* --midas /private/tmp/midas_model-small.onnx
ffmpeg -hide_banner -loglevel error -ss 20 -i ../Video/*.mp4 -t 3 \
  -vf 'fps=6,scale=480:-2' -q:v 3 build/depth-comparison/mmd-frames/frame_%03d.jpg
/private/tmp/quest-depth-bench-venv/bin/python tools/compare_depth_v2.py \
  /private/tmp/depth_anything_v2_vits_dynamic.onnx build/depth-comparison/mmd \
  build/depth-comparison/mmd-frames/*.jpg --midas /private/tmp/midas_model-small.onnx
/private/tmp/quest-depth-bench-venv/bin/python tools/measure_depth_flicker.py build/depth-comparison/mmd

./tools/build-local.sh
adb install -r build/agent-apks/app-nonRoot-debug.apk
adb push /private/tmp/depth_anything_v2_vits_dynamic.onnx \
  /sdcard/Android/data/com.gilleece.moonlightxr.debug/files/depth_anything_v2_vits_dynamic.onnx
adb shell am start -n com.gilleece.moonlightxr.debug/com.limelight.DepthV2BenchmarkActivity
adb shell am force-stop com.gilleece.moonlightxr.debug
adb shell am start -n com.gilleece.moonlightxr.debug/com.limelight.DepthV2BenchmarkActivity --es backend nnapi
adb shell am force-stop com.gilleece.moonlightxr.debug
adb shell am start -n com.gilleece.moonlightxr.debug/com.limelight.DepthV2BenchmarkActivity --es backend xnnpack
adb push /private/tmp/depth_anything_v2_qualcomm_518.tflite \
  /sdcard/Android/data/com.gilleece.moonlightxr.debug/files/depth_anything_v2_qualcomm_518.tflite
adb push build/depth-comparison/mmd-frames/frame_001.jpg \
  /sdcard/Android/data/com.gilleece.moonlightxr.debug/files/v2_benchmark.jpg
adb shell am force-stop com.gilleece.moonlightxr.debug
adb shell am start -n com.gilleece.moonlightxr.debug/com.limelight.DepthV2BenchmarkActivity \
  --es backend litert_gpu --ei runs 60
adb logcat -d -s XR3D-V2-BENCH:I '*:S'
```

The benchmark Activity and ONNX Runtime dependency are debug only. The regular image viewer and Moonlight video path still use MiDaS. The debug APK is about 101 MiB because it packages ONNX Runtime across the app's ABIs; the V2 weights are pushed separately.
