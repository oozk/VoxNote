package com.offlinetranscriber.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranscriber.data.JobStatus
import com.offlinetranscriber.data.TranscriptionEntity
import com.offlinetranscriber.util.Format
import com.offlinetranscriber.util.Languages
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen() {
    val app = LocalApp.current
    val c = app.appVm.container
    val scope = rememberCoroutineScope()
    val history by c.transcriptions.history.collectAsStateWithLifecycle(emptyList())
    val previews by c.transcriptions.previews.collectAsStateWithLifecycle(emptyList())
    val previewMap = previews.associate { it.id to it.preview.orEmpty() }
    var query by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<TranscriptionEntity?>(null) }
    val shown = history.filter { query.isBlank() || it.title.contains(query, true) || previewMap[it.id].orEmpty().contains(query, true) }

    Column(Modifier.fillMaxSize()) {
        AppTopBar("History")
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text("Search titles and text") })
            }
            if (history.isEmpty()) item { InfoBanner("No transcriptions yet. Record something or pick an audio file to get started.") }
            items(shown, key = { it.id }) { t ->
                SectionCard(null, Modifier.clickable {
                    app.go(if (t.status == JobStatus.COMPLETED || previewMap[t.id].orEmpty().isNotBlank()) "transcript/${t.id}" else "transcribe?job=${t.id}")
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(Format.date(t.createdAt), Format.duration(t.audioDurationMs), t.modelName,
                                (t.languageDetected ?: t.languageRequested.takeIf { it != "auto" })?.let { Languages.name(it) }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { deleting = t }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                    if (t.status != JobStatus.COMPLETED) StatusChip(statusLabel(t.status), MaterialTheme.colorScheme.tertiary)
                    previewMap[t.id]?.takeIf { it.isNotBlank() }?.let {
                        Text(it.trim(), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    deleting?.let { t ->
        ConfirmDialog("Delete \"${t.title}\"?", "The transcript and its audio file will be deleted from this device.", "Delete",
            onConfirm = { scope.launch { c.transcriptions.delete(t.id) } }, onDismiss = { deleting = null }, destructive = true)
    }
}
