# Architecture

```
UI (Jetpack Compose, Material 3) · Voice popup · Voice keyboard (IME) · RecognitionService · QS tile · widget
 ├─ Home · Record · Transcribe · My Vocabulary · Models · History · Settings · Transcript editor
 │
 ├─ Repositories (Room DB: keywords, categories, models, transcriptions, segments, suggestions, settings)
 │
 ├─ TranscriptionService (foreground service, wake lock, progress notification, cancel)
 │    └─ TranscriptionRunner ── AudioDecoder (MediaExtractor/MediaCodec → 16 kHz mono WAV)
 │          │                 └ ChunkPlanner (cut at pauses)
 │          └─ ChunkedTranscriber (pure Kotlin) ── PromptBuilder · CorrectionEngine
 │                 └─ SpeechEngine ┬ WhisperEngine → libtranscriber_jni.so → whisper.cpp + ggml (copy 1)
 │                                 └ TranscribeCppEngine → libtranscribe_jni.so → transcribe.cpp + ggml (copy 2)
 │                    SpeakerDiarizer (Sortformer, transcribe.cpp) · Silero VAD (whisper.cpp, bundled model)
 │
 └─ RecordingService (foreground "microphone") └─ Recorder (AudioRecord → WAV)
```

Everything below the UI that does not need Android (engine interface, chunking, vocabulary
matching, prompt building, export, WAV handling) is plain Kotlin, so JVM tests can exercise it,
including against the real native library.

## Two inference engines
* **whisper.cpp** (module `app`, `libtranscriber_jni.so`) runs Whisper GGML `.bin` files. It supports
  the prompt, the decoder keyword boost, Silero VAD and per-segment confidence.
* **transcribe.cpp** (module `engine-transcribe`, `libtranscribe_jni.so`) runs GGUF speech models
  (Parakeet, Moonshine, SenseVoice, Canary, Granite, Qwen3-ASR, Whisper GGUF, …) and the Sortformer
  speaker model. Weights are memory-mapped (clean, file-backed pages, which Android's low-memory
  killer does not count as app memory). Prompt and vocabulary-list support depends on the model
  family. The engine reports its `EngineCapabilities`, and VoxNote only uses what the model supports.
* Each engine is its own shared library with a **private copy of ggml** (`--exclude-libs,ALL`, no
  exported ggml symbols), so the two versions never clash in one process.
* `ModelInspector` reads GGML headers and GGUF metadata (`general.architecture`, languages, licence)
  to choose the engine and the role (speech / speaker), and to reject LLMs, VAD and language-ID files.

## Inference engine details (whisper.cpp)
* whisper.cpp **v1.9.5** with ggml, **CPU backend only**, built from vendored sources with CMake.
  The arm64 build targets `armv8.2-a+fp16+dotprod`.
* **Why no GPU/NNAPI:** ggml has no NNAPI backend. Its Vulkan backend is not reliable for Whisper
  on mobile GPUs (driver-specific failures on Adreno/Mali). The spec allows acceleration only
  "where genuinely supported and stable", so it is not enabled. Flash attention on the CPU is
  enabled by default.
* JNI threading: ggml's worker threads are never attached to the JVM. Cancellation and progress
  go through `std::atomic` fields. New-segment callbacks run on the calling (attached) thread, and
  the code checks this. Invalid UTF-8 at token boundaries is converted safely.
