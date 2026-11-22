# Dictating into other apps

VoxNote plugs into Android's speech-to-text in three ways. Each one uses your default model, My
Vocabulary, your replacements and filler removal, entirely on the device.

| Path | Used by | What happens |
|---|---|---|
| **Voice-input popup** (`RECOGNIZE_SPEECH`) | SwiftKey, website voice search, many apps | A compact panel slides up, you speak, and the text is inserted. It stops after a pause (Settings › Voice input) or when you tap ■ |
| **System speech service** (`RecognitionService`) | Keyboards and apps using `SpeechRecognizer` | Runs in the background. Select **VoxNote (on-device)** under *Settings › Apps › Default apps › Voice input* (the name varies by phone) |
| **Voice keyboard** (IME) | HeliBoard, FlorisBoard, Fossify, AnySoftKeyboard and others whose mic key switches to a voice keyboard | Enable *VoxNote (on-device)* under *Settings › System › Keyboard*. Tap the big key, speak, tap again. ⌨ switches back |

**Gboard** only uses Google's own voice typing, so it can't hand speech to VoxNote.

Notes
* Grant the microphone permission once in the app: open Home › Voice input in other apps › *Try voice input*.
* If Android shows a chooser, pick VoxNote and choose **Always**.
* Dictation applies only corrections at or above the auto-apply threshold (listed spellings), because
  there is no review step. Full suggestions are available for recordings and files.
* Fast models feel best: Parakeet 110M or Whisper base/small quantized.
