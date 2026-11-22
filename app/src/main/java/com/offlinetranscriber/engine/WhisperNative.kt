package com.offlinetranscriber.engine

/** Callback invoked by native code (on the transcribing thread) for every new segment. */
interface NativeSegmentListener {
    fun onSegment(index: Int, startMs: Long, endMs: Long, text: String)
}

/** Raw JNI bindings to app/src/main/cpp/transcriber_jni.cpp. */
object WhisperNative {
    const val RC_OK = 0
    const val RC_CANCELLED = 1
    const val RC_FAILED = 2
    const val RC_BAD_ARGS = 3

    @Volatile
    private var loaded = false

    /** Loads libtranscriber_jni from the APK. Tests may call [loadFrom] instead. */
    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("transcriber_jni")
            loaded = true
        }
    }

    @Synchronized
    fun loadFrom(absolutePath: String) {
        if (!loaded) {
            System.load(absolutePath)
            loaded = true
        }
    }

    @JvmStatic external fun nativeSystemInfo(): String
    @JvmStatic external fun nativeInit(path: String, flashAttention: Boolean): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeModelType(handle: Long): String
    @JvmStatic external fun nativeIsMultilingual(handle: Long): Boolean
    @JvmStatic external fun nativeCountTokens(handle: Long, text: String): Int
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeProgress(handle: Long): Int
    @JvmStatic external fun nativeTranscribe(
        handle: Long,
        samples: FloatArray,
        threads: Int,
        language: String,
        translate: Boolean,
        prompt: String?,
        carryPrompt: Boolean,
        noTimestamps: Boolean,
        beamSize: Int,
        boostTerms: Array<String>?,
        boostStrength: Float,
        listener: NativeSegmentListener?,
    ): Int
    @JvmStatic external fun nativeSegmentCount(handle: Long): Int
    @JvmStatic external fun nativeSegmentText(handle: Long, index: Int): String
    @JvmStatic external fun nativeSegmentStartMs(handle: Long, index: Int): Long
    @JvmStatic external fun nativeSegmentEndMs(handle: Long, index: Int): Long
    @JvmStatic external fun nativeSegmentNoSpeechProb(handle: Long, index: Int): Float
    @JvmStatic external fun nativeDetectedLanguage(handle: Long): String
}