* Only one model is in memory at a time (`EngineManager`). Before loading, RAM is estimated
  (file size plus a per-size overhead from whisper.cpp's memory table) and compared with
  `ActivityManager.MemoryInfo`. The model is released on `onTrimMemory` when idle.

## Long audio, progress and recovery
1. Audio is decoded once to a 16 kHz mono 16-bit WAV in app storage. App recordings are already
   in this format, so nothing is converted.
2. `ChunkPlanner` splits it into parts of about N seconds (default 120 s), moving each cut to the
   quietest 100 ms frame within the 4 s before it.
3. Each part is read from disk on demand, so memory is bounded by one part. The language detected
   in the first part is reused, and the previous part's tail is passed as context.
4. **After each part**, its segments, suggestions and the job's resume point (`nextChunk`) are
   written in one database transaction.
5. On a crash, reboot or cancel, completed parts are kept. On the next start, jobs left
   "running" are marked *Interrupted*, and **Resume** continues from the next part.
6. Leftover temporary files (`.part` copies, stale decodes, old exports) are removed at startup.

## Vocabulary assistance: what it really does
Three separate mechanisms, each with its own setting. The app states plainly that **none of them
is acoustic-model biasing**: none can make the model hear a word.

0. Which of the mechanisms below apply depends on the model's engine: whisper.cpp supports 1 and 2;
   transcribe.cpp models use 1 and/or a native vocabulary list where the family supports them (Parakeet
   accepts a vocabulary list but no prompt); 3 always applies. The transcript records what was used.
1. **Prompt context (decoder conditioning).** A `Glossary: term, term, …` text is passed as
   whisper's `initial_prompt` and re-applied to every 30 s window (`carry_initial_prompt`). The
   decoder then tends to spell those terms the same way. The budget is about 200 tokens, counted
   with the model's own tokenizer. Terms are ranked: starred, then already heard in this
   recording, then often found in past transcripts. With thousands of keywords, the most relevant
   ones are chosen per part. Segments that only echo the glossary (a known Whisper failure during
   silence) are dropped.
2. **Decoder keyword boost.** whisper's `logits_filter_callback` gets a token trie of the top
   keywords. When the tokens decoded so far match the start of a keyword, the next keyword token
   gets a logit bonus (Low = +1.5, i.e. about 4.5× the odds). A smaller bonus applies to a
   keyword's first token. This is a text-level nudge, like shallow fusion. Higher levels can insert
   keywords that were not said, and the settings say so.
3. **Local text post-processing (`CorrectionEngine`).** After each part, the text is compared
   with the vocabulary:
   * exact match of a listed alternative spelling (confidence 0.98)
   * same letters with different capitals, spaces or hyphens (0.90; only 0.45 when the word is a
     common English word such as "apple", so it is not shown)
   * similar spelling (Damerau–Levenshtein ≥ 0.85) or a sound-alike key; never above 0.94
   * guards: no fuzzy matching under 5 letters, no matching across punctuation, no turning one
     keyword into another, possessives kept, overlaps resolved by confidence
   Suggestions are stored separately. The original transcript is kept (`originalText`). By default
   only exact listed alternatives (≥ 0.95) are applied automatically, and each can be undone.
   Everything else waits for Accept/Reject. Automatic corrections can be turned off.

## Silence and endpointing
* **Silero VAD v6.2** (0.9 MB, bundled in `assets/vad`) is passed to whisper.cpp
  (`params.vad`). Only detected speech is decoded, and timestamps are mapped back to the original
  audio. It's faster, and it stops Whisper inventing sentences in long pauses.
* `EnergyEndpointer` (20 ms frames, adaptive noise floor = 10th percentile of the last 2 s, +10 dB
  speech threshold) ends dictation after a pause, auto-stops recordings (optional) and segments live
  captions. `speechRange()` trims long leading and trailing silence before dictation is transcribed.

## Voice input for other apps
`VoiceInputSession` records into memory, ends on a pause, trims silence, transcribes with the default
model and vocabulary, applies auto-apply corrections, replacements and filler removal, then returns the
text. Three front ends share it: `RecognizeActivity` (RECOGNIZE_SPEECH popup), `VoiceRecognitionService`
(SpeechRecognizer) and `VoiceKeyboardService` (IME). Only one session runs at a time.

## Live captions
`LiveCaptioner` gets 16 kHz frames from the recorder, or from `DeviceAudioCaptureService` (Android
playback capture through MediaProjection). Speech is cut into segments of 8 s at most, at 0.7 s
pauses:
* **Draft text:** while speech is in progress, the current segment is re-transcribed about every
  1.2 s, but only when the worker is idle. Only the newest draft is processed, so a slow phone never
  builds a queue.
* **Final text:** each finished segment is queued in order and never dropped. When several are
  waiting, they are merged into one run.

Captions are a preview: the saved audio is transcribed normally afterwards.

## Speakers
After transcription, if *Identify speakers* is on and a Sortformer model is installed, the audio is
diarized in 15-minute windows. Segments get the speaker with the largest time overlap, and speakers
are numbered in order of appearance. Numbering is consistent within a window; a later window may
assign new numbers, which "Merge" in the editor fixes. Names live on the transcript and are used by
all exports (VTT uses `<v Name>` voice tags).

## Text pipeline (after each part)
engine text → auto-applied corrections (≥ threshold) → word replacements → filler/stutter removal.
The engine's text is kept as `originalText`, and suggestions remain reviewable.

## Privacy
* No `INTERNET` or `ACCESS_NETWORK_STATE` permission (explicitly removed in the manifest; check
  with `aapt2 dump permissions`).
* `allowBackup=false`, and data-extraction rules exclude everything from cloud and device transfer.
* Files live in app-private storage. Data leaves only when you export or share.
* Permissions: `RECORD_AUDIO`, foreground-service types (microphone, dataSync, mediaProcessing,
  mediaProjection for device-audio captions), `POST_NOTIFICATIONS`, `WAKE_LOCK`.
