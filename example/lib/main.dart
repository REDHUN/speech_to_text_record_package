import 'dart:async';
import 'package:flutter/material.dart';
import 'package:speechtotextrecord/speechtotextrecord.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const SpeechToTextRecordExampleApp());
}

class SpeechToTextRecordExampleApp extends StatelessWidget {
  const SpeechToTextRecordExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Speech to Text & WAV Recorder',
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.deepPurple,
          brightness: Brightness.light,
        ),
      ),
      darkTheme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.deepPurple,
          brightness: Brightness.dark,
        ),
      ),
      home: const RecorderScreen(),
    );
  }
}

class RecorderScreen extends StatefulWidget {
  const RecorderScreen({super.key});

  @override
  State<RecorderScreen> createState() => _RecorderScreenState();
}

class _RecorderScreenState extends State<RecorderScreen> {
  final SpeechToTextRecord _recorder = SpeechToTextRecord.instance;
  StreamSubscription<RecorderEvent>? _subscription;

  String _state = 'idle';
  String _liveTranscript = '';
  double _audioLevel = 0.0;
  String? _lastIssue;
  RecordingResult? _lastResult;

  String _selectedLocale = 'en-IN';
  bool _onDeviceOnly = false;
  bool _hasPermission = false;
  bool _sttAvailable = false;

  final List<Map<String, String>> _locales = const [
    {'label': 'English (India)', 'id': 'en-IN'},
    {'label': 'English (US)', 'id': 'en-US'},
    {'label': 'Malayalam (India)', 'id': 'ml-IN'},
    {'label': 'Hindi (India)', 'id': 'hi-IN'},
    {'label': 'Spanish (Spain)', 'id': 'es-ES'},
  ];

  @override
  void initState() {
    super.initState();
    _checkStatus();
    _setupEventSubscription();
  }

  Future<void> _checkStatus() async {
    final hasPerm = await _recorder.hasPermission();
    final available = await _recorder.isRecognitionAvailable();
    if (mounted) {
      setState(() {
        _hasPermission = hasPerm;
        _sttAvailable = available;
      });
    }
  }

