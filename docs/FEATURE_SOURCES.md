# Where VoxNote's features come from

The following open-source projects were reviewed for features VoxNote could adopt. Ideas were
re-implemented from scratch. No code was copied from GPL projects (VoiceInk, FluidVoice). The MIT
libraries whisper.cpp and transcribe.cpp are vendored with their licences.

| Feature in VoxNote | Inspired by |
|---|---|
| Three-way voice input (popup, RecognitionService, voice keyboard), keyboard compatibility notes | notune/android_transcribe_app |
| transcribe.cpp engine (GGUF, Parakeet, Sortformer), browser-only model downloads | notune/android_transcribe_app, cjpais/Handy |
| Adaptive-noise-floor endpointer, silence trimming, live captions (draft text replaced as it updates, final text queued) | notune/android_transcribe_app |
| Pausing other audio while recording | notune/android_transcribe_app (AudioFocusPauser) |
| Silero VAD to skip silence | cjpais/Handy |
| Language-gated filler removal and stutter collapse | cjpais/Handy |
| Exact word replacements | Beingpax/VoiceInk |
| Per-language punctuation starter prompt | Beingpax/VoiceInk |
| Modes (saved presets) | Beingpax/VoiceInk (Power Mode) |
| Speaker labels with rename/merge, Markdown/JSON export | altic-dev/FluidVoice (FluidMeet) |
| Confidence shading, speaker colours | google/live-transcribe-speech-engine |
| Quick Settings tile, home-screen widget | the phone equivalent of desktop shortcuts (Handy, VoiceInk) |

Deliberately not adopted: cloud speech APIs (live-transcribe-speech-engine), cloud AI clean-up and
screen context (VoiceInk, FluidVoice), TensorFlow Lite Whisper (vilassn/whisper_android: weaker than
whisper.cpp), and on-device LLM clean-up (too large for most phones today).
