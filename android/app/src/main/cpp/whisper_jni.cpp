#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <string>
#include <sstream>
#include <thread>
#include <atomic>
#include <vector>
#include <memory>
#include <new>
struct NativeSession { whisper_context *context; std::atomic_bool aborted{false}; };

static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
class UtfChars {
    JNIEnv *env;
    jstring value;
public:
    const char *chars;
    UtfChars(JNIEnv *env, jstring value) : env(env), value(value), chars(env->GetStringUTFChars(value, nullptr)) {}
    ~UtfChars() { if (chars) env->ReleaseStringUTFChars(value, chars); }
};
static std::string quoted(const char *text) {
    std::string out = "\"";
    const char hex[] = "0123456789abcdef";
    for (const unsigned char *p = reinterpret_cast<const unsigned char *>(text); *p; ++p) {
        if (*p == '"' || *p == '\\') { out += '\\'; out += static_cast<char>(*p); }
        else if (*p < 32) { out += "\\u00"; out += hex[*p >> 4]; out += hex[*p & 15]; }
        else out += static_cast<char>(*p);
    }
    return out + "\"";
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_hellfire_ass_media_WhisperNative_load(JNIEnv *env, jobject, jstring path) try {
    if (!path) { fail(env, "Transcription model path required"); return 0; }
    const UtfChars model(env, path);
    if (!model.chars) return 0;
    auto options = whisper_context_default_params();
    options.use_gpu = false;
    std::unique_ptr<whisper_context, decltype(&whisper_free)> context(
        whisper_init_from_file_with_params(model.chars, options), whisper_free);
    if (!context) { fail(env, "Local transcription model could not be loaded"); return 0; }
    auto *session = new NativeSession{context.get()};
    context.release();
    return reinterpret_cast<jlong>(session);
} catch (const std::bad_alloc &) {
    env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Transcription session allocation failed");
    return 0;
} catch (const std::exception &) {
    fail(env, "Transcription model could not be loaded");
    return 0;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hellfire_ass_media_WhisperNative_transcribe(JNIEnv *env, jobject, jlong handle, jfloatArray samples, jstring language) try {
    auto *session = reinterpret_cast<NativeSession *>(handle);
    auto *context = session ? session->context : nullptr;
    if (!context) { fail(env, "Transcription context unavailable"); return nullptr; }
    if (!samples || !language) { fail(env, "Audio and language required"); return nullptr; }
    const int count = env->GetArrayLength(samples);
    if (count <= 0 || count > 16000 * 300) { fail(env, "Audio chunk exceeds the five-minute inference bound"); return nullptr; }
    std::string lang;
    {
        const UtfChars value(env, language);
        if (!value.chars) return nullptr;
        lang = value.chars;
    }
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::min(6, std::max(1, static_cast<int>(std::thread::hardware_concurrency())));
    params.language = lang.c_str();
    params.abort_callback = [](void *data) { return static_cast<NativeSession *>(data)->aborted.load(); };
    params.abort_callback_user_data = session;
    params.translate = false;
    params.no_context = true;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    // At most 19.2 MB; no JNI array borrow is held across inference.
    int result;
    {
        std::vector<float> audio(count);
        env->GetFloatArrayRegion(samples, 0, count, audio.data());
        if (env->ExceptionCheck()) return nullptr;
        result = whisper_full(context, params, audio.data(), count);
    }
    if (result != 0) { fail(env, "Local transcription failed"); return nullptr; }
    std::ostringstream output;
    output << "{\"language\":" << quoted(whisper_lang_str(whisper_full_lang_id(context))) << ",\"segments\":[";
    for (int i = 0; i < whisper_full_n_segments(context); ++i) {
        if (i) output << ',';
        output << "{\"start_ms\":" << whisper_full_get_segment_t0(context, i) * 10
            << ",\"end_ms\":" << whisper_full_get_segment_t1(context, i) * 10
            << ",\"text\":" << quoted(whisper_full_get_segment_text(context, i)) << '}';
    }
    output << "]}";
    const auto text = output.str();
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return bytes;
} catch (const std::bad_alloc &) {
    env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Native transcription allocation failed");
    return nullptr;
} catch (const std::exception &) {
    fail(env, "Native transcription failed");
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL
Java_com_hellfire_ass_media_WhisperNative_release(JNIEnv *, jobject, jlong handle) {
    if (handle) { auto *session = reinterpret_cast<NativeSession *>(handle); whisper_free(session->context); delete session; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hellfire_ass_media_WhisperNative_abort(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<NativeSession *>(handle)->aborted = true;
}
extern "C" JNIEXPORT void JNICALL
Java_com_hellfire_ass_media_WhisperNative_reset(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<NativeSession *>(handle)->aborted = false;
}
