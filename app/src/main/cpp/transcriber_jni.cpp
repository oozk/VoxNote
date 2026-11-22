// JNI bridge between the Kotlin app and whisper.cpp.
//
// Design notes
//  * All inference runs on the thread that calls nativeTranscribe (a Kotlin
//    background dispatcher). ggml spawns its own worker threads; those threads are
//    never attached to the JVM, therefore cancellation and progress are exchanged
//    through std::atomic fields only (no JNI calls from worker threads).
//  * New-segment notifications ARE delivered through JNI, but whisper.cpp invokes
//    that callback on the calling thread, which is attached. We verify this.
//  * "Vocabulary boost" is a decoder-side logit bias built from a token trie of
//    user keywords. It nudges the text decoder towards spelling a keyword the
//    same way once the decoder has already started emitting it. It is NOT
//    acoustic-model biasing and cannot make the model "hear" a word.

#include <jni.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "whisper.h"
#include "ggml.h"

#if defined(__ANDROID__)
#include <android/log.h>
#define LOG_TAG "TranscriberJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { fprintf(stderr, "[I] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGW(...) do { fprintf(stderr, "[W] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGE(...) do { fprintf(stderr, "[E] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

namespace {

// Result codes shared with Kotlin (WhisperNative.kt)
constexpr int RC_OK = 0;
constexpr int RC_CANCELLED = 1;
constexpr int RC_FAILED = 2;
constexpr int RC_BAD_ARGS = 3;

struct BiasData {
    std::vector<std::vector<whisper_token>> sequences;  // tokenized keywords
    float strength = 0.0f;                              // logit bonus for continuing a keyword
    float start_strength = 0.0f;                        // logit bonus for starting a keyword
    whisper_token token_beg = 0;                        // first timestamp token id
    whisper_token token_eot = 0;
};

struct Engine {
    whisper_context *ctx = nullptr;
    std::atomic<bool> cancel{false};
    std::atomic<int> progress{0};
    std::mutex run_mutex;  // one transcription at a time per context
    std::string model_path;
};

struct CallbackCtx {
    JNIEnv *env = nullptr;
    jobject listener = nullptr;
    jmethodID on_segment = nullptr;
    std::thread::id thread_id;
};

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// whisper.cpp emits UTF-8 that can contain partial multi-byte sequences at token
// boundaries; NewStringUTF aborts the VM on invalid "modified UTF-8". Build the
// Java string from UTF-16 manually, replacing invalid sequences with U+FFFD.
jstring utf8_to_jstring(JNIEnv *env, const char *s) {
    if (s == nullptr) return env->NewStringUTF("");
    std::u16string out;
    const auto *p = reinterpret_cast<const unsigned char *>(s);
    size_t len = strlen(s);
    size_t i = 0;
    while (i < len) {
        uint32_t cp;
        unsigned char c = p[i];
        size_t extra;
        if (c < 0x80) { cp = c; extra = 0; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; extra = 1; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; extra = 2; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; extra = 3; }
        else { out.push_back(0xFFFD); i++; continue; }
        bool ok = true;
        for (size_t k = 1; k <= extra; k++) {
            if (i + k >= len || (p[i + k] & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (p[i + k] & 0x3F);
        }
        if (!ok) { out.push_back(0xFFFD); i++; continue; }
        i += extra + 1;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(out.data()), static_cast<jsize>(out.size()));
}

bool abort_cb(void *user_data) {
    auto *engine = static_cast<Engine *>(user_data);
    return engine->cancel.load(std::memory_order_relaxed);
}

bool encoder_begin_cb(whisper_context *, whisper_state *, void *user_data) {
    auto *engine = static_cast<Engine *>(user_data);
    return !engine->cancel.load(std::memory_order_relaxed);  // false => abort
}

void progress_cb(whisper_context *, whisper_state *, int progress, void *user_data) {
    auto *engine = static_cast<Engine *>(user_data);
    engine->progress.store(progress, std::memory_order_relaxed);
}

void new_segment_cb(whisper_context *ctx, whisper_state *, int n_new, void *user_data) {
    auto *cb = static_cast<CallbackCtx *>(user_data);
    if (cb == nullptr || cb->listener == nullptr || cb->on_segment == nullptr) return;
    if (std::this_thread::get_id() != cb->thread_id) return;  // never call JNI off-thread
    JNIEnv *env = cb->env;
    const int n = whisper_full_n_segments(ctx);
    for (int i = n - n_new; i < n; i++) {
        if (i < 0) continue;
        jstring text = utf8_to_jstring(env, whisper_full_get_segment_text(ctx, i));
        env->CallVoidMethod(cb->listener, cb->on_segment, static_cast<jint>(i),
                            static_cast<jlong>(whisper_full_get_segment_t0(ctx, i)) * 10,
                            static_cast<jlong>(whisper_full_get_segment_t1(ctx, i)) * 10, text);
        env->DeleteLocalRef(text);
        if (env->ExceptionCheck()) { env->ExceptionClear(); }
    }
}

// Decoder-side keyword boost. `tokens` holds the tokens generated so far in the
// current 30 s window (text and timestamp tokens). We look at the trailing text
// tokens; if they equal the first k tokens of a keyword, the (k+1)-th keyword token
// receives a logit bonus. A smaller bonus is applied to the first token of each
// keyword. Logits are pre-softmax, so a bonus of b multiplies the odds by e^b.
void logits_filter_cb(whisper_context *, whisper_state *, const whisper_token_data *tokens, int n_tokens,
                      float *logits, void *user_data) {
    const auto *bias = static_cast<const BiasData *>(user_data);
    if (bias == nullptr || bias->sequences.empty()) return;

    // collect up to 16 trailing text tokens (skip timestamps / specials)
    whisper_token tail[16];
    int n_tail = 0;
    for (int i = n_tokens - 1; i >= 0 && n_tail < 16; i--) {
        const whisper_token id = tokens[i].id;
        if (id >= bias->token_eot) break;  // timestamp or special token ends the text run
        tail[n_tail++] = id;
    }
    // tail is reversed: tail[0] is the most recent token

    for (const auto &seq : bias->sequences) {
        const int L = static_cast<int>(seq.size());
        if (L == 0) continue;
        if (bias->start_strength > 0.0f) {
            logits[seq[0]] += bias->start_strength;
        }
        // longest matched prefix of length k (1..L-1) ending at the most recent token
        for (int k = std::min(L - 1, n_tail); k >= 1; k--) {
            bool match = true;
            for (int j = 0; j < k; j++) {
                // prefix seq[0..k) must equal tokens tail[k-1] .. tail[0]
                if (seq[j] != tail[k - 1 - j]) { match = false; break; }
            }
            if (match) {
                logits[seq[k]] += bias->strength;
                break;
            }
        }
    }
}

Engine *as_engine(jlong handle) { return reinterpret_cast<Engine *>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeSystemInfo(JNIEnv *env, jclass) {
    std::string info = std::string("whisper.cpp ") + WHISPER_VERSION + " | " + whisper_print_system_info();
    return utf8_to_jstring(env, info.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeInit(JNIEnv *env, jclass, jstring jpath, jboolean flash_attn) {
    std::string path = jstring_to_std(env, jpath);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // CPU backend only (see docs/ARCHITECTURE.md)
    cparams.flash_attn = flash_attn == JNI_TRUE;
    LOGI("Loading model %s", path.c_str());
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params failed for %s", path.c_str());
        return 0;
    }
    auto *engine = new Engine();
    engine->ctx = ctx;
    engine->model_path = path;
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    Engine *engine = as_engine(handle);
    if (engine == nullptr) return;
    engine->cancel.store(true);
    {
        std::lock_guard<std::mutex> lock(engine->run_mutex);  // wait for a running job to stop
        whisper_free(engine->ctx);
        engine->ctx = nullptr;
    }
    delete engine;
}

JNIEXPORT jstring JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeModelType(JNIEnv *env, jclass, jlong handle) {
    Engine *engine = as_engine(handle);
    if (engine == nullptr || engine->ctx == nullptr) return utf8_to_jstring(env, "");
    return utf8_to_jstring(env, whisper_model_type_readable(engine->ctx));
}

JNIEXPORT jboolean JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeIsMultilingual(JNIEnv *, jclass, jlong handle) {
    Engine *engine = as_engine(handle);
    if (engine == nullptr || engine->ctx == nullptr) return JNI_FALSE;
    return whisper_is_multilingual(engine->ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeCountTokens(JNIEnv *env, jclass, jlong handle, jstring jtext) {
    Engine *engine = as_engine(handle);
    if (engine == nullptr || engine->ctx == nullptr) return -1;
    std::string text = jstring_to_std(env, jtext);
    // whisper_tokenize returns -(needed) when the buffer is too small
    int n = whisper_tokenize(engine->ctx, text.c_str(), nullptr, 0);
    return n < 0 ? -n : n;
}

JNIEXPORT void JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeCancel(JNIEnv *, jclass, jlong handle) {
    Engine *engine = as_engine(handle);
    if (engine != nullptr) engine->cancel.store(true);
}

JNIEXPORT jint JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeProgress(JNIEnv *, jclass, jlong handle) {
    Engine *engine = as_engine(handle);
    return engine == nullptr ? 0 : engine->progress.load();
}

JNIEXPORT jint JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeTranscribe(
        JNIEnv *env, jclass, jlong handle, jfloatArray jsamples, jint n_threads, jstring jlanguage,
        jboolean translate, jstring jprompt, jboolean carry_prompt, jboolean no_timestamps, jint beam_size,
        jobjectArray jbias_terms, jfloat bias_strength, jboolean vad, jstring jvad_model, jobject listener) {
    Engine *engine = as_engine(handle);
    if (engine == nullptr || engine->ctx == nullptr || jsamples == nullptr) return RC_BAD_ARGS;
    std::lock_guard<std::mutex> lock(engine->run_mutex);
    if (engine->ctx == nullptr) return RC_BAD_ARGS;
    engine->cancel.store(false);
    engine->progress.store(0);

    const std::string language = jstring_to_std(env, jlanguage);
    const std::string prompt = jstring_to_std(env, jprompt);
    const std::string vad_model = jstring_to_std(env, jvad_model);

    whisper_full_params params = whisper_full_default_params(
            beam_size > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    params.n_threads = n_threads > 0 ? n_threads : 4;
    params.translate = translate == JNI_TRUE;
    params.no_timestamps = no_timestamps == JNI_TRUE;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.language = language.empty() ? "auto" : language.c_str();
    params.detect_language = false;  // "auto" already triggers detection + transcription
    params.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
    params.carry_initial_prompt = carry_prompt == JNI_TRUE && !prompt.empty();
    params.suppress_blank = true;
    params.suppress_nst = true;
    if (beam_size > 1) {
        params.beam_search.beam_size = beam_size;
    } else {
        params.greedy.best_of = 2;
    }

    // Silero VAD: whisper.cpp transcribes only detected speech and maps the
    // timestamps back to the original audio (fewer hallucinations in silence, faster).
    if (vad == JNI_TRUE && !vad_model.empty()) {
        params.vad = true;
        params.vad_model_path = vad_model.c_str();
        params.vad_params = whisper_vad_default_params();
        params.vad_params.min_silence_duration_ms = 300;
        params.vad_params.speech_pad_ms = 200;
    }

    params.abort_callback = abort_cb;
    params.abort_callback_user_data = engine;
    params.encoder_begin_callback = encoder_begin_cb;
    params.encoder_begin_callback_user_data = engine;
    params.progress_callback = progress_cb;
    params.progress_callback_user_data = engine;

    CallbackCtx cb;
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        cb.env = env;
        cb.listener = listener;
        cb.on_segment = env->GetMethodID(cls, "onSegment", "(IJJLjava/lang/String;)V");
        cb.thread_id = std::this_thread::get_id();
        env->DeleteLocalRef(cls);
        if (cb.on_segment == nullptr) {
            env->ExceptionClear();
        } else {
            params.new_segment_callback = new_segment_cb;
            params.new_segment_callback_user_data = &cb;
        }
    }

    BiasData bias;
    if (jbias_terms != nullptr && bias_strength > 0.0f) {
        bias.strength = bias_strength;
        bias.start_strength = bias_strength * 0.25f;
        bias.token_eot = whisper_token_eot(engine->ctx);
        bias.token_beg = whisper_token_beg(engine->ctx);
        const jsize n_terms = env->GetArrayLength(jbias_terms);
        std::vector<whisper_token> buf(64);
        for (jsize i = 0; i < n_terms; i++) {
            auto jterm = static_cast<jstring>(env->GetObjectArrayElement(jbias_terms, i));
            std::string term = " " + jstring_to_std(env, jterm);  // decoder text usually starts with a space
            env->DeleteLocalRef(jterm);
            int n = whisper_tokenize(engine->ctx, term.c_str(), buf.data(), static_cast<int>(buf.size()));
            if (n <= 0) continue;
            std::vector<whisper_token> seq(buf.begin(), buf.begin() + n);
            bool valid = true;
            for (auto t : seq) if (t >= bias.token_eot) valid = false;
            if (valid) bias.sequences.push_back(std::move(seq));
        }
        if (!bias.sequences.empty()) {
            params.logits_filter_callback = logits_filter_cb;
            params.logits_filter_callback_user_data = &bias;
        }
    }

    const jsize n_samples = env->GetArrayLength(jsamples);
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);
    if (samples == nullptr) return RC_FAILED;

    LOGI("whisper_full: %d samples, %d threads, lang=%s, prompt=%zu chars, bias=%zu terms",
         static_cast<int>(n_samples), params.n_threads, params.language, prompt.size(), bias.sequences.size());
    const int rc = whisper_full(engine->ctx, params, samples, n_samples);
    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);

    if (engine->cancel.load()) return RC_CANCELLED;
    if (rc != 0) {
        LOGE("whisper_full failed with %d", rc);
        return RC_FAILED;
    }
    engine->progress.store(100);
    return RC_OK;
}

JNIEXPORT jint JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeSegmentCount(JNIEnv *, jclass, jlong handle) {
    Engine *engine = as_engine(handle);
    if (engine == nullptr || engine->ctx == nullptr) return 0;
    return whisper_full_n_segments(engine->ctx);
}

JNIEXPORT jstring JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeSegmentText(JNIEnv *env, jclass, jlong handle, jint i) {
    Engine *engine = as_engine(handle);
    if (engine == nullptr || engine->ctx == nullptr) return utf8_to_jstring(env, "");
    return utf8_to_jstring(env, whisper_full_get_segment_text(engine->ctx, i));
}

// Timestamps are returned in milliseconds (whisper uses 10 ms units).
JNIEXPORT jlong JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeSegmentStartMs(JNIEnv *, jclass, jlong handle, jint i) {
    Engine *engine = as_engine(handle);
    if (engine == nullptr || engine->ctx == nullptr) return 0;
    return static_cast<jlong>(whisper_full_get_segment_t0(engine->ctx, i)) * 10;
}

JNIEXPORT jlong JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeSegmentEndMs(JNIEnv *, jclass, jlong handle, jint i) {
    Engine *engine = as_engine(handle);
    if (engine == nullptr || engine->ctx == nullptr) return 0;
    return static_cast<jlong>(whisper_full_get_segment_t1(engine->ctx, i)) * 10;
}

JNIEXPORT jfloat JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeSegmentNoSpeechProb(JNIEnv *, jclass, jlong handle, jint i) {
    Engine *engine = as_engine(handle);
    if (engine == nullptr || engine->ctx == nullptr) return 0.0f;
    return whisper_full_get_segment_no_speech_prob(engine->ctx, i);
}

/** Mean probability of the segment's text tokens (0..1), a rough recognition confidence. */
JNIEXPORT jfloat JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeSegmentConfidence(JNIEnv *, jclass, jlong handle, jint i) {
    Engine *engine = as_engine(handle);
    if (engine == nullptr || engine->ctx == nullptr) return -1.0f;
    const int n = whisper_full_n_tokens(engine->ctx, i);
    const whisper_token eot = whisper_token_eot(engine->ctx);
    double sum = 0.0;
    int count = 0;
    for (int k = 0; k < n; k++) {
        if (whisper_full_get_token_id(engine->ctx, i, k) >= eot) continue;  // skip timestamps/specials
        sum += whisper_full_get_token_p(engine->ctx, i, k);
        count++;
    }
    return count == 0 ? -1.0f : static_cast<jfloat>(sum / count);
}

JNIEXPORT jstring JNICALL
Java_com_offlinetranscriber_engine_WhisperNative_nativeDetectedLanguage(JNIEnv *env, jclass, jlong handle) {
    Engine *engine = as_engine(handle);
    if (engine == nullptr || engine->ctx == nullptr) return utf8_to_jstring(env, "");
    const int id = whisper_full_lang_id(engine->ctx);
    if (id < 0) return utf8_to_jstring(env, "");
    const char *code = whisper_lang_str(id);
    return utf8_to_jstring(env, code ? code : "");
}

}  // extern "C"
