# CLAUDE.md

This file is the entry brief for any Claude Code session opened at this project root. Read it first.

## Project goal

**Audio Recorder** — an Android app that captures audio from **USB Audio Class (UAC) input devices** (external USB audio interfaces, USB microphones, USB ADCs). It is a sibling project to **Matrix Player** (a USB-audio-aware music player at `C:\Users\incxiuefb\Documents\Files\clone\media_player`) and is meant to reuse the same low-level audio engine and visual theme.

Target user: an audiophile who plugs a USB audio interface into an Android phone and wants bit-perfect, hardware-controlled recording — not the canned `MediaRecorder` pipeline.

## Current state — important

This is a **minimal scaffold only**. The app builds and launches into a placeholder screen. **None of the recording logic is implemented yet.** The next session is expected to do that work.

What exists:

- Gradle 9.2.1 / AGP 9.0.1 multi-module project skeleton (only `:app` is wired up so far)
- AndroidManifest with `RECORD_AUDIO`, USB host, foreground service permissions, and `USB_DEVICE_ATTACHED` intent filter on `MainActivity`
- `MainActivity` that inflates a placeholder layout
- Full theme + colors + CMU Serif font copied from Matrix Player (renamed `Theme.MatrixPlayer` → `Theme.AudioRecorder`)
- `.gitmodules` declaring `audioengine` submodule at <https://github.com/minervarr/audio_engine.git> — **not yet cloned**

What is missing:

- The `audioengine` submodule directory is empty (run `git submodule update --init --recursive`)
- `:audioengine` is **commented out** in `settings.gradle` and the app does not yet depend on it
- No recording code, no UI beyond a placeholder TextView, no file output, no service
- The engine itself does **not yet support recording** — it is playback-only (see "Audio engine integration" below)

## Build commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew installDebug           # Build and install on connected device
./gradlew test                   # Unit tests
./gradlew connectedAndroidTest   # Instrumented tests on a device
./gradlew clean                  # Clean build outputs
```

## First-time setup

```bash
# 1. Initialize the git repo (if not yet)
git init

# 2. Pull in the audio engine submodule
git submodule update --init --recursive
#    -> populates audioengine/ from https://github.com/minervarr/audio_engine.git

# 3. Build
./gradlew assembleDebug
```

After step 2 completes (and you are ready to actually use the engine), open `settings.gradle` and uncomment:

```gradle
include ':audioengine'
```

…and open `app/build.gradle` and uncomment:

```gradle
implementation project(':audioengine')
```

## Project layout

```
audio_recorder/
├── CLAUDE.md                         # this file
├── .gitmodules                       # registers audioengine submodule
├── .gitignore
├── settings.gradle                   # includes :app (audioengine commented out)
├── build.gradle                      # root, plugins declared apply false
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml            # version catalog — same as Matrix Player
│   └── wrapper/                      # Gradle 9.2.1 wrapper
├── gradlew, gradlew.bat
├── audioengine/                      # GIT SUBMODULE (empty until init)
└── app/
    ├── build.gradle                  # namespace com.example.audio_recorder, minSdk 24, targetSdk 34, Java 11
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/audio_recorder/
        │   └── MainActivity.java     # placeholder
        └── res/
            ├── values/
            │   ├── colors.xml        # full palette (see below)
            │   ├── themes.xml        # Theme.AudioRecorder
            │   └── strings.xml
            ├── values-night/themes.xml
            ├── layout/activity_main.xml
            ├── drawable/             # bg_tab_indicator, bg_artwork_rounded(_sm)
            ├── font/                 # CMU Serif (4 ttf + cmu_serif.xml family)
            ├── mipmap-*/             # launcher icons (webp, copied from player)
            └── xml/                  # (empty — add backup_rules etc. if needed)
