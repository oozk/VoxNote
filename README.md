# Offline Transcriber (Android)

Native Android app (Kotlin + Jetpack Compose + C++/NDK) that turns recorded or imported audio
into text **entirely on the device**, using Whisper models in whisper.cpp GGML format.

* **No internet permission.** The APK does not request `android.permission.INTERNET`, so Android
  itself blocks every network socket from the app. Audio, transcripts, keywords and settings cannot
  leave the device through it. No analytics, no ads, no accounts, no cloud backup (backup is disabled).
* **Real local inference.** whisper.cpp v1.9.5 (vendored in `app/src/main/cpp/third_party`) on the CPU
  via a small JNI bridge. No Android speech services, no cloud fallback.
* **My Vocabulary.** A large ADD KEYWORD button, alternative spellings, categories, paste-many,
  TXT/CSV import and export, duplicate detection. Built to handle thousands of terms.

## Features

| Area | What's included |
|---|---|
| Engine | Loads GGML Whisper models (tiny through large-v3-turbo, F16/F32/Q4–Q8/K-quants) from local storage. Auto or manual language. Configurable threads, CPU mode and quality (greedy, beam 3 or beam 5). Long audio is split into chunks at pauses. Timestamps, progress, ETA, cancellation, resume after interruption |
| Models | Import by copy or register in place (no copy). Compatibility is checked from the file header, then confirmed by a real load. Clear errors for GGUF, safetensors, PyTorch, HTML pages and truncated downloads. Default model, removal, RAM and storage info, memory check before loading, optional Hugging Face catalogue opened in the browser |
| Audio | In-app recorder (16 kHz WAV, pause/resume, level meter, works with the screen off). Import from storage, the Share menu and "Open with". WAV, MP3, M4A/AAC, FLAC, OGG, OPUS and video sound tracks are decoded with Android's own codecs |
| Vocabulary | Add/edit/delete/search, starred terms, 9 built-in categories plus your own, bulk paste, TXT/CSV import with preview, merge of new spellings, CSV/TXT export |
| Vocabulary assistance | (1) prompt context, (2) a decoder keyword boost, (3) local correction suggestions, each clearly labelled; see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Transcript | Edit parts, restore the original, copy, select text, search, find and replace, tap a timestamp to play from there. Export TXT, timestamped TXT, DOCX, SRT, VTT. Save to the device or share |
| History | Local list with preview, search, reopen, export, delete |
| Settings | Everything from the spec, plus "Delete all app data" |

## Documentation

* [docs/BUILDING.md](docs/BUILDING.md): build the APK
* [docs/MODELS.md](docs/MODELS.md): get and import models from Hugging Face
* [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how it works and what vocabulary assistance really does
* [docs/TESTING.md](docs/TESTING.md): automated tests and the airplane-mode test checklist

## Licences

App code: yours. whisper.cpp and ggml: MIT (`app/src/main/cpp/third_party/whisper.cpp/LICENSE`).
`app/src/test/resources/jfk.wav` comes from the whisper.cpp repository.
