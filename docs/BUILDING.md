# Building

## Requirements
* JDK 17 or newer (tested with 21)
* Android SDK Platform 35, Build-Tools 35.0.0, NDK **27.2.12479018**, CMake **3.22.1**
  (Android Studio installs these automatically from the Gradle files; on the command line:
  `sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018" "cmake;3.22.1"`)

The whisper.cpp sources are vendored, so the native build needs no network access.

## Android Studio
Open the project folder, wait for the Gradle sync, then run the `app` configuration on a device.

## Command line
```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties
```
```bash
./gradlew :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Signed release APK
Create a keystore once:
```bash
keytool -genkeypair -keystore release.keystore -alias offline-transcriber -keyalg RSA -keysize 3072 -validity 10000
```
Then create `keystore.properties` in the project root (it is git-ignored):
```
storeFile=release.keystore
storePassword=...
keyAlias=offline-transcriber
keyPassword=...
```
```bash
./gradlew :app:assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`. Without `keystore.properties`, the
release build is signed with the debug key.

### Install
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Notes
* ABIs: `arm64-v8a` (phones and tablets) and `x86_64` (emulators, Chromebooks). The arm64 build
  targets ARMv8.2-A with fp16 and dot-product (practically every arm64 phone since 2018).
* Native code is always compiled with optimisations, even in debug builds, because an
  unoptimised whisper.cpp is 10–50× slower.
* Native libraries are linked for 16 KB page sizes (needed for Android 15+ devices).
