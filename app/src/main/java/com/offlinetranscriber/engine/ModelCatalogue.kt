package com.offlinetranscriber.engine

/**
 * Curated list of whisper.cpp-compatible models published on Hugging Face
 * (repository ggerganov/whisper.cpp). Purely informational: the app has no
 * INTERNET permission, so downloads are opened in the user's browser and the
 * downloaded file is then imported from local storage.
 */
data class CatalogueModel(
    val fileName: String,
    val title: String,
    val approxSizeMb: Int,
    val approxRamMb: Int,
    val englishOnly: Boolean,
    val note: String,
    /** Hugging Face repository; whisper.cpp GGML models by default. */
    val repo: String = "ggerganov/whisper.cpp",
    val engine: String = ENGINE_WHISPER,
    val role: String = ROLE_ASR,
) {
    val downloadUrl: String get() = "https://huggingface.co/$repo/resolve/main/$fileName?download=true"
    val pageUrl: String get() = "https://huggingface.co/$repo/blob/main/$fileName"
}

object ModelCatalogue {
    const val REPOSITORY_URL = "https://huggingface.co/ggerganov/whisper.cpp/tree/main"

    val models = listOf(
        CatalogueModel("ggml-tiny-q8_0.bin", "Tiny (quantized)", 42, 200, false, "Fastest. Lower accuracy. Good for testing."),
        CatalogueModel("ggml-base-q8_0.bin", "Base (quantized)", 78, 300, false, "Fast, decent accuracy. Good on 4 GB phones."),
        CatalogueModel("ggml-base.en.bin", "Base English", 142, 380, true, "English only; more accurate than multilingual base for English."),
        CatalogueModel("ggml-small-q5_1.bin", "Small (quantized)", 181, 600, false, "Recommended balance of speed and accuracy."),
        CatalogueModel("ggml-small.en-q5_1.bin", "Small English (quantized)", 181, 600, true, "Recommended for English."),
        CatalogueModel("ggml-small.bin", "Small", 466, 850, false, "Full-precision small model."),
        CatalogueModel("ggml-large-v3-turbo-q5_0.bin", "Large v3 Turbo (quantized)", 547, 1200, false, "High accuracy, needs 6 GB+ RAM. Slow on older phones."),
        CatalogueModel("ggml-medium-q5_0.bin", "Medium (quantized)", 514, 1200, false, "High accuracy, slow. 6 GB+ RAM recommended."),
        CatalogueModel("ggml-medium.en-q5_0.bin", "Medium English (quantized)", 514, 1200, true, "High accuracy for English, slow."),
        CatalogueModel("ggml-large-v3-q5_0.bin", "Large v3 (quantized)", 1080, 2100, false, "Best accuracy, very slow on phones. 8 GB+ RAM."),
        // transcribe.cpp (GGUF) models
        CatalogueModel("parakeet-tdt_ctc-110m-Q8_0.gguf", "Parakeet 110M (fast English)", 135, 300, true,
            "Very fast on phone CPUs with punctuation. Engine: transcribe.cpp. Accepts a vocabulary list.",
            repo = "handy-computer/parakeet-tdt_ctc-110m-gguf", engine = ENGINE_TRANSCRIBE),
        CatalogueModel("parakeet-tdt-0.6b-v3-Q8_0.gguf", "Parakeet v3 0.6B (25 European languages)", 740, 1000, false,
            "High accuracy and much faster than Whisper of similar quality. 6 GB+ RAM. Engine: transcribe.cpp.",
            repo = "handy-computer/parakeet-tdt-0.6b-v3-gguf", engine = ENGINE_TRANSCRIBE),
        CatalogueModel("moonshine-base-Q8_0.gguf", "Moonshine base (tiny English)", 77, 200, true,
            "Smallest fast English model. Engine: transcribe.cpp.", repo = "handy-computer/moonshine-base-gguf", engine = ENGINE_TRANSCRIBE),
        CatalogueModel("SenseVoiceSmall-Q8_0.gguf", "SenseVoice Small (zh/en/ja/ko/yue)", 253, 450, false,
            "Fast for Chinese, Cantonese, English, Japanese, Korean. Engine: transcribe.cpp.",
            repo = "handy-computer/SenseVoiceSmall-gguf", engine = ENGINE_TRANSCRIBE),
        CatalogueModel("diar_streaming_sortformer_4spk-v2.1-Q8_0.gguf", "Speaker identification (Sortformer)", 139, 350, false,
            "Not a speech model: labels who is speaking (up to 4 at a time). NVIDIA Open Model License.",
            repo = "handy-computer/diar_streaming_sortformer_4spk-v2.1-gguf", engine = ENGINE_TRANSCRIBE, role = ROLE_DIARIZE),
    )
}
