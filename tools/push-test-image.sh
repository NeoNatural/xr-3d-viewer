#!/usr/bin/env bash
set -euo pipefail

if (( $# < 1 || $# > 2 )); then
  echo "Usage: $0 IMAGE_PATH [DEVICE_NAME]" >&2
  exit 2
fi

image_path="$1"
image_name="${2:-$(basename "$image_path")}"
if [[ ! -f "$image_path" ]]; then
  echo "Image not found: $image_path" >&2
  exit 2
fi
if [[ ! "$image_name" =~ ^[A-Za-z0-9._-]+$ || "$image_name" == "." || "$image_name" == ".." ]]; then
  echo "Device name must be a simple filename" >&2
  exit 2
fi

device_dir=/sdcard/Android/data/com.gilleece.moonlightxr.debug/files/test_media
adb shell mkdir -p "$device_dir"
adb push "$image_path" "$device_dir/$image_name"
adb shell am start -n \
  com.gilleece.moonlightxr.debug/com.limelight.StaticImageXrActivity \
  --es testImageName "$image_name"
