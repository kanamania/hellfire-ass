#include "whisper.h"
#include <jni.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <thread>

static std::atomic_bool entered{false};
whisper_context_params whisper_context_default_params() { return {}; }
whisper_context *whisper_init_from_file_with_params(const char *path, whisper_context_params) {
    return std::strcmp(path, "invalid") == 0 ? nullptr : new whisper_context;
}
void whisper_free(whisper_context *context) { delete context; }
whisper_full_params whisper_full_default_params(int) { return {}; }
int whisper_full(whisper_context *, whisper_full_params params, const float *samples, int count) {
    entered = true;
    if (count < 2 || samples[0] != 0.25f || samples[count - 1] != -0.25f) return 1;
    if (std::strcmp(params.language, "sw") == 0) {
        for (int i = 0; i < 2000; ++i) {
            if (params.abort_callback(params.abort_callback_user_data)) return 1;
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        return 1; // Cancellation test must abort, never time out as a fake success.
    }
    return params.abort_callback(params.abort_callback_user_data) ? 1 : 0;
}
const char *whisper_lang_str(int) { return "en"; }
int whisper_full_lang_id(whisper_context *) { return 0; }
int whisper_full_n_segments(whisper_context *) { return 1; }
int64_t whisper_full_get_segment_t0(whisper_context *, int) { return 7; }
int64_t whisper_full_get_segment_t1(whisper_context *, int) { return 42; }
const char *whisper_full_get_segment_text(whisper_context *, int) { return "Habari \"dunia\"\n\t\\ café"; }
extern "C" JNIEXPORT jboolean JNICALL Java_com_hellfire_ass_media_WhisperNative_entered(JNIEnv *, jobject) { return entered; }
extern "C" JNIEXPORT void JNICALL Java_com_hellfire_ass_media_WhisperNative_clearEntered(JNIEnv *, jobject) { entered = false; }
