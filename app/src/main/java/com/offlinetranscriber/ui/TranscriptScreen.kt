package com.offlinetranscriber.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranscriber.data.SegmentEntity
import com.offlinetranscriber.data.SuggestionEntity
import com.offlinetranscriber.data.SuggestionStatus
import com.offlinetranscriber.export.ExportFormat
import com.offlinetranscriber.export.Exporters
import com.offlinetranscriber.util.AppStorage
import com.offlinetranscriber.util.Format
import com.offlinetranscriber.util.Languages
import com.offlinetranscriber.vocab.MatchKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TranscriptScreen(id: Long) {
    val app = LocalApp.current
    val c = app.appVm.container
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by remember(id) { c.transcriptions.observe(id) }.collectAsStateWithLifecycle(null)
    val segments by remember(id) { c.transcriptions.observeSegments(id) }.collectAsStateWithLifecycle(emptyList())
    val suggestions by remember(id) { c.transcriptions.observeSuggestions(id) }.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)

    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showTimestamps by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<SegmentEntity?>(null) }
    var showReplace by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showSuggestions by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var speakerMenu by remember { mutableStateOf<SegmentEntity?>(null) }
    val listState = rememberLazyListState()
    val player = rememberPlayer(job?.audioPath)

    val pending = suggestions.filter { it.status == SuggestionStatus.PENDING }
    val auto = suggestions.filter { it.status == SuggestionStatus.AUTO_APPLIED }
    val pendingBySegment = pending.groupBy { it.segmentId }
    val t = job

    Column(Modifier.fillMaxSize()) {
        AppTopBar(t?.title ?: "Transcript", showBack = true) {
            IconButton(onClick = { showSearch = !showSearch }) { Icon(Icons.Filled.Search, "Search") }
            IconButton(onClick = {
                copyToClipboard(ctx, segments.joinToString(" ") { it.text })
                scope.launch { app.snackbar.showSnackbar("Copied to clipboard") }
            }) { Icon(Icons.Filled.ContentCopy, "Copy all") }
            IconButton(onClick = { showExport = true }) { Icon(Icons.Filled.IosShare, "Export") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Find and replace") }, onClick = { menu = false; showReplace = true })
                DropdownMenuItem(text = { Text(if (showTimestamps) "Hide timestamps" else "Show timestamps") }, onClick = { menu = false; showTimestamps = !showTimestamps })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; showRename = true })
                DropdownMenuItem(text = { Text("Transcribe again…") }, onClick = { menu = false; app.go("transcribe?job=$id") })
                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true })
            }
        }
        if (t == null) return@Column

        if (showSearch) {
            val matches = remember(query, segments) {
                if (query.isBlank()) emptyList() else segments.mapIndexedNotNull { i, s -> if (s.text.contains(query, true)) i else null }
            }
            var matchCursor by remember(query) { mutableStateOf(-1) }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search in transcript") },
                    supportingText = { if (query.isNotBlank()) Text("${matches.size} parts contain \"$query\"") })
                IconButton(enabled = matches.isNotEmpty(), onClick = {
                    matchCursor = (matchCursor + 1) % matches.size
                    scope.launch { listState.animateScrollToItem(matches[matchCursor] + 1) }
                }) { Icon(Icons.Filled.KeyboardArrowDown, "Next match") }
            }
        }

        if (t.audioPath.isNotEmpty()) PlayerBar(player)

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(buildString {
                        append(Format.date(t.createdAt)).append(" · ").append(Format.duration(t.audioDurationMs))
                        t.modelName?.let { append(" · ").append(it) }
                        append(" · ").append(Languages.name(t.languageDetected ?: t.languageRequested))
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Audio: ${File(t.audioPath).name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (t.status != "COMPLETED") InfoBanner("Status: ${statusLabel(t.status)}. ${t.error ?: ""}".trim(), Modifier.clickable { app.go("transcribe?job=$id") })
                    if (pending.isNotEmpty() || auto.isNotEmpty()) {
                        SectionCard(null, Modifier.clickable { showSuggestions = true }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Spellcheck, null)
                                Spacer(Modifier.width(8.dp))
                                Text(buildString {
                                    if (pending.isNotEmpty()) append("${pending.size} vocabulary suggestions to review")
                                    if (pending.isNotEmpty() && auto.isNotEmpty()) append(" · ")
                                    if (auto.isNotEmpty()) append("${auto.size} applied automatically")
                                }, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                TextButton(onClick = { showSuggestions = true }) { Text("Review") }
                            }
                        }
                    }
                    if (segments.isEmpty()) Text("No text yet.", style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (settings?.highlightUncertain != false && segments.any { (it.confidence ?: 1f) < UNCERTAIN }) item {
                Text("Parts shown in lighter text are ones the engine was less sure about — worth a listen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(segments, key = { _, it -> it.id }) { i, seg ->
                val active = player.positionMs in seg.startMs until seg.endMs && player.playing
                if (seg.speaker != null && seg.speaker != segments.getOrNull(i - 1)?.speaker) {
                    SpeakerLabel(t.speakerName(seg.speaker) ?: "", seg.speaker) { speakerMenu = seg }
                }
                SegmentRow(seg, query, pendingBySegment[seg.id].orEmpty(), showTimestamps && (settings?.timestamps != false), active,
                    uncertain = settings?.highlightUncertain != false && (seg.confidence ?: 1f) < UNCERTAIN,
                    onSeek = { player.seekAndPlay(seg.startMs) }, onEdit = { editing = seg })
                HorizontalDivider()
            }
        }
    }

    speakerMenu?.let { seg ->
        if (t != null) SpeakerDialog(t, seg, segments.mapNotNull { it.speaker }.distinct().sorted(),
            onRename = { sp, name -> scope.launch { c.transcriptions.renameSpeaker(id, sp, name) } },
            onMerge = { from, to -> scope.launch { c.transcriptions.mergeSpeakers(id, from, to) } },
            onAssign = { sp -> scope.launch { c.transcriptions.setSegmentSpeaker(seg.id, sp) } },
            onDismiss = { speakerMenu = null })
    }

    editing?.let { seg ->
        EditSegmentDialog(seg, onDismiss = { editing = null }, onSave = { txt ->
            scope.launch { c.transcriptions.editSegment(seg.id, txt) }; editing = null
        }, onRestore = { scope.launch { c.transcriptions.restoreOriginal(seg.id) }; editing = null })
    }
    if (showReplace) FindReplaceDialog(query, onDismiss = { showReplace = false }) { f, r, mc, ww ->
        scope.launch {
            val n = c.transcriptions.replaceAll(id, f, r, mc, ww)
            app.snackbar.showSnackbar(if (n == 0) "No matches for \"$f\"" else "Replaced $n occurrence(s)")
        }
        showReplace = false
    }
    if (showSuggestions) SuggestionsDialog(pending, auto, segments,
        onAccept = { s -> scope.launch { if (!c.transcriptions.acceptSuggestion(s.id)) app.snackbar.showSnackbar("That text was edited; suggestion no longer applies") } },
        onReject = { s -> scope.launch { c.transcriptions.rejectSuggestion(s.id) } },
        onUndo = { s -> scope.launch { c.transcriptions.undoSuggestion(s.id) } },
        onAcceptAll = { scope.launch { val n = c.transcriptions.acceptAll(id); app.snackbar.showSnackbar("Accepted $n suggestions") } },
        onDismiss = { showSuggestions = false })
    if (showExport && t != null) ExportDialog(id, t.title, settings?.exportFormat ?: ExportFormat.TXT, onDismiss = { showExport = false })
    if (showRename && t != null) {
        var name by remember { mutableStateOf(t.title) }
        AlertDialog(onDismissRequest = { showRename = false }, title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { c.transcriptions.rename(id, name) }; showRename = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } })
    }
    if (confirmDelete) ConfirmDialog("Delete transcription?", "The transcript and its audio file will be removed from this device.", "Delete",
        onConfirm = { scope.launch { c.transcriptions.delete(id); app.nav.popBackStack() } }, onDismiss = { confirmDelete = false }, destructive = true)
}

