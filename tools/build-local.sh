#!/usr/bin/env bash
set -euo pipefail

# ndk-build cannot parse the space in this checkout's parent directory.
# Build a synchronized copy at a physical path without spaces.
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
build_dir="${TMPDIR:-/private/tmp}/moonlight-xr-agent-build"

if [[ "$build_dir" == *" "* ]]; then
  echo "Build directory must not contain spaces: $build_dir" >&2
  exit 2
fi

mkdir -p "$build_dir"
rsync -a --delete \
  --exclude '.git' \
  --exclude '.gradle' \
  --exclude 'build' \
  "$repo_dir/" "$build_dir/"

if [[ ! -f "$build_dir/local.properties" ]]; then
  sdk_dir="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  printf 'sdk.dir=%s\n' "$sdk_dir" > "$build_dir/local.properties"
fi

tasks=("$@")
if (( ${#tasks[@]} == 0 )); then
  tasks=(assembleNonRootDebug)
fi

(cd "$build_dir" && ./gradlew --no-daemon "${tasks[@]}")

output_dir="$repo_dir/build/agent-apks"
for task in "${tasks[@]}"; do
  case "$task" in
    assembleNonRootDebug)
      mkdir -p "$output_dir"
      cp "$build_dir"/app/build/outputs/apk/nonRoot/debug/*.apk "$output_dir/"
      echo "Debug APK: $output_dir"
      ;;
    assembleNonRootRelease)
      mkdir -p "$output_dir"
      cp "$build_dir"/app/build/outputs/apk/nonRoot/release/*.apk "$output_dir/"
      echo "Release APK: $output_dir"
      ;;
  esac
done
