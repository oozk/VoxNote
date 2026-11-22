plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Second inference engine: transcribe.cpp (GGUF models: Parakeet, Whisper GGUF, Moonshine,
// Canary, SenseVoice, … plus Sortformer speaker diarization). It is built as its own
// shared library with its own private copy of ggml, separate from whisper.cpp's.
android {
    namespace = "com.offlinetranscriber.engine.tc"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
