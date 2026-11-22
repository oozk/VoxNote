package com.offlinetranscriber.engine.tc

/** Raw JNI bindings to engine-transcribe/src/main/cpp/tc_jni.cpp (transcribe.cpp). */
object TranscribeNative {
    const val OK = 0
    const val ERR_ABORTED = 13

    @Volatile private var loaded = false

    @Synchronized fun ensureLoaded() {
        if (!loaded) { System.loadLibrary("transcribe_jni"); loaded = true }
    }

    @Synchronized fun loadFrom(absolutePath: String) {
        if (!loaded) { System.load(absolutePath); loaded = true }
    }

    @JvmStatic external fun nativeVersion(): String
    @JvmStatic external fun nativeStatusString(status: Int): String
    @JvmStatic external fun nativeLastStatus(): Int
    @JvmStatic external fun nativeOpen(path: String, threads: Int, noMmap: Boolean): Long
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativeModelInfo(handle: Long): String
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeRun(handle: Long, pcm: FloatArray, language: String?, prompt: String?, vocabulary: Array<String>?, timestamps: Boolean): Int
    @JvmStatic external fun nativeSegmentCount(handle: Long): Int
    @JvmStatic external fun nativeSegmentTiming(handle: Long, index: Int): LongArray
    @JvmStatic external fun nativeSegmentText(handle: Long, index: Int): String
    @JvmStatic external fun nativeFullText(handle: Long): String
    @JvmStatic external fun nativeDetectedLanguage(handle: Long): String
    @JvmStatic external fun nativeTokenize(handle: Long, text: String): Int
    @JvmStatic external fun nativeDiarizeOpen(path: String, threads: Int): Long
    @JvmStatic external fun nativeDiarizeClose(handle: Long)
    @JvmStatic external fun nativeDiarizeCancel(handle: Long)
    @JvmStatic external fun nativeDiarizeRun(handle: Long, pcm: FloatArray): LongArray?
}
