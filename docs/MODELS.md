# Importing Whisper models from Hugging Face

The app runs Whisper models in **whisper.cpp GGML format** (`ggml-*.bin`). These are published at
<https://huggingface.co/ggerganov/whisper.cpp/tree/main>.

> The app itself has no internet access. Downloading is done by you, in your web browser or on a
> computer. The app then imports the file from local storage. Nothing downloads automatically.

## Which model?

| Device RAM | Suggested file | Download | RAM used |
|---|---|---|---|
| 3–4 GB | `ggml-base-q8_0.bin` or `ggml-base.en.bin` (English) | 78–142 MB | ~300–400 MB |
| 4–6 GB | `ggml-small-q5_1.bin` / `ggml-small.en-q5_1.bin` | 181 MB | ~600 MB |
| 6–8 GB | `ggml-medium-q5_0.bin` or `ggml-large-v3-turbo-q5_0.bin` | ~520–550 MB | ~1.2 GB |
| 8 GB+ | `ggml-large-v3-q5_0.bin` | 1.1 GB | ~2.1 GB |

`.en` models handle English only, and are a little more accurate for English. Quantized files
(`q5_0`, `q5_1`, `q8_0`) are smaller and faster, with a small loss of accuracy. Bigger models are
more accurate but slower: on a mid-range phone, `small` takes roughly as long as the audio
itself, and `tiny`/`base` run several times faster than real time.

## Steps (on the phone)
1. Open **Models**. Tap **Download in browser** next to a recommended model, or go to
   <https://huggingface.co/ggerganov/whisper.cpp/tree/main>, open a `ggml-….bin` file and tap the
   download (↓) link.
2. When the download finishes, go back to **Models** and tap **Import a model file**. Pick the
   file from *Downloads*.
3. The app checks the file header, copies the file into the app, then **verifies** it by
   loading it in the engine. The first model you add becomes the default.
4. You can now delete the copy in *Downloads* to free space.

**Use a model file without copying** keeps the file where it is (it saves space for large models).
The file must then stay in place.

## Steps (from a computer)
Download the `.bin` file, copy it to the phone (USB, SD card…) and import it as above.

## Compatibility checks
The app does not trust file names. It reads the header and reports:

* **Compatible**: a Whisper GGML model. Size class, weight type and language support are shown.
* **GGUF**: rejected. The bundled engine loads Whisper only in GGML `.bin` format; GGUF files are
  usually text LLMs.
* **safetensors / PyTorch (.pt)**: rejected, with a pointer to whisper.cpp's conversion scripts
  (`models/convert-h5-to-ggml.py`, `convert-pt-to-ggml.py`).
* **HTML**: the browser saved the web page instead of the file. Use the download (↓) link.
* **Truncated**: the file is smaller than a complete model, so download it again.

**Verify** proves compatibility by actually loading the model. Before loading, the app estimates
the RAM the model needs and refuses models that would get the app killed (this can be overridden
in Settings).

## Fine-tuned and custom models
Any Whisper fine-tune converted with whisper.cpp's conversion scripts to GGML works, for example a
domain-specific model from Hugging Face converted with `convert-h5-to-ggml.py`.