```

## Build config snapshot

- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34
- **Compile SDK**: 36
- **Java**: 11
- **Gradle**: 9.2.1 / **AGP**: 9.0.1
- **View binding**: enabled
- **ABI splits**: arm64-v8a, x86_64 (no universal APK)
- **Signing**: none (do **not** copy Matrix Player's `bruno.jks` credentials — they're hardcoded in its `build.gradle` and are a security issue)

## Audio engine integration

The engine is a sophisticated, **separate** Android library module:

- **Repo**: <https://github.com/minervarr/audio_engine.git>
- **Submodule path**: `audioengine/`
- **Module namespace**: `com.nerio.audioengine`
- **Native lib**: compiles to `libmatrix_audio.so` (C++17, CMake 3.22.1)
- **Statically links** `libusb` for direct USB control (bypasses Android's `AudioRecord`/`AudioTrack` for USB devices)

### What the engine already does (playback path)

- Parses USB Audio Class **1.0 and 2.0** descriptors (sample rates, channels, bit depth, format bitmaps including DSD)
- Sets up **isochronous OUT transfers** (8 transfers × 8 packets) with feedback endpoint for sync
- Hardware volume control via **UAC Feature Units** (dB Q8.8 format, mute)
- Lock-free ring buffer between Java side (decoded PCM) and native USB writer
- Output sinks: `AudioTrackOutput` (Android native) and `UsbAudioOutput` (libusb)
- Decode pipeline using `MediaCodec` + `MediaExtractor` with gapless playback support
- DSD playback (DSF + DFF parsers, DSD-over-PCM packager)
- EQ processor

### What the engine does **not** yet do (recording path — to be implemented)

The engine is **output-only today**. To make it usable for recording, the following needs to be added to the engine (in the submodule repo, ideally as a PR — or initially as a fork):

1. **Parse UAC IN terminals** in `usb_audio.cpp` — currently only output terminals are walked. UAC descriptors describe both directions, but the parsing code only consumes OUT.
2. **Isochronous IN transfer setup** — currently only `LIBUSB_ENDPOINT_OUT` endpoints are claimed and submitted. Need a symmetric IN path with the same ring-buffer pattern, just reversed.
3. **Capture-side ring buffer + reader** — engine pushes PCM into a buffer; Java side drains it via JNI reads (similar shape to the existing `nativeWriteFloat32` / `nativeWriteInt16` / etc., but `nativeRead*`).
4. **JNI surface for capture**, e.g.:
   - `nativeStartCapture(rate, channels, bits)`
   - `nativeStopCapture()`
   - `nativeReadFloat32(float[] out, int len)` / `nativeReadInt16` / `nativeReadInt24Packed` / `nativeReadInt32`
   - `nativeGetCaptureLatencyFrames()`
5. **A Java `AudioInput` abstraction** mirroring `AudioOutput`, with two impls: `AudioRecordInput` (Android native fallback) and `UsbAudioInput` (libusb capture path).

Files to study in the engine repo to understand the existing pattern (so the IN path mirrors it):
- `audioengine/src/main/cpp/usb_audio.cpp` and `usb_audio.h`
- `audioengine/src/main/cpp/usb_audio_jni.cpp`
- `audioengine/src/main/java/com/nerio/audioengine/UsbAudioNative.java`
- `audioengine/src/main/java/com/nerio/audioengine/UsbAudioOutput.java`
- `audioengine/src/main/java/com/nerio/audioengine/AudioOutput.java` (interface to mirror)
- `audioengine/src/main/cpp/CMakeLists.txt`

### Companion project for reference

The Matrix Player at `C:\Users\incxiuefb\Documents\Files\clone\media_player` is the canonical example of how to integrate the engine into an app. Useful files:

- `app/src/main/java/com/example/media_player/MainActivity.java` — USB device handling, permission flow, runtime lifecycle
- `app/src/main/java/com/example/media_player/MusicService.java` — foreground service shape (we'll want an analogous `RecorderService`)
- `app/src/main/java/com/example/media_player/AppSettings.java` — SharedPreferences wrapper pattern
- `app/build.gradle` — how it consumes `project(':audioengine')`
- `app/src/main/AndroidManifest.xml` — USB intent filter + foreground service declaration

## Recording architecture (suggested for next session)

Not prescriptive — the next session should choose. But a reasonable starting shape:

- **`MainActivity`**
  - Request `RECORD_AUDIO` at startup (runtime permission)
  - Enumerate USB audio devices via `UsbManager.getDeviceList()` and call `requestPermission()` for the selected one
  - On `USB_DEVICE_ATTACHED`, populate a device list UI
  - Show level meters and record/stop controls

- **`RecorderService`** (foreground service, `foregroundServiceType="microphone"`)
  - Holds the actual capture loop and engine handle
  - Mirrors `MusicService` in Matrix Player

- **`RecordingEngine`** (Java wrapper around engine JNI)
  - `start(UsbDevice device, int rate, int channels, int bits, File out)`
  - `stop()`
  - Drains PCM from engine, writes WAV header + samples (or FLAC via libFLAC later)

- **Output**
  - Start with WAV in `MediaStore.Audio.Media` (`RELATIVE_PATH=Music/Recordings/`) so files appear in the system media browser
  - FLAC encoder is a follow-up

## Theme & colors

Copied verbatim from Matrix Player, theme renamed `Theme.MatrixPlayer` → `Theme.AudioRecorder`. Full palette:

| Token | Hex | Use |
|---|---|---|
| `bg_primary` | `#050505` | window background, status/nav bars |
| `bg_surface` | `#0E0E0E` | surfaces above background |
| `bg_elevated` | `#161616` | elevated surfaces (dialogs, sheets) |
| `bg_item` | `#050505` | list-item background |
| `bg_item_playing` | `#081A08` | (player concept — "currently active") |
| `green_primary` | `#00C853` | primary accent |
| `green_bright` | `#00E676` | brighter accent (selected text, seekbar thumb) |
| `green_dim` | `#1B5E20` | primary variant |
| `green_muted` | `#0D3B14` | ripple |
| `green_seekbar_thumb` | `#00E676` | |
| `text_primary` | `#D4D4D4` | body text |
| `text_secondary` | `#686868` | secondary text, unselected tabs |
| `text_playing` | `#00E676` | (player concept — active-track text) |
| `amber_conversion` | `#FFB300` | format-conversion indicator (probably useful here too for "sample-rate converted") |
| `divider` | `#1A1A1A` | dividers |

