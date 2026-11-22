cmake_minimum_required(VERSION 3.22)
project(offline_transcriber_native C CXX)

# Native inference engine for Offline Transcriber.
# Builds the vendored whisper.cpp + ggml (CPU backend only) and the JNI bridge.
# The same file is used for the Android build (via Gradle externalNativeBuild)
# and for a host (Linux/macOS) build used by the JVM integration test.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp)
file(STRINGS ${WHISPER_DIR}/VENDORED_VERSION WHISPER_VERSION_LINES)
list(GET WHISPER_VERSION_LINES 1 WHISPER_VERSION)

# ---- ggml (CPU only; GPU backends are intentionally not built, see docs/ARCHITECTURE.md)
set(GGML_STATIC         ON  CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS   OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP         OFF CACHE BOOL "" FORCE)  # use ggml's own thread pool (no libomp to ship)
set(GGML_LLAMAFILE      OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE         OFF CACHE BOOL "" FORCE)
set(GGML_METAL          OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN         OFF CACHE BOOL "" FORCE)
set(GGML_CUDA           OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL         OFF CACHE BOOL "" FORCE)
set(GGML_BLAS           OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE         OFF CACHE BOOL "" FORCE)

if (ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    # Baseline ARMv8.2-A with fp16 + dotprod is supported by practically every
    # arm64 phone sold since 2018. ggml additionally performs runtime feature checks.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif()

add_subdirectory(${WHISPER_DIR}/ggml ${CMAKE_BINARY_DIR}/ggml)

# ---- whisper
add_library(whisper STATIC
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp)
target_include_directories(whisper PUBLIC ${WHISPER_DIR}/include PRIVATE ${WHISPER_DIR}/src)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")
target_link_libraries(whisper PUBLIC ggml)

# ---- JNI bridge
add_library(transcriber_jni SHARED transcriber_jni.cpp)
target_compile_definitions(transcriber_jni PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")
target_link_libraries(transcriber_jni PRIVATE whisper ggml)

if (ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(transcriber_jni PRIVATE ${LOG_LIB})
    if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
        foreach(t whisper ggml ggml-base ggml-cpu transcriber_jni)
            if (TARGET ${t})
                target_compile_options(${t} PRIVATE -O3 -ffunction-sections -fdata-sections)
            endif()
        endforeach()
        target_link_options(transcriber_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
    endif()
    # 16 KB page size support (required for Google Play on Android 15+ devices)
    target_link_options(transcriber_jni PRIVATE "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    target_include_directories(transcriber_jni PRIVATE ${JNI_INCLUDE_DIRS})
endif()