@Composable
private fun SegmentRow(seg: SegmentEntity, query: String, pending: List<SuggestionEntity>, showTime: Boolean, active: Boolean,
                       uncertain: Boolean, onSeek: () -> Unit, onEdit: () -> Unit) {
    val hl = MaterialTheme.colorScheme.tertiaryContainer
    val sugColor = MaterialTheme.colorScheme.primary
    val text: AnnotatedString = remember(seg.text, query, pending, hl) {
        buildAnnotatedString {
            append(seg.text)
            if (query.isNotBlank()) {
                var i = seg.text.indexOf(query, ignoreCase = true)
                while (i >= 0) { addStyle(SpanStyle(background = hl), i, i + query.length); i = seg.text.indexOf(query, i + query.length, true) }
            }
            for (p in pending) {
                val at = com.offlinetranscriber.vocab.CorrectionEngine.locate(seg.text, p.original, p.startOffset)
                if (at >= 0) addStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = sugColor), at, at + p.original.length)
            }
        }
    }
    Row(Modifier.fillMaxWidth().background(if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else androidx.compose.ui.graphics.Color.Transparent)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        if (showTime) {
            Text(Exporters.clock(seg.startMs), Modifier.clickable(onClick = onSeek).padding(end = 10.dp, top = 2.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        SelectionContainer(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge,
                color = if (uncertain) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f) else androidx.compose.ui.graphics.Color.Unspecified)
        }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit this part", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private const val UNCERTAIN = 0.6f

private val SpeakerColors = listOf(0xFF1E88E5, 0xFFD81B60, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA, 0xFF00897B, 0xFF6D4C41, 0xFF3949AB)

@Composable
private fun SpeakerLabel(name: String, speaker: Int, onClick: () -> Unit) {
    val color = androidx.compose.ui.graphics.Color(SpeakerColors[(speaker - 1).mod(SpeakerColors.size)])
    TextButton(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) {
        Text(name, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SpeakerDialog(
    t: com.offlinetranscriber.data.TranscriptionEntity, seg: SegmentEntity, speakers: List<Int>,
    onRename: (Int, String) -> Unit, onMerge: (Int, Int) -> Unit, onAssign: (Int?) -> Unit, onDismiss: () -> Unit,
) {
    val sp = seg.speaker ?: return
    var name by remember { mutableStateOf(t.speakerNameMap()[sp] ?: "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t.speakerName(sp) ?: "Speaker") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name for everyone's parts of ${t.speakerName(sp)}") }, singleLine = true)
                val others = speakers.filter { it != sp }
                if (others.isNotEmpty()) {
                    Text("Same person as… (merge)", style = MaterialTheme.typography.labelLarge)
                    others.forEach { o -> TextButton(onClick = { onMerge(sp, o); onDismiss() }) { Text("Merge into ${t.speakerName(o)}") } }
                    Text("Only this part was said by…", style = MaterialTheme.typography.labelLarge)
                    others.forEach { o -> TextButton(onClick = { onAssign(o); onDismiss() }) { Text(t.speakerName(o) ?: "") } }
                }
                TextButton(onClick = { onAssign((speakers.maxOrNull() ?: 0) + 1); onDismiss() }) { Text("This part is a new speaker") }
                Text("Speaker labels come from the on-device speaker model and can be wrong.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onRename(sp, name); onDismiss() }) { Text("Save name") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun EditSegmentDialog(seg: SegmentEntity, onDismiss: () -> Unit, onSave: (String) -> Unit, onRestore: () -> Unit) {
    var text by remember(seg.id) { mutableStateOf(seg.text) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit ${Exporters.clock(seg.startMs)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp))
                if (seg.originalText != null && seg.originalText != seg.text) {
                    Text("Original: ${seg.originalText}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onRestore) { Text("Restore original text") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun FindReplaceDialog(initial: String, onDismiss: () -> Unit, onReplace: (String, String, Boolean, Boolean) -> Unit) {
    var find by remember { mutableStateOf(initial) }
    var replace by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Filled.FindReplace, null) }, title = { Text("Find and replace") },
        text = {
            Column {
                OutlinedTextField(find, { find = it }, label = { Text("Find") }, singleLine = true)
                OutlinedTextField(replace, { replace = it }, label = { Text("Replace with") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(matchCase, { matchCase = it }); Text("Match case") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(wholeWord, { wholeWord = it }); Text("Whole words only") }
            }
        },
        confirmButton = { TextButton(enabled = find.isNotEmpty(), onClick = { onReplace(find, replace, matchCase, wholeWord) }) { Text("Replace all") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun SuggestionsDialog(
    pending: List<SuggestionEntity>, auto: List<SuggestionEntity>, segments: List<SegmentEntity>,
    onAccept: (SuggestionEntity) -> Unit, onReject: (SuggestionEntity) -> Unit, onUndo: (SuggestionEntity) -> Unit,
    onAcceptAll: () -> Unit, onDismiss: () -> Unit,
) {
    val segById = segments.associateBy { it.id }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Vocabulary suggestions") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("These come from comparing the recognised text with My Vocabulary on this device (text post-processing). " +
                        "Nothing is changed unless you accept it. Your original text is kept.", style = MaterialTheme.typography.bodySmall)
                }
                items(pending, key = { it.id }) { s ->
                    Column {
                        Text("\"${s.original}\"  →  \"${s.replacement}\"", fontWeight = FontWeight.SemiBold)
                        Text("${runCatching { MatchKind.valueOf(s.kind).label }.getOrDefault(s.kind)} · ${(s.confidence * 100).toInt()} % confident",
                            style = MaterialTheme.typography.bodySmall)
                        segById[s.segmentId]?.let { Text("…${it.text.take(120)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Row {
                            TextButton(onClick = { onAccept(s) }) { Text("Accept") }
                            TextButton(onClick = { onReject(s) }) { Text("Reject") }
                        }
                    }
                }
                if (auto.isNotEmpty()) {
                    item { Text("Applied automatically (listed spellings)", style = MaterialTheme.typography.titleSmall) }
                    items(auto, key = { it.id }) { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("\"${s.original}\" → \"${s.replacement}\"", Modifier.weight(1f))
                            TextButton(onClick = { onUndo(s) }) { Text("Undo") }
                        }
                    }
                }
            }
        },
        confirmButton = { if (pending.isNotEmpty()) TextButton(onClick = onAcceptAll) { Text("Accept all") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun ExportDialog(id: Long, title: String, defaultFormat: ExportFormat, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val c = app.appVm.container
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(defaultFormat) }
    val fileName = AppStorage.sanitize("$title.${format.extension}")

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) { Exporters.exportToBytes(c.transcriptions.exportDocument(id), format) }
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }
            }.onSuccess { app.snackbar.showSnackbar("Saved $fileName") }.onFailure { app.snackbar.showSnackbar("Export failed: ${it.message}") }
            onDismiss()
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Export") },
        text = {
            Column {
                ExportFormat.entries.forEach { f ->
                    Row(Modifier.fillMaxWidth().selectable(f == format) { format = f }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(f == format, { format = f }); Text(f.label)
                    }
                }
                Text("Saved files stay on your device. \"Share\" hands the file to an app you choose.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { saver.launch(fileName) }) { Text("Save to device") } },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    runCatching {
                        val file = withContext(Dispatchers.IO) {
                            val f = File(c.storage.exportDir, fileName)
                            f.outputStream().use { Exporters.export(c.transcriptions.exportDocument(id), format, it) }
                            f
                        }
                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(format.mimeType)
                            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share transcript"))
                    }.onFailure { app.snackbar.showSnackbar("Share failed: ${it.message}") }
                    onDismiss()
                }
            }) { Text("Share…") }
        })
}

private fun copyToClipboard(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Transcript", text))
}

// ---------------- audio playback (local file) ----------------

class PlayerState {
    var player: MediaPlayer? = null
    var playing by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var dragging by mutableFloatStateOf(-1f)

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
    }

    fun seekAndPlay(ms: Long) {
        val p = player ?: return
        p.seekTo(ms.toInt())
        positionMs = ms
        if (!p.isPlaying) { p.start(); playing = true }
    }
}

