# Testing

## Automated tests (JVM)
```bash
./gradlew :app:testDebugUnitTest
```
| Suite | Covers |
|---|---|
| `KeywordImportTest` | bulk paste from the spec (9 terms), bullets and comments, `keyword \| alts \| category` syntax, CSV (quotes, `;`/tab delimiters, BOM, header detection), duplicates (case/space/hyphen-insensitive, within the import and against existing), merging new spellings, 5 000-term performance, CSV/TXT export round trip |
| `CorrectionEngineTest` | listed spellings, acronym case, spacing/hyphens, fuzzy and sound-alike matches, no blind replacement of common words, no matching across punctuation, possessives, apply/locate after edits, 5 000-term performance |
| `PromptBuilderTest`, `SimilarityTest`, `CleanSegmentsTest` | token budget, ranking, context, distance and phonetic keys, glossary-echo and loop filtering |
| `AudioFileTest` | WAV write/read, crash-safe headers, streaming headers, 48k/44.1k→16k resampling (accuracy and anti-aliasing), downmix, chunk cuts in silence, plan persistence |
| `ModelInspectorTest` | GGML Whisper detection (size, quantization, language), GGUF/safetensors/PyTorch/HTML/truncated rejection, memory verdicts |
| `ExportersTest` | SRT, VTT, TXT, timestamped TXT, DOCX (valid ZIP, well-formed escaped XML) |
| `VocabularyRepositoryTest`, `TranscriptionRepositoryTest` (Robolectric + Room) | CRUD, duplicates, 2 000-keyword import, merge, search, categories; accept/reject/undo/stale suggestions, find and replace, export, queue, resume and restart, interruption marking, delete |
| `NativeEngineHostTest` | **the real JNI + whisper.cpp code and a real model**: auto language detection, JFK transcription, token counting, prompt and boost, a 72 s file in 3 chunks with continuous timestamps, cancellation |
| `EndToEndPipelineTest` (Robolectric + native) | import model, verify, import audio, transcribe with vocabulary, suggestions, export; cancel mid-run then resume without duplicates |

The native suites need the JNI library built for your computer and a model:
```bash
cmake -S app/src/main/cpp -B build-host -G Ninja -DCMAKE_BUILD_TYPE=Release
```
```bash
cmake --build build-host
```
```bash
curl -L -o ggml-tiny.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin
```
```bash
./gradlew :app:testDebugUnitTest -Pnative.lib.path=$PWD/build-host/libtranscriber_jni.so -Ptest.model.path=$PWD/ggml-tiny.bin
```
Without those properties the native suites are skipped. No test uses the network.

## On-device checklist (airplane mode)
Turn on **airplane mode** (Wi-Fi and mobile data off) before step 1.

1. Models → Import `ggml-base.en.bin` (or any model) from Downloads → *Compatible · verified*.
2. Record → RECORD 30 s, pause and resume, stop → transcription starts → the text appears.
3. Transcribe → choose an MP3/M4A file → START → progress, part x/y and ETA → open the transcript.
4. Share an audio file from a file manager to Offline Transcriber → it imports and opens the Transcribe screen.
5. My Vocabulary → paste the 9-term list from the spec → *9 new* → add. Import a CSV. Export CSV.
6. Add `Thermo Fisher` with the alternative `thermofisher`, transcribe a sentence that says it, and check the suggestion or automatic correction. Undo it.
7. Transcript → edit a part, find and replace, export TXT/DOCX/SRT/VTT, share.
8. History → reopen, delete.
9. Large file: a 1–2 h recording, cancelled halfway → *Resume* continues → completes.
10. Low memory: try `large-v3` on a 4 GB phone → refused with an explanation, no crash.
11. Force-stop the app during a transcription → reopen → *Interrupted* → Resume.
12. Settings → Delete all app data → everything is gone.
