package com.offlinetranscriber.data

import com.offlinetranscriber.export.ExportFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PerformanceMode(val label: String, val description: String) {
    BATTERY("Battery saver", "2 CPU threads. Slower, cooler, uses less battery."),
    BALANCED("Balanced", "Up to 4 threads. Recommended for most phones."),
    MAXIMUM("Maximum speed", "Up to 8 threads. Fastest, phone may get warm."),
}

enum class Quality(val label: String, val description: String, val beamSize: Int) {
    FAST("Fast", "Greedy decoding. Recommended on phones.", 1),
    BETTER("Better", "Beam search (3 beams). About 2× slower.", 3),
    BEST("Best", "Beam search (5 beams). Slowest, slightly more accurate.", 5),
}

enum class BoostLevel(val label: String, val strength: Float) {
    OFF("Off", 0f),
    LOW("Low", 1.5f),
    MEDIUM("Medium", 3f),
    HIGH("High", 5f),
}

data class AppSettings(
    val defaultModelId: Long? = null,
    val language: String = "auto",
    /** 0 = automatic (from [performanceMode]). */
    val threads: Int = 0,
    val performanceMode: PerformanceMode = PerformanceMode.BALANCED,
    val quality: Quality = Quality.FAST,
    val chunkSeconds: Int = 120,
    val timestamps: Boolean = true,
    val flashAttention: Boolean = true,
    /** Vocabulary in the decoder prompt (decoder context). */
    val vocabularyPrompt: Boolean = true,
    /** Decoder logit boost towards keyword spellings. */
    val vocabularyBoost: BoostLevel = BoostLevel.LOW,
    /** Local post-processing: propose corrections. */
    val suggestCorrections: Boolean = true,
    /** Automatically apply corrections at/above [autoApplyThreshold] (exact listed alternative spellings). */
    val autoApplyCorrections: Boolean = true,
    val autoApplyThreshold: Double = 0.95,
    val preserveOriginal: Boolean = true,
    val exportFormat: ExportFormat = ExportFormat.TXT,
    val exportIncludeTimestamps: Boolean = true,
    val transcribeAfterRecording: Boolean = true,
    val allowLowMemoryLoad: Boolean = false,
    val onboardingDone: Boolean = false,
    /** Silero VAD: transcribe only speech, skip silence (whisper.cpp models). */
    val skipSilence: Boolean = true,
    /** Remove "uh/um" and stutters (original text is kept). */
    val removeFillers: Boolean = true,
    /** Start the prompt with a punctuated sentence in the recognised language. */
    val languageStarter: Boolean = true,
    /** Voice input: stop after this many ms of silence once speech started. */
    val voiceSilenceMs: Int = 1500,
    /** Recording: stop automatically after this many seconds of silence (0 = never). */
    val recordingAutoStopSec: Int = 0,
    /** Show live captions while recording. */
    val liveCaptions: Boolean = false,
    /** Pause music/videos from other apps while recording. */
    val pauseOtherAudio: Boolean = true,
    /** Shade parts the engine was unsure about. */
    val highlightUncertain: Boolean = true,
    /** Identify speakers by default when a speaker model is installed. */
    val identifySpeakers: Boolean = false,
) {
    fun effectiveThreads(cpuCount: Int = Runtime.getRuntime().availableProcessors()): Int {
        if (threads > 0) return threads.coerceIn(1, cpuCount.coerceAtLeast(1))
        return when (performanceMode) {
            PerformanceMode.BATTERY -> 2
            PerformanceMode.BALANCED -> minOf(4, cpuCount).coerceAtLeast(1)
            PerformanceMode.MAXIMUM -> minOf(8, cpuCount).coerceAtLeast(1)
        }
    }

    companion object {
        fun fromMap(m: Map<String, String>): AppSettings {
            val d = AppSettings()
            fun b(k: String, def: Boolean) = m[k]?.toBooleanStrictOrNull() ?: def
            return AppSettings(
                defaultModelId = m["defaultModelId"]?.toLongOrNull(),
                language = m["language"] ?: d.language,
                threads = m["threads"]?.toIntOrNull() ?: d.threads,
                performanceMode = m["performanceMode"]?.let { runCatching { PerformanceMode.valueOf(it) }.getOrNull() } ?: d.performanceMode,
                quality = m["quality"]?.let { runCatching { Quality.valueOf(it) }.getOrNull() } ?: d.quality,
                chunkSeconds = m["chunkSeconds"]?.toIntOrNull() ?: d.chunkSeconds,
                timestamps = b("timestamps", d.timestamps),
                flashAttention = b("flashAttention", d.flashAttention),
                vocabularyPrompt = b("vocabularyPrompt", d.vocabularyPrompt),
                vocabularyBoost = m["vocabularyBoost"]?.let { runCatching { BoostLevel.valueOf(it) }.getOrNull() } ?: d.vocabularyBoost,
                suggestCorrections = b("suggestCorrections", d.suggestCorrections),
                autoApplyCorrections = b("autoApplyCorrections", d.autoApplyCorrections),
                autoApplyThreshold = m["autoApplyThreshold"]?.toDoubleOrNull() ?: d.autoApplyThreshold,
                preserveOriginal = b("preserveOriginal", d.preserveOriginal),
                exportFormat = m["exportFormat"]?.let { runCatching { ExportFormat.valueOf(it) }.getOrNull() } ?: d.exportFormat,
                exportIncludeTimestamps = b("exportIncludeTimestamps", d.exportIncludeTimestamps),
                transcribeAfterRecording = b("transcribeAfterRecording", d.transcribeAfterRecording),
                allowLowMemoryLoad = b("allowLowMemoryLoad", d.allowLowMemoryLoad),
                onboardingDone = b("onboardingDone", d.onboardingDone),
                skipSilence = b("skipSilence", d.skipSilence),
                removeFillers = b("removeFillers", d.removeFillers),
                languageStarter = b("languageStarter", d.languageStarter),
                voiceSilenceMs = m["voiceSilenceMs"]?.toIntOrNull() ?: d.voiceSilenceMs,
                recordingAutoStopSec = m["recordingAutoStopSec"]?.toIntOrNull() ?: d.recordingAutoStopSec,
                liveCaptions = b("liveCaptions", d.liveCaptions),
                pauseOtherAudio = b("pauseOtherAudio", d.pauseOtherAudio),
                highlightUncertain = b("highlightUncertain", d.highlightUncertain),
                identifySpeakers = b("identifySpeakers", d.identifySpeakers),
            )
        }

        fun toMap(s: AppSettings): Map<String, String> = buildMap {
            s.defaultModelId?.let { put("defaultModelId", it.toString()) }
            put("language", s.language)
            put("threads", s.threads.toString())
            put("performanceMode", s.performanceMode.name)
            put("quality", s.quality.name)
            put("chunkSeconds", s.chunkSeconds.toString())
            put("timestamps", s.timestamps.toString())
            put("flashAttention", s.flashAttention.toString())
            put("vocabularyPrompt", s.vocabularyPrompt.toString())
            put("vocabularyBoost", s.vocabularyBoost.name)
            put("suggestCorrections", s.suggestCorrections.toString())
            put("autoApplyCorrections", s.autoApplyCorrections.toString())
            put("autoApplyThreshold", s.autoApplyThreshold.toString())
            put("preserveOriginal", s.preserveOriginal.toString())
            put("exportFormat", s.exportFormat.name)
            put("exportIncludeTimestamps", s.exportIncludeTimestamps.toString())
            put("transcribeAfterRecording", s.transcribeAfterRecording.toString())
            put("allowLowMemoryLoad", s.allowLowMemoryLoad.toString())
            put("onboardingDone", s.onboardingDone.toString())
            put("skipSilence", s.skipSilence.toString())
            put("removeFillers", s.removeFillers.toString())
            put("languageStarter", s.languageStarter.toString())
            put("voiceSilenceMs", s.voiceSilenceMs.toString())
            put("recordingAutoStopSec", s.recordingAutoStopSec.toString())
            put("liveCaptions", s.liveCaptions.toString())
            put("pauseOtherAudio", s.pauseOtherAudio.toString())
            put("highlightUncertain", s.highlightUncertain.toString())
            put("identifySpeakers", s.identifySpeakers.toString())
        }
    }
}

/** Settings persisted in the Room `settings` table (key/value). */
class SettingsRepository(private val dao: SettingDao) {
    private val mutex = Mutex()

    val settings: Flow<AppSettings> = dao.observeAll().map { rows -> AppSettings.fromMap(rows.associate { it.key to it.value }) }

    suspend fun current(): AppSettings = AppSettings.fromMap(dao.all().associate { it.key to it.value })

    suspend fun update(transform: (AppSettings) -> AppSettings) = mutex.withLock {
        val old = current()
        val new = transform(old)
        val oldMap = AppSettings.toMap(old)
        for ((k, v) in AppSettings.toMap(new)) if (oldMap[k] != v) dao.put(SettingEntity(k, v))
        if (new.defaultModelId == null && old.defaultModelId != null) dao.put(SettingEntity("defaultModelId", ""))
    }
}