  void _setupEventSubscription() {
    _subscription = _recorder.events.listen(
      (event) {
        if (!mounted) return;
        setState(() {
          switch (event) {
            case RecorderStateChanged(:final state):
              _state = state;
              if (state == 'idle') {
                _audioLevel = 0.0;
              }
            case TranscriptChanged(:final text):
              _liveTranscript = text;
            case AudioLevel(:final value):
              _audioLevel = value;
            case RecorderIssue(:final code, :final message):
              _lastIssue = '$code: $message';
            case RecordingStopped(:final result):
              _lastResult = result;
          }
        });
      },
      onError: (Object error) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Recorder error: $error')),
        );
      },
    );
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }

  Future<void> _startRecording() async {
    try {
      if (!_hasPermission) {
        final granted = await _recorder.requestPermission();
        setState(() => _hasPermission = granted);
        if (!granted) {
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Microphone permission denied.')),
            );
          }
          return;
        }
      }

      setState(() {
        _liveTranscript = '';
        _lastIssue = null;
        _lastResult = null;
      });

      await _recorder.start(
        localeId: _selectedLocale,
        onDevice: _onDeviceOnly,
      );
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to start: $e')),
        );
      }
    }
  }

  Future<void> _stopRecording() async {
    try {
      final result = await _recorder.stop();
      if (mounted) {
        setState(() {
          _lastResult = result;
        });
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to stop: $e')),
        );
      }
    }
  }

  Future<void> _cancelRecording() async {
    try {
      await _recorder.cancel();
      if (mounted) {
        setState(() {
          _liveTranscript = '';
          _audioLevel = 0.0;
        });
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to cancel: $e')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isRecording = _state == 'recording';
    final isBusy = _state == 'starting' || _state == 'stopping';

    return Scaffold(
      appBar: AppBar(
        title: const Text('SpeechToTextRecord Demo'),
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Status & Configuration Card
            Card(
              elevation: 2,
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Configuration', style: theme.textTheme.titleMedium),
                    const SizedBox(height: 12),
                    DropdownButtonFormField<String>(
                      value: _selectedLocale,
                      decoration: const InputDecoration(
                        labelText: 'Recognition Language',
                        border: OutlineInputBorder(),
                      ),
                      items: _locales.map((locale) {
                        return DropdownMenuItem<String>(
                          value: locale['id'],
                          child: Text('${locale['label']} (${locale['id']})'),
                        );
                      }).toList(),
                      onChanged: isRecording || isBusy
                          ? null
                          : (value) {
                              if (value != null) {
                                setState(() => _selectedLocale = value);
                              }
                            },
                    ),
                    const SizedBox(height: 12),
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('On-device recognition only'),
                      subtitle: const Text('Requires offline model support on device'),
                      value: _onDeviceOnly,
                      onChanged: isRecording || isBusy
                          ? null
                          : (val) => setState(() => _onDeviceOnly = val),
                    ),
                    const Divider(),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('Service Available: ${_sttAvailable ? "Yes" : "No"}'),
                        Text('Mic Permission: ${_hasPermission ? "Granted" : "Required"}'),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text('Recorder State: $_state',
                        style: TextStyle(
                          fontWeight: FontWeight.bold,
                          color: isRecording ? Colors.green : Colors.grey[700],
                        )),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Live Audio Level & Wave Indicator
            Card(
              elevation: 2,
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Audio Level (Mic Peak)', style: theme.textTheme.titleMedium),
                    const SizedBox(height: 8),
                    LinearProgressIndicator(
                      value: _audioLevel,
                      minHeight: 12,
                      borderRadius: BorderRadius.circular(6),
                      backgroundColor: theme.colorScheme.surfaceVariant,
                      valueColor: AlwaysStoppedAnimation<Color>(
                        _audioLevel > 0.8 ? Colors.red : theme.colorScheme.primary,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Live Transcript Card
            Card(
              elevation: 2,
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Text('Live Transcript', style: theme.textTheme.titleMedium),
                        if (isRecording) ...[
                          const SizedBox(width: 8),
                          const SizedBox(
                            width: 14,
                            height: 14,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 8),
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: theme.colorScheme.surfaceVariant.withOpacity(0.4),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(
                        _liveTranscript.isEmpty
                            ? (isRecording ? 'Listening...' : 'Transcript will appear here as you speak.')
                            : _liveTranscript,
                        style: TextStyle(
                          fontSize: 16,
                          fontStyle: _liveTranscript.isEmpty ? FontStyle.italic : FontStyle.normal,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),

            // Action Buttons
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    style: ElevatedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 14),
                      backgroundColor: isRecording ? Colors.orange : theme.colorScheme.primary,
                      foregroundColor: Colors.white,
                    ),
                    onPressed: isBusy
                        ? null
                        : (isRecording ? _stopRecording : _startRecording),
                    icon: Icon(isRecording ? Icons.stop : Icons.mic),
                    label: Text(isRecording ? 'Stop & Save' : 'Start Recording'),
                  ),
                ),
                if (isRecording) ...[
                  const SizedBox(width: 12),
                  OutlinedButton.icon(
                    style: OutlinedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 14),
                      foregroundColor: Colors.red,
                    ),
                    onPressed: isBusy ? null : _cancelRecording,
                    icon: const Icon(Icons.cancel),
                    label: const Text('Cancel'),
                  ),
                ],
              ],
            ),
            const SizedBox(height: 16),

            // Issues Banner
            if (_lastIssue != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.amber.shade100,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.amber.shade700),
                ),
                child: Row(
                  children: [
                    const Icon(Icons.warning_amber, color: Colors.orange),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        _lastIssue!,
                        style: const TextStyle(color: Colors.black87),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
            ],

            // Final Result Card
            if (_lastResult != null) ...[
              Card(
                color: theme.colorScheme.primaryContainer,
                elevation: 3,
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Saved Recording Result',
                          style: theme.textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                            color: theme.colorScheme.onPrimaryContainer,
                          )),
                      const SizedBox(height: 8),
                      Text('Duration: ${_lastResult!.duration.inSeconds} seconds (${_lastResult!.duration.inMilliseconds} ms)'),
                      const SizedBox(height: 4),
                      Text('Audio Complete: ${_lastResult!.audioComplete}'),
                      const SizedBox(height: 4),
                      Text('Speech Complete: ${_lastResult!.speechComplete}'),
                      const SizedBox(height: 8),
                      const Text('Final Transcript:', style: TextStyle(fontWeight: FontWeight.bold)),
                      Text(
                        _lastResult!.transcript.isEmpty ? '(No words recognized)' : _lastResult!.transcript,
                        style: const TextStyle(fontSize: 15),
                      ),
                      const SizedBox(height: 8),
                      const Text('WAV File Path:', style: TextStyle(fontWeight: FontWeight.bold)),
                      SelectableText(
                        _lastResult!.audioPath,
                        style: TextStyle(fontSize: 12, color: theme.colorScheme.onPrimaryContainer),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
