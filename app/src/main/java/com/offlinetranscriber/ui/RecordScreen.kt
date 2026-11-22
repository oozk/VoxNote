package com.offlinetranscriber.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranscriber.audio.RecorderState
import com.offlinetranscriber.audio.RecordingService
import com.offlinetranscriber.util.Format
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordScreen(autoStart: Boolean = false) {
    val app = LocalApp.current
    val c = app.appVm.container
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by c.recorder.state.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)
    var name by rememberSaveable { mutableStateOf(defaultRecordingName()) }
    var permissionDenied by remember { mutableStateOf(false) }
    val transcribeAfter = settings?.transcribeAfterRecording ?: true

    fun startRecording() = RecordingService.start(ctx, name.trim().ifEmpty { defaultRecordingName() }, transcribeAfter)

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) { permissionDenied = false; startRecording() } else permissionDenied = true
    }

    fun onRecordPressed() {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) startRecording() else {
            val req = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) req.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.launch(req.toTypedArray())
        }
    }

    val captioner by c.captioner.collectAsStateWithLifecycle()
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoStart) {
        if (autoStart && !autoStarted && state !is RecorderState.Recording) { autoStarted = true; onRecordPressed() }
    }

    // after "record and transcribe", jump to the progress screen
    LaunchedEffect(state) {
        val s = state
        if (s is RecorderState.Saved && s.transcribeNow && s.transcriptionId != null) {
            c.recorder.reset()
            name = defaultRecordingName()
            app.go("transcribe?job=${s.transcriptionId}")
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Record")
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                is RecorderState.Recording -> {
                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                    Text(Format.duration(s.elapsedMs), fontSize = 56.sp, fontWeight = FontWeight.Light)
                    Text(if (s.paused) "Paused" else "Recording…", color = if (s.paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    LevelMeter(if (s.paused) 0f else s.level)
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledIconButton(
                            onClick = { if (s.paused) RecordingService.resume(ctx) else RecordingService.pause(ctx) },
                            modifier = Modifier.size(80.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                        ) { Icon(if (s.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (s.paused) "Resume" else "Pause", Modifier.size(40.dp)) }
                        FilledIconButton(
                            onClick = { RecordingService.stop(ctx) },
                            modifier = Modifier.size(104.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Icon(Icons.Filled.Stop, "Stop", Modifier.size(56.dp)) }
                    }
                    Text(if (transcribeAfter) "Transcription starts automatically when you stop." else "The recording will be saved. You can transcribe it later.",
                        style = MaterialTheme.typography.bodySmall)
                    captioner?.let { cap -> LiveCaptionsView(cap) }
                }
                is RecorderState.Saved -> {
                    SectionCard("Recording saved") {
                        Text("${s.name} · ${Format.duration(s.durationMs)}")
                        Text("Saved on this device: ${s.file.name}", style = MaterialTheme.typography.bodySmall)
                        if (s.transcriptionId != null) {
                            BigButton("Transcribe now", Icons.Filled.Subtitles, {
                                c.recorder.reset(); app.go("transcribe?job=${s.transcriptionId}")
                            })
                        }
                        BigButton("Record another", Icons.Filled.Mic, { c.recorder.reset(); name = defaultRecordingName() }, secondary = true)
                    }
                }
                else -> {
                    if (state is RecorderState.Error) InfoBanner((state as RecorderState.Error).message, warning = true)
                    if (permissionDenied) InfoBanner("Microphone permission is needed to record. You can allow it in Android Settings › Apps › VoxNote › Permissions.", warning = true)
                    OutlinedTextField(name, { name = it }, label = { Text("Recording name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    LargeFloatingActionButton(
                        onClick = { scope.launch { onRecordPressed() } },
                        modifier = Modifier.size(140.dp),
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Mic, null, Modifier.size(56.dp))
                            Text("RECORD", fontWeight = FontWeight.Bold)
                        }
                    }
                    SectionCard {
                        LabeledSwitch("Transcribe when I stop", "Off = record now, transcribe later from the Transcribe screen.", transcribeAfter, { v ->
                            scope.launch { c.settings.update { it.copy(transcribeAfterRecording = v) } }
                        })
                        LabeledSwitch("Live captions", "Show text while you record. Uses more battery; works best with small models.",
                            settings?.liveCaptions == true, { v -> scope.launch { c.settings.update { it.copy(liveCaptions = v) } } })
                        val stops = listOf(0, 5, 10, 30, 60)
                        Chooser("Stop automatically after silence", stops, settings?.recordingAutoStopSec ?: 0,
                            { if (it == 0) "Never" else "$it seconds of silence" }, { v -> scope.launch { c.settings.update { it.copy(recordingAutoStopSec = v) } } })
                    }
                    Text("Recordings are saved as WAV files inside the app and never uploaded.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LevelMeter(level: Float) {
    Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(18.dp).clip(RoundedCornerShape(9.dp))
            .background(if (level > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary))
    }
}

private fun defaultRecordingName() = "Recording " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())

/** Live caption text (finished lines plus the replaceable line in progress). */
@Composable
fun LiveCaptionsView(cap: com.offlinetranscriber.transcription.LiveCaptioner) {
    val captions by cap.captions.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    LaunchedEffect(captions.lines.size, captions.partial) { scroll.animateScrollTo(scroll.maxValue) }
    SectionCard("Live captions") {
        Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(scroll)) {
            if (captions.lines.isEmpty() && captions.partial.isBlank()) {
                Text("Captions appear after the first sentence…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            captions.lines.takeLast(30).forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (captions.partial.isNotBlank()) Text(captions.partial, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}
