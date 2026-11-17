import 'dart:async';

import 'package:flutter/services.dart';

import 'models.dart';

/// Single-capture WAV audio recorder with live speech recognition events for Android.
final class SpeechToTextRecord {
  SpeechToTextRecord._();
  static final SpeechToTextRecord instance = SpeechToTextRecord._();

  static const MethodChannel _methods = MethodChannel('speechtotextrecord/methods');
  static const EventChannel _nativeEvents = EventChannel('speechtotextrecord/events');

  final StreamController<RecorderEvent> _events = StreamController<RecorderEvent>.broadcast();
  StreamSubscription<dynamic>? _subscription;
  Future<void>? _initialization;

  /// Broadcast stream of real-time recorder events:
  /// - [RecorderStateChanged] (starting, recording, stopping, idle)
  /// - [TranscriptChanged] (live partial and committed speech transcript)
  /// - [AudioLevel] (PCM peak audio amplitude in [0.0, 1.0])
  /// - [RecorderIssue] (native warnings or recoverable errors)
  /// - [RecordingStopped] (session finalized with [RecordingResult])
  Stream<RecorderEvent> get events => _events.stream;

  /// Subscribe to [events] before starting if you need every event from the onset.
  Future<void> initialize() => _initialization ??= _connect();

  Future<void> _connect() async {
    final ready = Completer<void>();
    _subscription = _nativeEvents.receiveBroadcastStream().listen((dynamic raw) {
      final map = Map<Object?, Object?>.from(raw as Map);
      if (map['type'] == 'ready') {
        if (!ready.isCompleted) ready.complete();
        return;
      }
      try {
        _events.add(RecorderEvent.fromMap(map));
      } catch (error, stack) {
        _events.addError(error, stack);
      }
    }, onError: (Object error, StackTrace stack) {
      if (!ready.isCompleted) {
        ready.completeError(error, stack);
      } else {
        _events.addError(error, stack);
      }
    });

    try {
      await ready.future.timeout(const Duration(seconds: 5));
    } catch (_) {
      await _subscription?.cancel();
      _subscription = null;
      _initialization = null;
      rethrow;
    }
  }

  /// Checks if `android.permission.RECORD_AUDIO` is granted.
  Future<bool> hasPermission() async =>
      await _methods.invokeMethod<bool>('hasPermission') ?? false;

  /// Requests `android.permission.RECORD_AUDIO` from the active Activity.
  /// Must be called when the app is in the foreground.
  Future<bool> requestPermission() async =>
      await _methods.invokeMethod<bool>('requestPermission') ?? false;

  /// Returns true if an Android Speech Recognition service is available on the device.
  Future<bool> isRecognitionAvailable() async =>
      await _methods.invokeMethod<bool>('isRecognitionAvailable') ?? false;

  /// Starts recording audio and transcribing speech.
  ///
  /// - Requires Android 13+ (API 33+) and foreground activity focus.
  /// - [localeId] specifies the BCP-47 language tag (e.g. `'en-US'`, `'en-IN'`, `'hi-IN'`).
  /// - [onDevice] requests on-device speech recognition if supported by the OS.
  /// - [audioPath] optional custom absolute path for the output `.wav` file.
  Future<void> start({
    String localeId = 'en-US',
    bool onDevice = false,
    String? audioPath,
  }) async {
    if (localeId.trim().isEmpty) {
      throw ArgumentError.value(localeId, 'localeId', 'Cannot be empty');
    }
    await initialize();
    await _methods.invokeMethod<void>('start', {
      'localeId': localeId,
      'onDevice': onDevice,
      if (audioPath != null) 'audioPath': audioPath,
    });
  }

  /// Stops capture, finalizes the WAV file header, and awaits speech finalization.
  ///
  /// Returns a [RecordingResult] containing the audio file path, duration, and transcript.
  /// Safe to call multiple times; returns the completed session result.
  Future<RecordingResult> stop() async {
    final map = await _methods.invokeMapMethod<Object?, Object?>('stop');
    if (map == null) throw StateError('Native recorder returned no result.');
    return RecordingResult.fromMap(map);
  }

  /// Cancels recording and deletes the current session WAV file.
  Future<void> cancel() => _methods.invokeMethod<void>('cancel');
}