@Composable
private fun rememberPlayer(path: String?): PlayerState {
    val state = remember { PlayerState() }
    DisposableEffect(path) {
        val p = path?.let { f ->
            runCatching { MediaPlayer().apply { setDataSource(f); prepare() } }.getOrNull()
        }
        state.player = p
        state.durationMs = p?.duration?.toLong() ?: 0L
        p?.setOnCompletionListener { state.playing = false }
        onDispose { p?.release(); state.player = null; state.playing = false }
    }
    LaunchedEffect(path) {
        while (true) {
            state.player?.let { if (state.playing) state.positionMs = it.currentPosition.toLong() }
            delay(250)
        }
    }
    return state
}

@Composable
private fun PlayerBar(p: PlayerState) {
    if (p.player == null) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { p.toggle() }) { Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (p.playing) "Pause" else "Play") }
        Slider(
            value = if (p.dragging >= 0) p.dragging else if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else 0f,
            onValueChange = { p.dragging = it },
            onValueChangeFinished = { p.player?.seekTo((p.dragging * p.durationMs).toInt()); p.positionMs = (p.dragging * p.durationMs).toLong(); p.dragging = -1f },
            modifier = Modifier.weight(1f),
        )
        Text("${Format.duration(p.positionMs)} / ${Format.duration(p.durationMs)}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
    }
}
