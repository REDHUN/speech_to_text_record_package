# speechtotextrecord

Android-first Flutter plugin for simultaneous WAV audio recording and live speech-to-text recognition using a single native microphone capture.

> **Platform Support**: Android 13+ (API level 33+) only. iOS is intentionally not supported or registered.

---

## Why Single Capture?

On Android, multiple applications or components cannot reliably share access to `AudioSource.MIC` simultaneously. If an app attempts to run an audio recorder and a speech recognizer at the same time, either one will fail to initialize or the other will be preempted.

`speechtotextrecord` solves this by:
1. Owning the hardware microphone once using Android's low-level `AudioRecord` API (16 kHz, 16-bit mono PCM).
2. Streaming raw PCM audio directly to a non-buffering `WavWriter` on disk.
3. Simultaneously duplicating and writing audio chunks into a non-blocking Linux pipe (`ParcelFileDescriptor.createPipe()`).
4. Supplying the read end of that pipe to Android's `SpeechRecognizer` via `RecognizerIntent.EXTRA_AUDIO_SOURCE`.
5. Isolating speech engine delays using a bounded queue: if the speech recognition engine lags or encounters a network error, speech writing gracefully decouples while disk audio capture continues without dropping audio frames.

---

## Features

- **True Single-Mic Capture**: Records a standard 16 kHz mono WAV file while transcribing in real-time.
- **Real-Time Stream Events**:
  - `TranscriptChanged`: Live transcription with partial updates and committed segments.
  - `AudioLevel`: Normalized PCM peak volume amplitude (0.0 to 1.0) ~10 times per second for visualizers.
  - `RecorderStateChanged`: State tracking (`starting`, `recording`, `stopping`, `idle`).
  - `RecorderIssue`: Warnings and non-fatal native issues (e.g. speech pipe full, network loss).
  - `RecordingStopped`: Final session details and path to the completed `.wav` file.
- **Graceful Lifecycle Management**: Automatically stops and finalizes audio when the app pauses or leaves the foreground.
- **Custom Destination Support**: Choose where to store the `.wav` file or use app-private storage.
- **On-Device / Offline Mode**: Toggle on-device speech models if supported by the Android device.

---

## Installation

Add `speechtotextrecord` to your `pubspec.yaml`:

```yaml
dependencies:
  speechtotextrecord:
    path: ../speechtotextrecord # Or your git/local path
```

---

## Android Configuration

In your host application's `android/app/build.gradle` (or `build.gradle.kts`), ensure `minSdk` is at least 33:

```groovy
android {
    defaultConfig {
        minSdk = 33
    }
}
```

The plugin automatically declares the required permissions and package visibility queries in its `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<queries>
    <intent>
        <action android:name="android.speech.RecognitionService" />
    </intent>
</queries>
```

---

## Usage Guide

### 1. Initialize and Check Permissions

```dart
import 'dart:async';
import 'package:speechtotextrecord/speechtotextrecord.dart';

final recorder = SpeechToTextRecord.instance;

// Check and request microphone permission
bool hasPerm = await recorder.hasPermission();
if (!hasPerm) {
  hasPerm = await recorder.requestPermission();
}

// Verify that speech recognition is available on the device
bool available = await recorder.isRecognitionAvailable();
```

### 2. Listen to Live Events

```dart
StreamSubscription<RecorderEvent>? subscription;

void startListening() {
  subscription = recorder.events.listen((event) {
    switch (event) {
      case TranscriptChanged(:final text, :final isFinal):
        print('Transcript: $text (committed: $isFinal)');

      case AudioLevel(:final value):
        // Value between 0.0 and 1.0 for audio wave/meter UI
        print('Volume level: $value');

      case RecorderStateChanged(:final state):
        print('State: $state'); // starting, recording, stopping, idle

      case RecorderIssue(:final code, :final message):
        print('Issue [$code]: $message');

      case RecordingStopped(:final result):
        print('Saved to: ${result.audioPath}');
    }
  });
}
```

### 3. Start Recording

```dart
await recorder.start(
  localeId: 'en-US', // Or 'en-IN', 'hi-IN', 'ml-IN', etc.
  onDevice: false,   // Set to true if on-device model is installed
  // audioPath: '/custom/path/to/my_recording.wav', // Optional custom path
);
```

### 4. Stop and Finalize

```dart
final RecordingResult result = await recorder.stop();

print('Audio file path: ${result.audioPath}');
print('Duration: ${result.duration.inSeconds} seconds');
print('Final transcript: ${result.transcript}');
print('Audio fully written: ${result.audioComplete}');
print('Speech completed normally: ${result.speechComplete}');
```

### 5. Cancel Recording

If you want to discard the active recording and delete the file:

```dart
await recorder.cancel();
```

---

## Model Reference

### `RecordingResult`
| Field | Type | Description |
|---|---|---|
| `audioPath` | `String` | Absolute path to the finalized `.wav` audio file. |
| `duration` | `Duration` | Recorded duration calculated directly from sample byte counts. |
| `transcript` | `String` | Fully committed speech recognition transcript. |
| `audioComplete`| `bool` | True if the audio file was written and finalized successfully. |
| `speechComplete`| `bool`| True if the speech recognizer completed normally before timeout. |

---

## Testing

Unit tests for event mapping and models can be run with:

```bash
flutter test
```

Verification of the WAV writer logic independent of Android:

```bash
java -m jdk.compiler/com.sun.tools.javac.Main -d .wav-check android/src/main/java/com/speechtotextrecord/WavWriter.java tools/WavWriterCheck.java
java -cp .wav-check com.speechtotextrecord.WavWriterCheck .wav-check/audio
```
