package com.speechtotextrecord

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.io.File
import java.util.UUID

/**
 * Android 13+ foreground audio capture and continuous speech recognition Flutter plugin.
 * All state mutations and Flutter platform channel interactions run on the main thread.
 *
 * Supports continuous speech transcription across utterance boundaries, pauses/silence,
 * and speech service reconnects while keeping single-mic WAV recording active.
 */
class SpeechToTextRecordPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler, PluginRegistry.RequestPermissionsResultListener,
    Application.ActivityLifecycleCallbacks {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 49121
    }

    private lateinit var context: Context
    private lateinit var methods: MethodChannel
    private lateinit var events: EventChannel
    private val main = Handler(Looper.getMainLooper())
    private var sink: EventChannel.EventSink? = null
    private var activity: Activity? = null
    private var binding: ActivityPluginBinding? = null
    private var foreground = false
    private var permissionResult: MethodChannel.Result? = null
    private var capture: CaptureSession? = null
    private var recognizer: SpeechRecognizer? = null
    private var token = 0L
    private var stopping = false
    private var cancelled = false
    private var audioDone = false
    private var audioComplete = true
    private var durationMs = 0L
    private var speechDone = false
    private var speechComplete = false
    private var committed = ""
    private var enablePunctuation = true
    private var currentLocale = "en-IN"
    private var onDeviceMode = false
    private var lastResult: Map<String, Any>? = null
    private val stopResults = mutableListOf<MethodChannel.Result>()
    private val cancelResults = mutableListOf<MethodChannel.Result>()
    private var deadline: Runnable? = null

    override fun onAttachedToEngine(b: FlutterPlugin.FlutterPluginBinding) {
        context = b.applicationContext
        methods = MethodChannel(b.binaryMessenger, "speechtotextrecord/methods")
        events = EventChannel(b.binaryMessenger, "speechtotextrecord/events")
        methods.setMethodCallHandler(this)
        events.setStreamHandler(this)
        (context as Application).registerActivityLifecycleCallbacks(this)
    }

    override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink) {
        sink = eventSink
        emit(mapOf("type" to "ready"))
    }

    override fun onCancel(arguments: Any?) {
        sink = null
    }

    private fun emit(event: Map<String, Any>) {
        sink?.success(event)
    }

    private fun state(value: String) = emit(mapOf("type" to "state", "state" to value))

    private fun issue(code: String, message: String) = emit(
        mapOf("type" to "issue", "code" to code, "message" to message)
    )

    private fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "hasPermission" -> result.success(hasPermission())
            "isRecognitionAvailable" -> result.success(SpeechRecognizer.isRecognitionAvailable(context))
            "requestPermission" -> {
                when {
                    hasPermission() -> result.success(true)
                    activity == null -> result.error("no_activity", "Open the app before requesting permission.", null)
                    permissionResult != null -> result.error("permission_busy", "A permission request is already pending.", null)
                    else -> {
                        permissionResult = result
                        activity!!.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
                    }
                }
            }
            "start" -> start(call, result)
            "stop" -> {
                if (capture == null) {
                    if (lastResult != null) {
                        result.success(lastResult)
                    } else {
                        result.error("not_recording", "There is no active or saved recording in this session.", null)
                    }
                } else if (cancelled) {
                    result.error("cancelled", "This recording is being cancelled.", null)
                } else {
                    stopResults.add(result)
                    beginStop()
                }
            }
            "cancel" -> {
                if (capture == null) {
                    result.success(null)
                } else {
                    cancelled = true
                    cancelResults.add(result)
                    speechDone = true
                    speechComplete = false
                    capture?.disableSpeech()
                    recognizer?.cancel()
                    beginStop()
                    finishIfReady()
                }
            }
            else -> result.notImplemented()
        }
    }

    private fun createRecognizerIntent(inputFd: ParcelFileDescriptor): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLocale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, inputFd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CaptureSession.RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)

            // Extended silence timeouts: prevents aggressive cutoff on pauses in speech
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300000L)

            if (enablePunctuation) {
                putExtra("android.speech.extra.ENABLE_FORMATTING", "android.speech.extra.FORMATTING_OPTIMIZE_QUALITY")
                putExtra("android.speech.extra.HIDE_PARTIAL_TRAILING_PUNCTUATION", false)
                putExtra("android.speech.extra.DICTATION_MODE", true)
                putExtra("android.speech.extra.ENABLE_PUNCTUATION", true)
                putExtra("android.speech.extra.REQUEST_PUNCTUATION", true)
            }
        }
    }

    private fun restartListening() {
        if (capture == null || stopping || cancelled) return
        main.post {
            if (capture == null || stopping || cancelled) return
            try {
                cleanupRecognizer()
                val newRecognizer = if (onDeviceMode && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
                recognizer = newRecognizer
                newRecognizer.setRecognitionListener(listener(token))

                val inputFd = capture?.createSpeechPipe() ?: return@post
                val intent = createRecognizerIntent(inputFd)
                newRecognizer.startListening(intent)
            } catch (e: Exception) {
                if (capture != null && !stopping && !cancelled) {
                    main.postDelayed({ restartListening() }, 500)
                }
            }
        }
    }

    private fun start(call: MethodCall, result: MethodChannel.Result) {
        if (capture != null) {
            result.error("busy", "Stop the current recording before starting a new one.", null)
            return
        }
        if (activity == null || !foreground) {
            result.error("not_foreground", "Start recording while the app is in the foreground.", null)
            return
        }
        if (!hasPermission()) {
            result.error("permission_denied", "Microphone (RECORD_AUDIO) permission is required.", null)
            return
        }
        if (sink == null) {
            result.error("no_listener", "Initialize the event stream before starting recording.", null)
            return
        }
        val locale = call.argument<String>("localeId")?.trim().orEmpty()
        if (locale.isEmpty()) {
            result.error("invalid_locale", "Provide a language tag such as en-US or en-IN.", null)
            return
        }
        val onDevice = call.argument<Boolean>("onDevice") ?: false
        enablePunctuation = call.argument<Boolean>("enablePunctuation") ?: true
        currentLocale = locale
        onDeviceMode = onDevice

        if (onDevice && !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            result.error("on_device_unavailable", "No on-device speech recognizer is available on this device.", null)
            return
        }
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            result.error("stt_unavailable", "No speech recognition service is installed on this device.", null)
            return
        }

        token++
        val id = token
        stopping = false
        cancelled = false
        audioDone = false
        audioComplete = true
        durationMs = 0
        speechDone = false
        speechComplete = false
        committed = ""
        lastResult = null

        state("starting")
        try {
            val customPath = call.argument<String>("audioPath")?.trim()
            val file = if (!customPath.isNullOrEmpty()) {
                val f = File(customPath)
                f.parentFile?.mkdirs()
                f
            } else {
                val directory = File(context.filesDir, "speechtotextrecord")
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create recording directory" }
                File(directory, "${UUID.randomUUID()}.wav")
            }

            val session = CaptureSession(
                file,
                onLevel = { level ->
                    main.post {
                        if (id == token && capture != null && !stopping) {
                            emit(mapOf("type" to "level", "value" to level))
                        }
                    }
                },
                onIssue = { code, message ->
                    main.post {
                        if (id == token && capture != null) {
                            issue(code, message)
                            if (code == "speech_pipe_failed") {
                                if (stopping) failSpeech() else restartListening()
                            }
                        }
                    }
                },
                onAudioDone = { duration, complete ->
                    main.post {
                        if (id == token && capture != null) {
                            durationMs = duration
                            audioComplete = complete
                            audioDone = true
                            if (!stopping) beginStop()
                            finishIfReady()
                        }
                    }
                },
            )
            capture = session
            val inputFd = session.createSpeechPipe()

            recognizer = if (onDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer!!.setRecognitionListener(listener(id))

            val intent = createRecognizerIntent(inputFd)

            // Start hardware capture before asking the speech service to consume the pipe
            session.start()
            recognizer!!.startListening(intent)
            state("recording")
            result.success(null)
        } catch (error: Exception) {
            issue("start_failed", error.message ?: "Could not start recording")
            if (capture != null) {
                speechDone = true
                capture?.disableSpeech()
                if (!capture!!.started) {
                    capture?.abortBeforeStart()
                    capture = null
                    cleanupRecognizer()
                    state("idle")
                } else {
                    beginStop()
                }
            } else {
                state("idle")
            }
            result.error("start_failed", error.message ?: "Could not start recording", null)
        }
    }

    private fun listener(id: Long) = object : RecognitionListener {
        private fun current() = id == token && capture != null && !cancelled
        private fun text(results: Bundle?) =
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

        private fun formatPunctuation(rawText: String): String {
            if (!enablePunctuation || rawText.isEmpty()) return rawText
            var text = rawText
            text = text.replace(Regex("(?i)\\b(period|full stop)\\b"), ".")
                .replace(Regex("(?i)\\bcomma\\b"), ",")
                .replace(Regex("(?i)\\bquestion mark\\b"), "?")
                .replace(Regex("(?i)\\bexclamation (mark|point)\\b"), "!")
                .replace(Regex("(?i)\\bcolon\\b"), ":")
                .replace(Regex("(?i)\\bsemicolon\\b"), ";")
            text = text.replace(Regex("\\s+([.,!?:;])"), "$1")
            text = text.replace(Regex("([.,!?:;])([a-zA-Z])"), "$1 $2")
            val sb = StringBuilder()
            var capitalizeNext = true
            for (i in text.indices) {
                val c = text[i]
                if (capitalizeNext && c.isLetter()) {
                    sb.append(c.uppercaseChar())
                    capitalizeNext = false
                } else {
                    sb.append(c)
                    if (c == '.' || c == '?' || c == '!') {
                        capitalizeNext = true
                    }
                }
            }
            return sb.toString()
        }

        private fun joinSegments(base: String, addition: String): String {
            if (base.isEmpty()) return addition
            if (addition.isEmpty()) return base
            val trimmed = addition.trim()
            if (trimmed.isEmpty()) return base
            return if (trimmed.startsWith(".") || trimmed.startsWith(",") || trimmed.startsWith("?") ||
                trimmed.startsWith("!") || trimmed.startsWith(":") || trimmed.startsWith(";")) {
                base + trimmed
            } else {
                "$base $trimmed"
            }
        }

        private fun transcript(results: Bundle?, final: Boolean) {
            if (!current() || speechDone) return
            val rawSegment = text(results).trim()
            val segment = formatPunctuation(rawSegment)
            if (final && segment.isNotEmpty()) {
                committed = joinSegments(committed, segment)
            }
            val display = if (final) committed else joinSegments(committed, segment)
            emit(mapOf("type" to "transcript", "text" to display, "isFinal" to final))
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) = transcript(partialResults, false)
        override fun onSegmentResults(segmentResults: Bundle) = transcript(segmentResults, true)

        override fun onResults(results: Bundle?) {
            if (!current() || speechDone) return
            transcript(results, true)
            if (stopping) {
                completeSpeech()
            } else {
                // Continuous recording: restart recognizer for the next phrase/sentence
                restartListening()
            }
        }

        override fun onEndOfSegmentedSession() {
            if (!current() || speechDone) return
            if (stopping) {
                completeSpeech()
            } else {
                restartListening()
            }
        }

        override fun onError(error: Int) {
            if (!current() || speechDone) return

            when (error) {
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT, // 6: Pause / no speech in interval
                SpeechRecognizer.ERROR_NO_MATCH -> {  // 7: No recognized words during pause
                    // Normal pauses during speaking: seamlessly restart listening if recording
                    if (!stopping && !cancelled) {
                        restartListening()
                    } else {
                        completeSpeech()
                    }
                }
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED, // 11
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> { // 1
                    if (!stopping && !cancelled) {
                        issue("speech_network_retry", "Speech service timed out. Reconnecting...")
                        main.postDelayed({ if (!stopping && !cancelled) restartListening() }, 400)
                    } else {
                        failSpeech()
                    }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, // 8
                SpeechRecognizer.ERROR_CLIENT -> { // 5
                    if (!stopping && !cancelled) {
                        main.postDelayed({ if (!stopping && !cancelled) restartListening() }, 300)
                    } else {
                        failSpeech()
                    }
                }
                else -> {
                    issue("speech_error_$error", "Speech recognition notice ($error). Audio recording continues.")
                    if (stopping) {
                        failSpeech()
                    } else {
                        main.postDelayed({ if (!stopping && !cancelled) restartListening() }, 500)
                    }
                }
            }
        }
    }

    private fun completeSpeech() {
        speechDone = true
        speechComplete = true
        capture?.disableSpeech()
        finishIfReady()
    }

    private fun failSpeech() {
        speechDone = true
        speechComplete = false
        capture?.disableSpeech()
        runCatching { recognizer?.cancel() }
        finishIfReady()
    }

    private fun beginStop() {
        if (stopping) return
        stopping = true
        state("stopping")
        capture?.stop()
        val id = token
        val timeout = Runnable {
            if (id == token && capture != null && !speechDone) {
                issue("speech_finalize_timeout", "Final transcript did not arrive within timeout. Finalizing recording.")
                failSpeech()
            }
        }
        deadline = timeout
        main.postDelayed(timeout, 4000)
        finishIfReady()
    }

    private fun finishIfReady() {
        if (!stopping || !audioDone || !speechDone) return
        deadline?.let { main.removeCallbacks(it) }
        deadline = null
        val session = capture ?: return
        capture = null
        session.disableSpeech()
        session.closeInput()
        cleanupRecognizer()

        if (cancelled) {
            val deleted = !session.file.exists() || session.file.delete()
            lastResult = null
            stopResults.forEach { it.error("cancelled", "Recording was cancelled.", null) }
            cancelResults.forEach {
                if (deleted) {
                    it.success(null)
                } else {
                    it.error("delete_failed", "Could not delete ${session.file.path}", null)
                }
            }
        } else {
            val saved = mapOf<String, Any>(
                "audioPath" to session.file.absolutePath,
                "durationMs" to durationMs,
                "transcript" to committed,
                "speechComplete" to speechComplete,
                "audioComplete" to audioComplete,
            )
            lastResult = saved
            emit(saved + ("type" to "stopped"))
            stopResults.forEach { it.success(saved) }
        }
        stopResults.clear()
        cancelResults.clear()
        state("idle")
    }

    private fun cleanupRecognizer() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    override fun onAttachedToActivity(b: ActivityPluginBinding) {
        binding = b
        activity = b.activity
        foreground = activity?.isFinishing == false && activity?.isDestroyed == false
        b.addRequestPermissionsResultListener(this)
    }

    private fun detachActivity() {
        binding?.removeRequestPermissionsResultListener(this)
        permissionResult?.error("activity_detached", "Activity detached during permission request.", null)
        permissionResult = null
        if (capture != null) beginStop()
        foreground = false
        activity = null
        binding = null
    }

    override fun onDetachedFromActivityForConfigChanges() = detachActivity()
    override fun onReattachedToActivityForConfigChanges(b: ActivityPluginBinding) = onAttachedToActivity(b)
    override fun onDetachedFromActivity() = detachActivity()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode != PERMISSION_REQUEST_CODE) return false
        permissionResult?.success(hasPermission())
        permissionResult = null
        return true
    }

    override fun onActivityResumed(a: Activity) {
        if (a == activity) foreground = true
    }

    override fun onActivityPaused(a: Activity) {
        if (a == activity) {
            foreground = false
            if (capture != null) {
                issue("app_paused", "Recording stopped because the app left the foreground.")
                beginStop()
            }
        }
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}

    override fun onDetachedFromEngine(b: FlutterPlugin.FlutterPluginBinding) {
        detachActivity()
        methods.setMethodCallHandler(null)
        events.setStreamHandler(null)
        sink = null
        (context as Application).unregisterActivityLifecycleCallbacks(this)
        if (capture != null) {
            speechDone = true
            capture?.disableSpeech()
            beginStop()
            finishIfReady()
        }
        cleanupRecognizer()
    }
}
