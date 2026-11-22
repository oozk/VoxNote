package com.offlinetranscriber.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.offlinetranscriber.OfflineTranscriberApp
import com.offlinetranscriber.ui.theme.OfflineTranscriberTheme

/**
 * Compact voice-input panel opened by keyboards (e.g. SwiftKey), website voice search and other
 * apps through RecognizerIntent.ACTION_RECOGNIZE_SPEECH. Everything runs on the device.
 */
class RecognizeActivity : ComponentActivity() {
    private var session: VoiceInputSession? = null
    private var status by mutableStateOf("Starting…")
    private var level by mutableFloatStateOf(0f)
    private var working by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin() else { error = "VoxNote needs microphone access for voice input."; status = "" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        setContent {
            OfflineTranscriberTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        tonalElevation = 6.dp) {
                        Column(Modifier.padding(24.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(error ?: status, style = MaterialTheme.typography.titleMedium,
                                color = if (error != null) MaterialTheme.colorScheme.error else Color.Unspecified)
                            if (working) CircularProgressIndicator()
                            else if (error == null) {
                                FilledIconButton(onClick = { session?.stop() }, modifier = Modifier.size(84.dp), shape = CircleShape,
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                    Icon(if (session != null) Icons.Filled.Stop else Icons.Filled.Mic, "Stop and insert text", Modifier.size(42.dp))
                                }
                                Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
                                    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(8.dp))
                                }
                            }
                            Row {
                                TextButton(onClick = ::cancelAndFinish) { Text("Cancel") }
                            }
                            Text("On-device · nothing leaves this phone", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun begin() {
        val c = (application as OfflineTranscriberApp).container
        val lang = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        session = VoiceInputSession(c, lifecycleScope, object : VoiceInputSession.Listener {
            override fun onReady() = runOnUiThread { status = "Speak now" }
            override fun onSpeechStart() = runOnUiThread { status = "Listening…" }
            override fun onLevel(db: Float) = runOnUiThread { level = ((db + 60f) / 60f).coerceIn(0f, 1f) }
            override fun onTranscribing() = runOnUiThread { status = "Writing it down…"; working = true }
            override fun onResult(text: String) = runOnUiThread {
                setResult(Activity.RESULT_OK, Intent()
                    .putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
                    .putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, floatArrayOf(1f)))
                finish()
            }
            override fun onError(code: Int, message: String) = runOnUiThread {
                working = false; error = message
                setResult(if (code == VoiceInputSession.ERROR_NO_SPEECH) RecognizerIntent.RESULT_NO_MATCH else RecognizerIntent.RESULT_CLIENT_ERROR)
            }
        }, languageHint = lang).also { it.start() }
    }

    private fun cancelAndFinish() {
        session?.cancel()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) session?.cancel()
        super.onDestroy()
    }
}
