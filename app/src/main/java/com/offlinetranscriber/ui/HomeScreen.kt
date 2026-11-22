package com.offlinetranscriber.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranscriber.util.Format

@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val c = app.appVm.container
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)
    val models by c.models.models.collectAsStateWithLifecycle(emptyList())
    val keywordCount by c.vocabulary.count.collectAsStateWithLifecycle(0)
    val run by c.runner.state.collectAsStateWithLifecycle()
    val history by c.transcriptions.history.collectAsStateWithLifecycle(emptyList())
    val defaultModel = models.firstOrNull { it.id == settings?.defaultModelId } ?: models.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Offline Transcriber")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoBanner("Works fully offline. Audio, text and keywords never leave this device — this app has no internet permission.", icon = Icons.Outlined.Lock)

            if (models.isEmpty()) {
                SectionCard("Step 1: add a speech model") {
                    Text("Transcription needs a Whisper model file (ggml-*.bin) on this device. See the Models screen for recommended files and how to get them from Hugging Face.")
                    BigButton("Add a model", Icons.Filled.Memory, { app.goTop("models") })
                }
            } else {
                SectionCard {
                    Text("Model: ${defaultModel?.displayName ?: "—"}", style = MaterialTheme.typography.bodyLarge)
                    Text("My Vocabulary: $keywordCount keywords", style = MaterialTheme.typography.bodyMedium)
                }
            }

            run?.let { r ->
                SectionCard("Working on: ${r.title}", Modifier.clickable { app.goTop("transcribe") }) {
                    Text(r.phase.label + (r.etaMs?.let { " · ${Format.eta(it)} left" } ?: ""))
                    LinearProgressIndicator(progress = { r.fraction }, modifier = Modifier.fillMaxWidth())
                }
            }

            BigButton("Record", Icons.Filled.Mic, { app.goTop("record") })
            BigButton("Transcribe an audio file", Icons.Filled.AudioFile, { app.goTop("transcribe") })
            BigButton("My Vocabulary", Icons.Filled.Spellcheck, { app.goTop("vocabulary") }, secondary = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("Models", Icons.Filled.Memory, { app.goTop("models") }, Modifier.weight(1f), secondary = true)
                BigButton("Settings", Icons.Filled.Settings, { app.goTop("settings") }, Modifier.weight(1f), secondary = true)
            }

            if (history.isNotEmpty()) {
                SectionCard("Recent") {
                    history.take(4).forEach { t ->
                        Column(Modifier.fillMaxWidth().clickable { app.go("transcript/${t.id}") }.padding(vertical = 6.dp)) {
                            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${Format.date(t.createdAt)} · ${Format.duration(t.audioDurationMs)} · ${statusLabel(t.status)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    BigButton("All transcriptions", Icons.Filled.History, { app.goTop("history") }, secondary = true)
                }
            }
        }
    }
}

fun statusLabel(status: String): String = when (status) {
    "COMPLETED" -> "Done"
    "RUNNING" -> "Transcribing"
    "PREPARING" -> "Preparing"
    "QUEUED" -> "Waiting"
    "INTERRUPTED" -> "Interrupted"
    "FAILED" -> "Failed"
    "CANCELLED" -> "Cancelled"
    "NOT_STARTED" -> "Not transcribed yet"
    else -> status
}
