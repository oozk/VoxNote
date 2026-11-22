cmake_minimum_required(VERSION 3.22)
project(voxnote_transcribe_native C CXX)

# transcribe.cpp (vendored, CPU backend only) + JNI bridge -> libtranscribe_jni.so
# Used both by the Android build and by a host build for JVM integration tests.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(TC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/transcribe.cpp)

set(TRANSCRIBE_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_SHARED    OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_METAL           OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_VULKAN          OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_CUDA            OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_HIP             OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_USE_OPENMP      OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_USE_SYSTEM_BLAS OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_INSTALL         OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE                OFF CACHE BOOL "" FORCE)
if (ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif()

add_subdirectory(${TC_DIR} ${CMAKE_BINARY_DIR}/transcribe)

add_library(transcribe_jni SHARED tc_jni.cpp)
target_link_libraries(transcribe_jni PRIVATE transcribe)
target_include_directories(transcribe_jni PRIVATE ${TC_DIR}/include)
# Keep this library's ggml copy private (whisper.cpp's lives in another .so).
target_link_options(transcribe_jni PRIVATE -Wl,--exclude-libs,ALL)
set_target_properties(transcribe_jni PROPERTIES CXX_VISIBILITY_PRESET hidden)

if (ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(transcribe_jni PRIVATE ${LOG_LIB})
    target_link_options(transcribe_jni PRIVATE "-Wl,-z,max-page-size=16384" -Wl,--gc-sections)
else()
    find_package(JNI REQUIRED)
    target_include_directories(transcribe_jni PRIVATE ${JNI_INCLUDE_DIRS})
endif()
