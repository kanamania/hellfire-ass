// Synthetic whisper API for testing the real JNI boundary on a host JVM. No inference or model weights.
#pragma once
#include <cstdint>
struct whisper_context {};
struct whisper_context_params { bool use_gpu; };
enum { WHISPER_SAMPLING_GREEDY };
struct whisper_full_params {
    int n_threads;
    const char *language;
    bool (*abort_callback)(void *);
    void *abort_callback_user_data;
    bool translate, no_context, print_special, print_progress, print_realtime, print_timestamps;
};
whisper_context_params whisper_context_default_params();
whisper_context *whisper_init_from_file_with_params(const char *, whisper_context_params);
void whisper_free(whisper_context *);
whisper_full_params whisper_full_default_params(int);
int whisper_full(whisper_context *, whisper_full_params, const float *, int);
const char *whisper_lang_str(int);
int whisper_full_lang_id(whisper_context *);
int whisper_full_n_segments(whisper_context *);
int64_t whisper_full_get_segment_t0(whisper_context *, int);
int64_t whisper_full_get_segment_t1(whisper_context *, int);
const char *whisper_full_get_segment_text(whisper_context *, int);
