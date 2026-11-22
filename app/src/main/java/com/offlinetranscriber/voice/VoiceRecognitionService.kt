package com.offlinetranscriber.voice

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.offlinetranscriber.OfflineTranscriberApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Android system speech-recognition service, so apps and keyboards that use SpeechRecognizer
 * get on-device VoxNote transcription (select VoxNote under Settings › Voice input).
 */
class VoiceRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: VoiceInputSession? = null

    override fun onStartListening(intent: Intent, callback: Callback) {
        session?.cancel()
        val c = (application as OfflineTranscriberApp).container
        session = VoiceInputSession(c, scope, object : VoiceInputSession.Listener {
            override fun onReady() = safe { callback.readyForSpeech(Bundle()) }
            override fun onSpeechStart() = safe { callback.beginningOfSpeech() }
            override fun onLevel(db: Float) = safe { callback.rmsChanged((db + 60f) / 6f) }  // ~0..10 like other engines
            override fun onEndOfSpeech() = safe { callback.endOfSpeech() }
            override fun onResult(text: String) = safe {
                callback.results(Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                    putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
                })
            }
            override fun onError(code: Int, message: String) = safe {
                callback.error(when (code) {
                    VoiceInputSession.ERROR_NO_PERMISSION -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                    VoiceInputSession.ERROR_NO_SPEECH -> SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    VoiceInputSession.ERROR_AUDIO -> SpeechRecognizer.ERROR_AUDIO
                    VoiceInputSession.ERROR_BUSY -> SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    else -> SpeechRecognizer.ERROR_CLIENT
                })
            }
        }, languageHint = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)).also { it.start() }
    }

    override fun onStopListening(callback: Callback) { session?.stop() }

    override fun onCancel(callback: Callback) { session?.cancel(); session = null }

    override fun onDestroy() {
        session?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private inline fun safe(block: () -> Unit) { runCatching(block) }   // client may have gone away
}
