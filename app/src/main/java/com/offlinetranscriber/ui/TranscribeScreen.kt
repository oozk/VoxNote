package com.offlinetranscriber.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranscriber.data.JobStatus
import com.offlinetranscriber.data.ModelEntity
import com.offlinetranscriber.data.TranscriptionEntity
import com.offlinetranscriber.transcription.RunPhase
import com.offlinetranscriber.transcription.RunState
import com.offlinetranscriber.transcription.TranscriptionService
import com.offlinetranscriber.util.Format
import com.offlinetranscriber.util.Languages
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun TranscribeScreen(jobId: Long?) {
    val app = LocalApp.current
    val c = app.appVm.container
    val run by c.runner.state.collectAsStateWithLifecycle()
    val history by c.transcriptions.history.collectAsStateWithLifecycle(emptyList())
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { app.appVm.importAudio(it) } }
    val selectedId = jobId ?: run?.jobId

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Transcribe", showBack = jobId != null)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selectedId != null) {
                JobPanel(selectedId, run)
            } else {
                BigButton("Choose an audio file", Icons.Filled.AudioFile, {
                    picker.launch(arrayOf("audio/*", "video/*", "application/ogg"))
                })
                Text("WAV, MP3, M4A/AAC, FLAC, OGG, OPUS and video sound tracks are decoded on this device. You can also use \"Share\" from another app.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val pending = history.filter { it.status in JobStatus.RESUMABLE || it.status == JobStatus.QUEUED }
                if (pending.isNotEmpty()) SectionCard("Waiting to be transcribed") {
                    pending.forEach { t -> JobRow(t) { app.go("transcribe?job=${t.id}") } }
                }
            }
        }
    }
}

