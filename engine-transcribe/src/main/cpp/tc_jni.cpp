// JNI bridge for transcribe.cpp (second engine of VoxNote).
// Same rules as transcriber_jni.cpp: inference runs on the calling thread, and
// cancellation crosses threads only through std::atomic (never JNI).

#include <jni.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "transcribe.h"
#include "transcribe/diarize.h"

namespace {

struct AsrHandle {
    transcribe_session *session = nullptr;
    std::atomic<bool> cancel{false};
    std::mutex run_mutex;
};

struct DiarHandle {
    transcribe_model *model = nullptr;
    transcribe_diarize_session *session = nullptr;
    std::atomic<bool> cancel{false};
    std::mutex run_mutex;
};

bool abort_cb(void *user) { return static_cast<std::atomic<bool> *>(user)->load(std::memory_order_relaxed); }

std::string to_std(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

// Robust UTF-8 -> Java string (NewStringUTF aborts on invalid modified UTF-8).
jstring to_j(JNIEnv *env, const char *s) {
    std::u16string out;
    if (s) {
        const auto *p = reinterpret_cast<const unsigned char *>(s);
        size_t n = strlen(s), i = 0;
        while (i < n) {
            uint32_t cp; size_t extra;
            unsigned char c = p[i];
            if (c < 0x80) { cp = c; extra = 0; }
            else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; extra = 1; }
            else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; extra = 2; }
            else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; extra = 3; }
            else { out.push_back(0xFFFD); i++; continue; }
            bool ok = true;
            for (size_t k = 1; k <= extra; k++) {
                if (i + k >= n || (p[i + k] & 0xC0) != 0x80) { ok = false; break; }
                cp = (cp << 6) | (p[i + k] & 0x3F);
            }
            if (!ok) { out.push_back(0xFFFD); i++; continue; }
            i += extra + 1;
            if (cp >= 0x10000) { cp -= 0x10000; out.push_back(0xD800 + (cp >> 10)); out.push_back(0xDC00 + (cp & 0x3FF)); }
            else out.push_back(static_cast<char16_t>(cp));
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(out.data()), static_cast<jsize>(out.size()));
}

AsrHandle *asr(jlong h) { return reinterpret_cast<AsrHandle *>(h); }
DiarHandle *diar(jlong h) { return reinterpret_cast<DiarHandle *>(h); }

transcribe_status g_last_status = TRANSCRIBE_OK;

}  // namespace

#define FN(name) Java_com_offlinetranscriber_engine_tc_TranscribeNative_##name

