package com.offlinetranscriber.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranscriber.audio.DeviceAudioCaptureService
import com.offlinetranscriber.util.Format

/** Live captions for audio playing on this phone (videos, podcasts, calls on speaker). */
@Composable
fun LiveScreen() {
    val app = LocalApp.current
    val c = app.appVm.container
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        AppTopBar("Live captions", showBack = true)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (Build.VERSION.SDK_INT < 29) {
                InfoBanner("Captioning other apps' audio needs Android 10 or newer.", warning = true)
                return@Column
            }
            val state by DeviceAudioCaptureService.state.collectAsStateWithLifecycle()
            val captioner by c.captioner.collectAsStateWithLifecycle()
            val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                if (r.resultCode == Activity.RESULT_OK && r.data != null) DeviceAudioCaptureService.start(ctx, r.resultCode, r.data!!)
            }
            val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                if (ok) projection.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            }
            InfoBanner("Captions audio that other apps play, on this device. Android asks you to allow screen capture; only the sound is used. " +
                "Some apps (many streaming services) block capture, and their sound will be silent.")
            when (val s = state) {
                is DeviceAudioCaptureService.Companion.State.Capturing -> {
                    Text("Listening to device audio · ${Format.duration(s.elapsedMs)}", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { s.level }, modifier = Modifier.fillMaxWidth())
                    BigButton("Stop and save", Icons.Filled.Stop, { DeviceAudioCaptureService.stop(ctx) })
                }
                else -> {
                    if (s is DeviceAudioCaptureService.Companion.State.Error) InfoBanner(s.message, warning = true)
                    if (s is DeviceAudioCaptureService.Companion.State.Saved && s.transcriptionId != null) {
                        SectionCard("Saved (${Format.duration(s.durationMs)})") {
                            Text("The captured audio was saved. Transcribe it for an accurate, timestamped transcript.")
                            BigButton("Transcribe it", Icons.Filled.Subtitles, {
                                DeviceAudioCaptureService.state.value = DeviceAudioCaptureService.Companion.State.Idle
                                app.go("transcribe?job=${s.transcriptionId}")
                            })
                        }
                    }
                    BigButton("Start live captions", Icons.Filled.ClosedCaption, {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                            projection.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                        else mic.launch(Manifest.permission.RECORD_AUDIO)
                    })
                }
            }
            captioner?.let { LiveCaptionsView(it) }
        }
    }
}
