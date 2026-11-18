#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
jdk_dir=$(dirname -- "$(dirname -- "$(readlink -f -- "$(command -v javac)")")")
test_dir=$(mktemp -d)
trap 'rm -rf -- "$test_dir"' EXIT
test_compiler="${CXX:-c++}"
test_flags=(-O2)
test_sanitized=false
if [[ "${1:-}" == "--sanitize" ]]; then
    test_flags=(-O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer)
    test_sanitized=true
elif [[ $# -ne 0 ]]; then
    echo 'Usage: test_native_jni.sh [--sanitize]' >&2
    exit 2
fi
"$test_compiler" -std=c++17 "${test_flags[@]}" -Wall -Wextra -Werror -fPIC -shared -pthread \
    -I"$jdk_dir/include" -I"$jdk_dir/include/linux" -I"$repo_dir/tools/native-jni" \
    "$repo_dir/android/app/src/main/cpp/whisper_jni.cpp" \
    "$repo_dir/tools/native-jni/whisper_stub.cpp" -o "$test_dir/libjni_test.so"
javac -d "$test_dir" "$repo_dir/tools/native-jni/WhisperNative.java"
if $test_sanitized; then
    test_asan_library=$("$test_compiler" -print-file-name=libasan.so)
    [[ -f "$test_asan_library" ]] || { echo 'Missing host AddressSanitizer runtime' >&2; exit 1; }
    # HotSpot owns VM/JIT allocations and uses SIGSEGV internally. This run checks
    # native address/undefined behavior, not JVM process leaks or Android execution.
    LD_PRELOAD="$test_asan_library${LD_PRELOAD:+:$LD_PRELOAD}" \
        ASAN_OPTIONS=detect_leaks=0:handle_segv=0:use_sigaltstack=0 \
        UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
        java -Xmx256m -Xcheck:jni -cp "$test_dir" com.hellfire.ass.media.WhisperNative "$test_dir/libjni_test.so"
else
    java -Xmx256m -Xcheck:jni -cp "$test_dir" com.hellfire.ass.media.WhisperNative "$test_dir/libjni_test.so"
fi
