package com.offlinetranscriber.voice

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.offlinetranscriber.OfflineTranscriberApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * "VoxNote voice keyboard": an input method with one big microphone key. Keyboards such as
 * HeliBoard, FlorisBoard or Fossify switch to the system voice keyboard from their mic key.
 */
class VoiceKeyboardService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: VoiceInputSession? = null
    private lateinit var status: TextView
    private lateinit var mic: Button
    private lateinit var level: ProgressBar

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreateInputView(): View {
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) Color.rgb(32, 33, 36) else Color.rgb(236, 239, 244)
        val fg = if (dark) Color.WHITE else Color.rgb(32, 33, 36)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        status = TextView(this).apply {
            text = "Tap the microphone and speak"
            setTextColor(fg); textSize = 15f; gravity = Gravity.CENTER
        }
        level = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        mic = Button(this).apply {
            text = "🎤  Tap to speak"
            textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(32).toFloat(); setColor(Color.rgb(198, 40, 40)) }
            setOnClickListener { toggle() }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun key(label: String, weight: Float, action: () -> Unit) = Button(this).apply {
            text = label; textSize = 16f; setTextColor(fg); isAllCaps = false
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (dark) Color.rgb(60, 64, 67) else Color.WHITE) }
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), weight).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        }
        row.addView(key("⌨", 1f) { switchBack() })
        row.addView(key(",", 1f) { commit(",") })
        row.addView(key("space", 3f) { commit(" ") })
        row.addView(key(".", 1f) { commit(".") })
        row.addView(key("⌫", 1.2f) { backspace() })
        row.addView(key("↵", 1.2f) { enter() })
        root.addView(status, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(level, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { setMargins(0, dp(8), 0, dp(8)) })
        root.addView(mic, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Open VoxNote once and allow the microphone to use voice typing."
        } else if (session == null) {
            status.text = "Tap the microphone and speak"
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        session?.cancel(); session = null; idle()
        super.onFinishInputView(finishingInput)
    }

    private fun toggle() {
        val s = session
        if (s != null) { s.stop(); return }
        val c = (application as OfflineTranscriberApp).container
        session = VoiceInputSession(c, scope, object : VoiceInputSession.Listener {
            override fun onReady() = ui { status.text = "Listening… tap again when you are done"; mic.text = "■  Done" }
            override fun onLevel(db: Float) = ui { level.progress = (((db + 60f) / 60f) * 100).toInt().coerceIn(0, 100) }
            override fun onTranscribing() = ui { status.text = "Writing it down…"; mic.isEnabled = false }
            override fun onResult(text: String) = ui {
                val before = currentInputConnection?.getTextBeforeCursor(1, 0)
                val sep = if (before.isNullOrEmpty() || before.last().isWhitespace()) "" else " "
                commit(sep + text)
                session = null; idle()
            }
            override fun onError(code: Int, message: String) = ui { session = null; idle(); status.text = message }
        }, languageHint = currentInputEditorInfo?.hintLocales?.get(0)?.toLanguageTag(), autoStop = false).also { it.start() }
    }

    private fun idle() {
        if (!::mic.isInitialized) return
        mic.isEnabled = true; mic.text = "🎤  Tap to speak"; level.progress = 0
        status.text = "Tap the microphone and speak"
    }

    private fun ui(block: () -> Unit) { mic.post(block) }
    private fun commit(t: String) { currentInputConnection?.commitText(t, 1) }
    private fun backspace() {
        val ic = currentInputConnection ?: return
        if (ic.getSelectedText(0).isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1)
    }
    private fun enter() {
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) currentInputConnection?.performEditorAction(action)
        else { currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)) }
    }
    private fun switchBack() {
        if (Build.VERSION.SDK_INT >= 28 && switchToPreviousInputMethod()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onDestroy() { session?.cancel(); scope.cancel(); super.onDestroy() }
}
