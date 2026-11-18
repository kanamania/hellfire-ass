#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo 'Usage: tools/test_real_whisper.sh MODEL [EXISTING_PINNED_WHISPER_SOURCE]' >&2
    exit 2
fi
model_file=$(realpath -- "$1")
build_dir="$repo_dir/android/build/host-native"
cmake_args=()
if [[ $# == 2 ]]; then cmake_args+=("-DFETCHCONTENT_SOURCE_DIR_WHISPER=$(realpath -- "$2")"); fi
cmake -S "$repo_dir/android/app/src/main/cpp" -B "$build_dir" -DCMAKE_BUILD_TYPE=Release "${cmake_args[@]}"
cmake --build "$build_dir" --parallel 2
javac -d "$build_dir/classes" "$repo_dir/tools/native-real/WhisperNative.java"
sample_file="$build_dir/_deps/whisper-src/samples/jfk.wav"
if [[ $# == 2 ]]; then sample_file="$2/samples/jfk.wav"; fi
[[ -f "$sample_file" ]] || { echo 'Pinned upstream JFK sample is missing' >&2; exit 1; }
java -Xmx256m -Xcheck:jni -cp "$build_dir/classes" com.hellfire.ass.media.WhisperNative \
    "$build_dir/libhellfire_whisper.so" "$model_file" "$sample_file"