extern "C" {

JNIEXPORT jstring JNICALL FN(nativeVersion)(JNIEnv *env, jclass) {
    std::string v = std::string("transcribe.cpp ") + transcribe_version();
    return to_j(env, v.c_str());
}

JNIEXPORT jstring JNICALL FN(nativeStatusString)(JNIEnv *env, jclass, jint status) {
    return to_j(env, transcribe_status_string(status));
}

JNIEXPORT jint JNICALL FN(nativeLastStatus)(JNIEnv *, jclass) { return g_last_status; }

/** Opens an ASR model on the CPU. Returns 0 on failure (see nativeLastStatus). */
JNIEXPORT jlong JNICALL FN(nativeOpen)(JNIEnv *env, jclass, jstring jpath, jint threads, jboolean noMmap) {
    std::string path = to_std(env, jpath);
    transcribe_init_backends_default();
    transcribe_model_load_params lp;
    transcribe_model_load_params_init(&lp);
    lp.backend = TRANSCRIBE_BACKEND_CPU;
    lp.no_mmap = noMmap == JNI_TRUE;
    transcribe_session_params sp;
    transcribe_session_params_init(&sp);
    sp.n_threads = threads;
    transcribe_session *s = nullptr;
    g_last_status = transcribe_open(path.c_str(), &lp, &sp, &s);
    if (g_last_status != TRANSCRIBE_OK || s == nullptr) return 0;
    auto *h = new AsrHandle();
    h->session = s;
    transcribe_set_abort_callback(s, abort_cb, &h->cancel);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL FN(nativeClose)(JNIEnv *, jclass, jlong handle) {
    AsrHandle *h = asr(handle);
    if (!h) return;
    h->cancel.store(true);
    { std::lock_guard<std::mutex> l(h->run_mutex); transcribe_session_free(h->session); h->session = nullptr; }
    delete h;
}

/** "key=value" lines describing the loaded model. */
JNIEXPORT jstring JNICALL FN(nativeModelInfo)(JNIEnv *env, jclass, jlong handle) {
    AsrHandle *h = asr(handle);
    if (!h || !h->session) return to_j(env, "");
    const transcribe_model *m = transcribe_get_model(h->session);
    transcribe_capabilities caps;
    transcribe_capabilities_init(&caps);
    transcribe_model_get_capabilities(m, &caps);
    std::string r;
    r += "arch="; r += transcribe_model_arch_string(m) ? transcribe_model_arch_string(m) : ""; r += "\n";
    r += "variant="; r += transcribe_model_variant_string(m) ? transcribe_model_variant_string(m) : ""; r += "\n";
    r += "languages=";
    for (int i = 0; i < caps.n_languages; i++) { if (i) r += ","; r += caps.languages[i]; }
    r += "\n";
    r += std::string("language_detect=") + (caps.supports_language_detect ? "1" : "0") + "\n";
    r += std::string("vocabulary=") + (transcribe_model_supports(m, TRANSCRIBE_FEATURE_VOCABULARY) ? "1" : "0") + "\n";
    r += std::string("prompt=") + ((transcribe_model_supports(m, TRANSCRIBE_FEATURE_CONTEXT_PROMPT) ||
                                     transcribe_model_supports(m, TRANSCRIBE_FEATURE_INITIAL_PROMPT)) ? "1" : "0") + "\n";
    r += std::string("diarization=") + (transcribe_model_supports(m, TRANSCRIBE_FEATURE_DIARIZATION) ? "1" : "0") + "\n";
    r += "max_audio_ms=" + std::to_string(caps.max_audio_ms) + "\n";
    return to_j(env, r.c_str());
}

JNIEXPORT void JNICALL FN(nativeCancel)(JNIEnv *, jclass, jlong handle) {
    if (AsrHandle *h = asr(handle)) h->cancel.store(true);
}

/** Returns a transcribe_status (0 = OK, 13 = aborted). */
JNIEXPORT jint JNICALL FN(nativeRun)(JNIEnv *env, jclass, jlong handle, jfloatArray jpcm, jstring jlang,
                                     jstring jprompt, jobjectArray jvocab, jboolean timestamps) {
    AsrHandle *h = asr(handle);
    if (!h || !h->session || !jpcm) return TRANSCRIBE_ERR_INVALID_ARG;
    std::lock_guard<std::mutex> l(h->run_mutex);
    h->cancel.store(false);
    const transcribe_model *m = transcribe_get_model(h->session);

    std::string lang = to_std(env, jlang);
    std::string prompt = to_std(env, jprompt);
    std::vector<std::string> vocab;
    if (jvocab && transcribe_model_supports(m, TRANSCRIBE_FEATURE_VOCABULARY)) {
        jsize n = env->GetArrayLength(jvocab);
        for (jsize i = 0; i < n; i++) {
            auto s = static_cast<jstring>(env->GetObjectArrayElement(jvocab, i));
            vocab.push_back(to_std(env, s));
            env->DeleteLocalRef(s);
        }
    }
    std::vector<const char *> vptr;
    for (auto &s : vocab) vptr.push_back(s.c_str());

    transcribe_run_params rp;
    transcribe_run_params_init(&rp);
    rp.task = TRANSCRIBE_TASK_TRANSCRIBE;
    rp.timestamps = timestamps ? TRANSCRIBE_TIMESTAMPS_SEGMENT : TRANSCRIBE_TIMESTAMPS_AUTO;
    rp.language = (lang.empty() || lang == "auto") ? nullptr : lang.c_str();
    // only pass features the model declares, so unsupported ones never log warnings or fail
    bool canPrompt = transcribe_model_supports(m, TRANSCRIBE_FEATURE_CONTEXT_PROMPT) ||
                     transcribe_model_supports(m, TRANSCRIBE_FEATURE_INITIAL_PROMPT);
    rp.prompt = (canPrompt && !prompt.empty()) ? prompt.c_str() : nullptr;
    rp.vocabulary = vptr.empty() ? nullptr : vptr.data();
    rp.n_vocabulary = static_cast<int32_t>(vptr.size());

    jsize n = env->GetArrayLength(jpcm);
    jfloat *pcm = env->GetFloatArrayElements(jpcm, nullptr);
    transcribe_status st = transcribe_run(h->session, pcm, n, &rp);
    env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);
    g_last_status = st;
    if (h->cancel.load()) return TRANSCRIBE_ERR_ABORTED;
    return st;
}

JNIEXPORT jint JNICALL FN(nativeSegmentCount)(JNIEnv *, jclass, jlong handle) {
    AsrHandle *h = asr(handle);
    return (h && h->session) ? transcribe_n_segments(h->session) : 0;
}

/** Segment i as [t0Ms, t1Ms, speakerId]. */
JNIEXPORT jlongArray JNICALL FN(nativeSegmentTiming)(JNIEnv *env, jclass, jlong handle, jint i) {
    jlong v[3] = {0, 0, 0};
    AsrHandle *h = asr(handle);
    if (h && h->session) {
        transcribe_segment s;
        transcribe_segment_init(&s);
        if (transcribe_get_segment(h->session, i, &s) == TRANSCRIBE_OK) { v[0] = s.t0_ms; v[1] = s.t1_ms; v[2] = s.speaker_id; }
    }
    jlongArray out = env->NewLongArray(3);
    env->SetLongArrayRegion(out, 0, 3, v);
    return out;
}

JNIEXPORT jstring JNICALL FN(nativeSegmentText)(JNIEnv *env, jclass, jlong handle, jint i) {
    AsrHandle *h = asr(handle);
    if (!h || !h->session) return to_j(env, "");
    transcribe_segment s;
    transcribe_segment_init(&s);
    transcribe_get_segment(h->session, i, &s);
    return to_j(env, s.text);
}

JNIEXPORT jstring JNICALL FN(nativeFullText)(JNIEnv *env, jclass, jlong handle) {
    AsrHandle *h = asr(handle);
    return to_j(env, (h && h->session) ? transcribe_full_text(h->session) : "");
}

JNIEXPORT jstring JNICALL FN(nativeDetectedLanguage)(JNIEnv *env, jclass, jlong handle) {
    AsrHandle *h = asr(handle);
    const char *l = (h && h->session) ? transcribe_detected_language(h->session) : nullptr;
    return to_j(env, l ? l : "");
}

JNIEXPORT jint JNICALL FN(nativeTokenize)(JNIEnv *env, jclass, jlong handle, jstring jtext) {
    AsrHandle *h = asr(handle);
    if (!h || !h->session) return -1;
    std::string t = to_std(env, jtext);
    int n = transcribe_tokenize(transcribe_get_model(h->session), t.c_str(), nullptr, 0);
    return n < 0 ? -n : n;
}

// ---------------- speaker diarization (Sortformer) ----------------

JNIEXPORT jlong JNICALL FN(nativeDiarizeOpen)(JNIEnv *env, jclass, jstring jpath, jint threads) {
    std::string path = to_std(env, jpath);
    transcribe_init_backends_default();
    transcribe_model_load_params lp;
    transcribe_model_load_params_init(&lp);
    lp.backend = TRANSCRIBE_BACKEND_CPU;
    transcribe_model *m = nullptr;
    g_last_status = transcribe_model_load_file(path.c_str(), &lp, &m);
    if (g_last_status != TRANSCRIBE_OK || !m) return 0;
    transcribe_diarize_session_params sp;
    transcribe_diarize_session_params_init(&sp);
    sp.n_threads = threads;
    transcribe_diarize_session *s = nullptr;
    g_last_status = transcribe_diarize_session_init(m, &sp, &s);
    if (g_last_status != TRANSCRIBE_OK || !s) { transcribe_model_free(m); return 0; }
    auto *h = new DiarHandle();
    h->model = m;
    h->session = s;
    transcribe_diarize_set_abort_callback(s, abort_cb, &h->cancel);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL FN(nativeDiarizeClose)(JNIEnv *, jclass, jlong handle) {
    DiarHandle *h = diar(handle);
    if (!h) return;
    h->cancel.store(true);
    {
        std::lock_guard<std::mutex> l(h->run_mutex);
        transcribe_diarize_session_free(h->session);
        transcribe_model_free(h->model);
    }
    delete h;
}

JNIEXPORT void JNICALL FN(nativeDiarizeCancel)(JNIEnv *, jclass, jlong handle) {
    if (DiarHandle *h = diar(handle)) h->cancel.store(true);
}

/** Runs diarization; returns flattened [t0Ms, t1Ms, speakerId]* or null on failure. */
JNIEXPORT jlongArray JNICALL FN(nativeDiarizeRun)(JNIEnv *env, jclass, jlong handle, jfloatArray jpcm) {
    DiarHandle *h = diar(handle);
    if (!h || !jpcm) return nullptr;
    std::lock_guard<std::mutex> l(h->run_mutex);
    h->cancel.store(false);
    jsize n = env->GetArrayLength(jpcm);
    jfloat *pcm = env->GetFloatArrayElements(jpcm, nullptr);
    transcribe_status st = transcribe_diarize_run(h->session, pcm, n, nullptr);
    env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);
    g_last_status = st;
    if (st != TRANSCRIBE_OK) return nullptr;
    int count = transcribe_diarize_n_segments(h->session);
    std::vector<jlong> v;
    for (int i = 0; i < count; i++) {
        transcribe_speaker_segment s;
        transcribe_speaker_segment_init(&s);
        if (transcribe_diarize_get_segment(h->session, i, &s) == TRANSCRIBE_OK && s.speaker_id > 0) {
            v.push_back(s.t0_ms); v.push_back(s.t1_ms); v.push_back(s.speaker_id);
        }
    }
    jlongArray out = env->NewLongArray(static_cast<jsize>(v.size()));
    if (!v.empty()) env->SetLongArrayRegion(out, 0, static_cast<jsize>(v.size()), v.data());
    return out;
}

}  // extern "C"