@Composable
private fun JobRow(t: TranscriptionEntity, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${Format.duration(t.audioDurationMs)} · ${statusLabel(t.status)}" + if (t.progress > 0f && t.status != JobStatus.COMPLETED) " · ${(t.progress * 100).toInt()} %" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobPanel(id: Long, run: RunState?) {
    val app = LocalApp.current
    val c = app.appVm.container
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by remember(id) { c.transcriptions.observe(id) }.collectAsStateWithLifecycle(null)
    val models by c.models.models.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)
    val categories by c.vocabulary.categoryCounts.collectAsStateWithLifecycle(emptyList())
    val t = job ?: return
    val isRunning = run?.jobId == id
    val usable = models.filter { it.compatible && it.verified >= 0 }

    var modelId by remember(id) { mutableStateOf<Long?>(null) }
    var language by remember(id) { mutableStateOf<String?>(null) }
    var chosenCats by remember(id) { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(t.id, settings, usable.size) {
        if (modelId == null) modelId = t.modelId?.takeIf { m -> usable.any { it.id == m } } ?: settings?.defaultModelId?.takeIf { m -> usable.any { it.id == m } } ?: usable.firstOrNull()?.id
        if (language == null) language = t.languageRequested.takeIf { it != "auto" } ?: settings?.language ?: "auto"
        if (chosenCats == null) chosenCats = t.vocabularyCategories.lines().filter { it.isNotBlank() }.toSet()
    }
    val model: ModelEntity? = usable.firstOrNull { it.id == modelId }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun start(restart: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        scope.launch {
            val lang = if (model?.multilingual == false) "en" else (language ?: "auto")
            c.transcriptions.queue(id, modelId, lang, chosenCats?.toList().orEmpty(), restart)
            TranscriptionService.start(ctx)
        }
    }

    SectionCard {
        Text(t.title, style = MaterialTheme.typography.titleLarge)
        Text("Audio: ${java.io.File(t.audioPath).name} · ${Format.duration(t.audioDurationMs)}", style = MaterialTheme.typography.bodySmall)
    }

    when {
        isRunning && run != null -> ProgressPanel(run) { TranscriptionService.cancel(ctx) }
        t.status == JobStatus.QUEUED -> {
            InfoBanner("Waiting for the current transcription to finish…")
            BigButton("Remove from queue", Icons.Filled.Close, {
                scope.launch { c.transcriptions.update(t.copy(status = JobStatus.CANCELLED)) }
            }, secondary = true)
        }
        else -> {
            if (t.status == JobStatus.COMPLETED) {
                InfoBanner("Transcription finished" + (t.languageDetected?.let { " · language: ${Languages.name(it)}" } ?: "") + ".")
                BigButton("Open transcript", Icons.Filled.Description, { app.go("transcript/${t.id}") })
            }
            t.error?.takeIf { t.status != JobStatus.COMPLETED }?.let { InfoBanner(it, warning = t.status == JobStatus.FAILED) }

            if (usable.isEmpty()) {
                InfoBanner("No compatible model is installed yet.", warning = true)
                BigButton("Go to Models", null, { app.goTop("models") })
                return
            }
            SectionCard("Settings for this transcription") {
                Chooser("Model", usable, model, { "${it.displayName} (${Format.bytes(it.sizeBytes)})" }, { modelId = it.id })
                if (model?.multilingual == false) {
                    Text("This model understands English only.", style = MaterialTheme.typography.bodySmall)
                } else {
                    val langs = listOf("auto" to "Automatic detection") + com.offlinetranscriber.util.Languages.ALL
                    Chooser("Language", langs, langs.firstOrNull { it.first == language }, { it.second }, { language = it.first })
                }
                Text("Vocabulary to use", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val sel = chosenCats.orEmpty()
                    FilterChip(sel.isEmpty(), { chosenCats = emptySet() }, label = { Text("All (${categories.sumOf { it.count }})") })
                    categories.forEach { cc ->
                        FilterChip(cc.category in sel, {
                            chosenCats = if (cc.category in sel) sel - cc.category else sel + cc.category
                        }, label = { Text("${cc.category} (${cc.count})") })
                    }
                }
            }
            val resumable = t.status in setOf(JobStatus.INTERRUPTED, JobStatus.CANCELLED, JobStatus.FAILED) && t.chunkPlan != null && t.nextChunk > 0
            if (resumable) {
                BigButton("Resume (${(t.progress * 100).toInt()} % done)", Icons.Filled.PlayArrow, { start(false) })
                BigButton("Start over", Icons.Filled.Refresh, { start(true) }, secondary = true)
            } else if (t.status == JobStatus.COMPLETED) {
                BigButton("Transcribe again", Icons.Filled.Refresh, { start(true) }, secondary = true)
            } else {
                BigButton("START TRANSCRIPTION", Icons.Filled.PlayArrow, { start(false) }, Modifier.heightIn(min = 68.dp))
            }
            Text("Runs on this phone's processor. Long recordings are processed in parts; you can leave the app while it works.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProgressPanel(r: RunState, onCancel: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    SectionCard(r.phase.label + "…") {
        LinearProgressIndicator(progress = { r.fraction }, modifier = Modifier.fillMaxWidth())
        Text(buildString {
            append("${(r.fraction * 100).toInt()} %")
            if (r.phase == RunPhase.TRANSCRIBING && r.chunkCount > 1) append(" · part ${r.chunkIndex + 1} of ${r.chunkCount}")
            r.etaMs?.let { append(" · ${Format.eta(it)} left") }
        })
        r.modelName?.let { Text("Model: $it", style = MaterialTheme.typography.bodySmall) }
        r.language?.let { Text("Language: ${Languages.name(it)}", style = MaterialTheme.typography.bodySmall) }
        r.vocabularyNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (r.liveText.isNotBlank()) SectionCard("Recognised so far") {
        Text(r.liveText, style = MaterialTheme.typography.bodyLarge)
    }
    BigButton("Cancel", Icons.Filled.Close, { confirm = true }, secondary = true)
    if (confirm) ConfirmDialog("Cancel transcription?", "Parts that are already finished are kept. You can resume later.", "Cancel transcription",
        onConfirm = onCancel, onDismiss = { confirm = false }, destructive = true)
}
