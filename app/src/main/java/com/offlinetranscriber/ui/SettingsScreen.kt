package com.offlinetranscriber.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranscriber.BuildConfig
import com.offlinetranscriber.data.AppSettings
import com.offlinetranscriber.data.BoostLevel
import com.offlinetranscriber.data.PerformanceMode
import com.offlinetranscriber.data.Quality
import com.offlinetranscriber.export.ExportFormat
import com.offlinetranscriber.util.DeviceInfo
import com.offlinetranscriber.util.Languages
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    val c = app.appVm.container
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsStateWithLifecycle(null)
    val models by c.models.models.collectAsStateWithLifecycle(emptyList())
    var confirmWipe by remember { mutableStateOf(false) }
    val st = s ?: return
    fun set(f: (AppSettings) -> AppSettings) { scope.launch { c.settings.update(f) } }
    val cpu = DeviceInfo.cpuCount()

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Settings")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("Transcription") {
                Chooser("Default model", models.filter { it.compatible && it.role == "asr" }, models.firstOrNull { it.id == st.defaultModelId }, { it.displayName },
                    { m -> set { it.copy(defaultModelId = m.id) } })
                val langs = listOf("auto" to "Automatic detection") + Languages.ALL
                Chooser("Default language", langs, langs.firstOrNull { it.first == st.language }, { it.second }, { l -> set { it.copy(language = l.first) } })
                Chooser("Processing quality", Quality.entries, st.quality, { "${it.label} — ${it.description}" }, { q -> set { it.copy(quality = q) } })
                LabeledSwitch("Timestamps", "Show and export the time of each part.", st.timestamps, { v -> set { it.copy(timestamps = v) } })
                LabeledSwitch("Skip silence (Silero VAD)", "Transcribe only speech. Faster, and stops the model inventing text during long pauses. whisper.cpp models.",
                    st.skipSilence, { v -> set { it.copy(skipSilence = v) } })
                LabeledSwitch("Remove filler words", "Removes \"uh\", \"um\" and stutters like \"I I I\". The original text is kept.",
                    st.removeFillers, { v -> set { it.copy(removeFillers = v) } })
                LabeledSwitch("Language punctuation hint", "Starts the prompt with a punctuated sentence in the recognised language for better punctuation.",
                    st.languageStarter, { v -> set { it.copy(languageStarter = v) } })
                LabeledSwitch("Shade uncertain parts", "Lighter text where the engine was less confident.", st.highlightUncertain,
                    { v -> set { it.copy(highlightUncertain = v) } })
                LabeledSwitch("Identify speakers by default", "Needs a speaker model (Models screen).", st.identifySpeakers,
                    { v -> set { it.copy(identifySpeakers = v) } })
                Text("Audio part length: ${st.chunkSeconds} s", style = MaterialTheme.typography.bodyLarge)
                Slider(st.chunkSeconds.toFloat(), { v -> set { it.copy(chunkSeconds = (v / 30).toInt() * 30) } }, valueRange = 30f..600f, steps = 18)
                Text("Long recordings are processed in parts of this length (cut at pauses). Shorter parts use less memory and save progress more often.",
                    style = MaterialTheme.typography.bodySmall)
            }
            SectionCard("Performance") {
                Chooser("CPU mode", PerformanceMode.entries, st.performanceMode, { "${it.label} — ${it.description}" }, { m -> set { it.copy(performanceMode = m, threads = 0) } })
                Text("CPU threads: " + if (st.threads == 0) "automatic (${st.effectiveThreads()})" else "${st.threads}", style = MaterialTheme.typography.bodyLarge)
                Slider(st.threads.toFloat(), { v -> set { it.copy(threads = v.toInt()) } }, valueRange = 0f..cpu.toFloat(), steps = (cpu - 1).coerceAtLeast(0))
                Text("0 = automatic. This device has $cpu CPU cores. More threads are not always faster on phones.", style = MaterialTheme.typography.bodySmall)
                LabeledSwitch("Flash attention", "Faster and uses less memory on most devices.", st.flashAttention, { v -> set { it.copy(flashAttention = v) } })
                LabeledSwitch("Allow loading with low memory", "Try to load a model even if free memory looks too low. Android may close the app.",
                    st.allowLowMemoryLoad, { v -> set { it.copy(allowLowMemoryLoad = v) } })
                Text("Inference runs on the CPU. GPU (Vulkan) and NNAPI acceleration are not used because they are not reliable for Whisper on Android phones.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard("Vocabulary assistance") {
                LabeledSwitch("Use vocabulary as context", "Shows your keywords to the decoder so it spells them correctly. This is a spelling hint, not acoustic biasing.",
                    st.vocabularyPrompt, { v -> set { it.copy(vocabularyPrompt = v) } })
                Chooser("Keyword boost while decoding", BoostLevel.entries, st.vocabularyBoost, { it.label }, { b -> set { it.copy(vocabularyBoost = b) } })
                Text("Makes the decoder more likely to finish a keyword it has started. High values may insert keywords that were not said.",
                    style = MaterialTheme.typography.bodySmall)
                LabeledSwitch("Suggest corrections", "After transcription, compare the text with your vocabulary and suggest fixes (local text post-processing).",
                    st.suggestCorrections, { v -> set { it.copy(suggestCorrections = v) } })
                LabeledSwitch("Automatic corrections", "Automatically apply only exact matches of spellings you listed. Everything else waits for your approval.",
                    st.autoApplyCorrections, { v -> set { it.copy(autoApplyCorrections = v) } }, enabled = st.suggestCorrections)
                LabeledSwitch("Preserve original transcription", "Keep the engine's original text so edits and corrections can be undone.",
                    st.preserveOriginal, { v -> set { it.copy(preserveOriginal = v) } })
            }
            SectionCard("Voice input in other apps") {
                Chooser("Stop listening after a pause of", listOf(800, 1200, 1500, 2000, 3000), st.voiceSilenceMs,
                    { "${it / 1000.0} seconds" }, { v -> set { it.copy(voiceSilenceMs = v) } })
                Text("See Home › Voice input for how to use VoxNote from your keyboard.", style = MaterialTheme.typography.bodySmall)
            }
            SectionCard("Recording & export") {
                LabeledSwitch("Transcribe after recording", null, st.transcribeAfterRecording, { v -> set { it.copy(transcribeAfterRecording = v) } })
                LabeledSwitch("Live captions while recording", "Uses more battery.", st.liveCaptions, { v -> set { it.copy(liveCaptions = v) } })
                LabeledSwitch("Pause other audio while recording", "Music and videos pause during recording and dictation.", st.pauseOtherAudio,
                    { v -> set { it.copy(pauseOtherAudio = v) } })
                Chooser("Stop recording after silence", listOf(0, 5, 10, 30, 60), st.recordingAutoStopSec,
                    { if (it == 0) "Never" else "$it seconds" }, { v -> set { it.copy(recordingAutoStopSec = v) } })
                Chooser("Preferred export format", ExportFormat.entries, st.exportFormat, { it.label }, { f -> set { it.copy(exportFormat = f) } })
            }
            val modes by c.db.modes().observeAll().collectAsStateWithLifecycle(emptyList())
            if (modes.isNotEmpty()) SectionCard("Saved modes") {
                modes.forEach { m ->
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(m.name, Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = { scope.launch { c.db.modes().delete(m.id) } }) { Text("Delete") }
                    }
                }
            }
            SectionCard("Privacy") {
                InfoBanner("This app has no internet permission. Recording, transcription, vocabulary, editing, export and history all work in airplane mode. " +
                    "No analytics, no ads, no accounts, no cloud backup.", icon = Icons.Outlined.Lock)
                BigButton("Delete all app data", Icons.Filled.DeleteForever, { confirmWipe = true }, secondary = true)
            }
            Text("VoxNote ${BuildConfig.VERSION_NAME} · ${c.engines.systemInfo()} · ${c.engines.secondEngineInfo()}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmWipe) ConfirmDialog("Delete all app data?",
        "All recordings, transcripts, models, keywords and settings will be permanently deleted from this device.", "Delete everything",
        onConfirm = { scope.launch { c.deleteAllData(); app.snackbar.showSnackbar("All app data deleted") } }, onDismiss = { confirmWipe = false }, destructive = true)
}
