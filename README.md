# VoxNote — offline transcription for Android

Native Android app (Kotlin + Jetpack Compose + C++/NDK) that turns speech into text **entirely on the
device**. It runs two local inference engines: whisper.cpp (Whisper GGML models) and transcribe.cpp
(GGUF models such as Parakeet, Moonshine, SenseVoice, Whisper GGUF, plus the Sortformer speaker model).

* **No internet permission.** The APK does not request `android.permission.INTERNET`, so Android
  itself blocks every network socket from the app. No analytics, ads, accounts or cloud backup.
* **Use it anywhere.** VoxNote is a system speech service, a voice-input popup for keyboards and
  websites, and a voice keyboard, so you can dictate into other apps with your own vocabulary.
* **My Vocabulary.** A large ADD KEYWORD button, alternative spellings, categories, paste-many,
  TXT/CSV import and export, plus exact word replacements. Built for thousands of terms.

## Features

| Area | What's included |
|---|---|
| Engines | **whisper.cpp v1.9.5**: GGML Whisper tiny to large-v3-turbo, keyword boost, Silero VAD. **transcribe.cpp 0.4.2**: GGUF Parakeet (fast, 25 European languages), Moonshine, SenseVoice, Canary, Whisper GGUF and more. CPU, configurable threads and quality, auto or manual language |
| Long audio | Chunks cut at pauses, progress and ETA, cancel, resume after interruption, foreground service |
| Silence | Bundled **Silero VAD** skips silence (faster, no invented text in pauses). An energy endpointer auto-stops dictation and, optionally, recordings |
| Speakers | Optional **Sortformer** speaker model labels who is speaking. In the editor, rename speakers, merge two labels for one person, or move one part to another speaker. Exports include names |
| Vocabulary assistance | Prompt context (whisper), decoder keyword boost (whisper), native vocabulary lists (models that support them), local correction suggestions, exact replacements, filler and stutter removal, a punctuation hint per language. Each is labelled honestly |
| Voice input | System `RecognitionService`, `RECOGNIZE_SPEECH` popup (SwiftKey, website voice search), voice keyboard (for keyboards whose mic key switches to a voice keyboard, e.g. HeliBoard and FlorisBoard). Gboard can't use it |
| Live captions | While recording, and for **audio played by other apps** (playback capture, Android 10+). Captured audio is saved so you can transcribe it properly afterwards |
| Audio | Recorder (16 kHz WAV, pause/resume, level meter, works with the screen off, pauses other audio). Import from storage, the Share menu and "Open with": WAV, MP3, M4A/AAC, FLAC, OGG, OPUS, video sound |
| Transcript | Edit, restore original, uncertain parts shaded, search, find and replace, tap to play, suggestions with accept/reject/undo. Export TXT, timestamped TXT, DOCX, SRT, VTT (speaker voice tags), Markdown, JSON |
| Shortcuts | Quick Settings tile and home-screen widget (one tap to record), saved **modes** (model, language, vocabulary categories, speakers, filler removal) |
| Models | Import by copy or register in place. GGML and GGUF compatibility is read from the file, then confirmed by a real load. Speaker, VAD and LLM files are recognised. RAM check before loading. Optional Hugging Face catalogue opened in the browser |

## Documentation

* [docs/BUILDING.md](docs/BUILDING.md): build the APK
* [docs/MODELS.md](docs/MODELS.md): get and import models from Hugging Face
* [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how it works and what vocabulary assistance really does
* [docs/VOICE_INPUT.md](docs/VOICE_INPUT.md): dictating into other apps
* [docs/TESTING.md](docs/TESTING.md): automated tests and the airplane-mode checklist
* [docs/FEATURE_SOURCES.md](docs/FEATURE_SOURCES.md): which ideas came from which open-source projects

## Licences

App code: yours. whisper.cpp, ggml, transcribe.cpp and Silero VAD: MIT (licence files under
`*/src/main/cpp/third_party/`). Models you download carry their own licences: Whisper is MIT,
Parakeet is CC-BY-4.0, Sortformer uses the NVIDIA Open Model License. `jfk.wav` comes from the
whisper.cpp repository.
