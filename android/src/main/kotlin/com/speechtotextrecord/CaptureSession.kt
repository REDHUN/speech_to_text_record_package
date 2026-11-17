package com.speechtotextrecord

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * One AudioRecord owns the mic; disk writing is independent of speech pipe backpressure.
 * Supports dynamic speech pipe recreation to allow continuous SpeechRecognizer restarts
 * across silence pauses, utterance boundaries, and network reconnections.
 */
internal class CaptureSession(
    val file: File,
    private val onLevel: (Double) -> Unit,
    private val onIssue: (String, String) -> Unit,
    private val onAudioDone: (Long, Boolean) -> Unit,
) {
    companion object {
        const val RATE = 16000
    }

    private val running = AtomicBoolean(false)
    private val feeding = AtomicBoolean(true)
    private val queue = ArrayBlockingQueue<ByteArray>(256) // ~5.12 seconds of PCM buffer

    @Volatile
    private var activePipe: Array<ParcelFileDescriptor>? = null

    private var audio: AudioRecord? = null
    private var wav: WavWriter? = null
    var started = false
        private set

    /**
     * Creates a new non-blocking pipe for a SpeechRecognizer session.
     * Closes any previous pipe descriptors safely.
     * Returns the read end of the pipe to be passed as EXTRA_AUDIO_SOURCE.
     */
    @Synchronized
    fun createSpeechPipe(): ParcelFileDescriptor {
        closeSpeechPipe()
        val newPipe = ParcelFileDescriptor.createPipe()
        val flags = Os.fcntlInt(newPipe[1].fileDescriptor, OsConstants.F_GETFL, 0)
        Os.fcntlInt(newPipe[1].fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        activePipe = newPipe
        return newPipe[0]
    }

    @Synchronized
    fun closeSpeechPipe() {
        val old = activePipe
        activePipe = null
        if (old != null) {
            runCatching { old[0].close() }
            runCatching { old[1].close() }
        }
    }

    fun start() {
        try {
            if (activePipe == null) {
                createSpeechPipe()
            }
            val minimum = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimum > 0) { "16 kHz mono PCM recording is unavailable" }
            val format = AudioFormat.Builder().setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            audio = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format).setBufferSizeInBytes(maxOf(minimum * 2, 6400)).build()
            check(audio!!.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            wav = WavWriter(file, RATE)
            audio!!.startRecording()
            check(audio!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            running.set(true)
            started = true
            Thread(::feedSpeech, "stt-pipe").start()
            Thread(::capture, "stt-capture").start()
        } catch (error: Exception) {
            running.set(false)
            feeding.set(false)
            runCatching { audio?.release() }
            runCatching { wav?.close() }
            closeSpeechPipe()
            file.delete()
            throw error
        }
    }

    fun stop() {
        running.set(false)
    }

    fun disableSpeech() {
        feeding.set(false)
        queue.clear()
        closeSpeechPipe()
    }

    fun closeInput() {
        closeSpeechPipe()
    }

    fun abortBeforeStart() {
        disableSpeech()
        closeSpeechPipe()
        file.delete()
    }

    private fun speechFailure(message: String) {
        if (feeding.getAndSet(false)) {
            queue.clear()
            closeSpeechPipe()
            onIssue("speech_pipe_failed", "$message Audio recording continues.")
        }
    }

    private fun capture() {
        val bytes = ByteArray(640) // 20ms PCM16 mono at 16kHz
        var success = true
        var lastLevel = 0L
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            while (running.get()) {
                val count = audio!!.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                if (!running.get()) break
                if (count < 0) error("AudioRecord read failed ($count)")
                if (count == 0) continue

                // 1. Write unconditionally to WAV on disk
                wav!!.write(bytes, count)

                // 2. Feed speech pipe buffer (if full, discard oldest chunk rather than failing)
                if (feeding.get()) {
                    if (!queue.offer(bytes.copyOf(count))) {
                        queue.poll()
                        queue.offer(bytes.copyOf(count))
                    }
                }

                // 3. Throttle volume level updates to ~10 Hz
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevel >= 100) {
                    var peak = 0
                    for (i in 0 until count - 1 step 2) {
                        val value = ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                        peak = maxOf(peak, abs(value))
                    }
                    onLevel((peak / 32768.0).coerceIn(0.0, 1.0))
                    lastLevel = now
                }
            }
        } catch (error: Exception) {
            success = false
            onIssue("audio_capture_failed", error.message ?: "Audio capture failed")
        } finally {
            running.set(false)
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            try {
                wav?.close()
            } catch (error: Exception) {
                success = false
                onIssue("wav_finalize_failed", error.message ?: "Could not finalize WAV")
            }
            onAudioDone(wav?.durationMs() ?: 0, success)
        }
    }

    private fun feedSpeech() {
        try {
            while (feeding.get() && (running.get() || queue.isNotEmpty())) {
                val p = activePipe
                if (p == null) {
                    // Recognizer session ended, restarting, or waiting for a new pipe
                    Thread.sleep(20)
                    continue
                }
                val chunk = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                var offset = 0
                var lastProgress = SystemClock.elapsedRealtime()

                while (offset < chunk.size && feeding.get() && activePipe === p) {
                    try {
                        val written = Os.write(p[1].fileDescriptor, chunk, offset, chunk.size - offset)
                        if (written > 0) {
                            offset += written
                            lastProgress = SystemClock.elapsedRealtime()
                        }
                    } catch (error: ErrnoException) {
                        if (error.errno == OsConstants.EPIPE || error.errno == OsConstants.EBADF) {
                            // Remote reader closed pipe when its session finished or before restarting.
                            // Close this pipe descriptor and await the next session pipe.
                            closeSpeechPipe()
                            break
                        }
                        if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) {
                            closeSpeechPipe()
                            break
                        }
                        if (SystemClock.elapsedRealtime() - lastProgress > 5000) {
                            closeSpeechPipe()
                            break
                        }
                        Thread.sleep(5)
                    }
                }
            }
        } catch (error: Exception) {
            speechFailure(error.message ?: "Speech pipe failed")
        } finally {
            closeSpeechPipe()
            queue.clear()
        }
    }
}