Font: **CMU Serif** at `res/font/cmu_serif.xml` with 4 weights (regular/bold/italic/bold-italic). The theme sets it as `android:fontFamily` globally.

Theme styles defined: `Theme.AudioRecorder`, `Theme.AudioRecorder.Dialog`, `Widget.AudioRecorder.Tab` (custom TabLayout with 2dp green indicator), `RoundedCorner6`, `RoundedCorner4`.

## Permissions in the manifest

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | required for any audio capture, even via USB device file descriptor |
| `READ_MEDIA_AUDIO` | Android 13+ media access |
| `READ_EXTERNAL_STORAGE` (maxSdk 32) | legacy storage access |
| `WRITE_EXTERNAL_STORAGE` (maxSdk 28) | legacy write access |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | required for background recording on API 34+ |
| `<uses-feature usb.host>` | declares USB host capability (not required, so the app installs on phones without it) |
| `<uses-feature microphone>` | declares mic capability (not required) |

## Conventions

Match Matrix Player where it makes sense:
- **Java only** (no Kotlin, despite the kotlin plugin being in the version catalog — that's just inherited)
- **View binding** for layouts (no Compose)
- **No third-party libraries** for audio, networking, or image loading
- Match the existing dark/green aesthetic in any new UI
- Foreground services for any work that outlives the activity

## Open questions for the user (good to confirm before big changes)

- Output formats: start with WAV only, or include FLAC from day one?
- Should the recorder also support **built-in microphone** capture (via `AudioRecord`) as a fallback when no USB device is connected, or USB-only?
- Bluetooth input was deferred — is that still out of scope?
- Should engine modifications for the IN path be a PR back to `github.com/minervarr/audio_engine` or maintained as a fork?

## Companion project context

Matrix Player CLAUDE.md is at `C:\Users\incxiuefb\Documents\Files\clone\media_player\CLAUDE.md`. Worth a skim for the engine background and the architecture patterns it uses.
