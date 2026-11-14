package com.example.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioInputStreamer(
    private val context: Context? = null,
    private val onAudioChunk: (base64Audio: String, audioLevel: Float) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var autoGainControl: AutomaticGainControl? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var isMuted: Boolean = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording.get()) return true

        if (context != null) {
            val permissionGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!permissionGranted) {
                onError("মাইক্রোফোন ব্যবহারের অনুমতি প্রয়োজন (RECORD_AUDIO)")
                return false
            }
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize <= 0) {
            onError("মাইক্রোফোন বাফার সাইজ নির্ধারণে ত্রুটি")
            return false
        }

        val bufferSize = (minBufferSize * 2).coerceAtLeast(3200)

        try {
            var candidateRecord: AudioRecord? = null
            val audioSources = intArrayOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.DEFAULT
            )

            for (source in audioSources) {
                try {
                    val record = AudioRecord(
                        source,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                    )
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        candidateRecord = record
                        break
                    } else {
                        record.release()
                    }
                } catch (e: Throwable) {
                    // Try next source
                }
            }

            if (candidateRecord == null || candidateRecord.state != AudioRecord.STATE_INITIALIZED) {
                candidateRecord?.release()
                onError("মাইক্রোফোন আরম্ভ করা সম্ভব হয়নি (ডিভাইসে অডিও ট্র্যাক অনুপলব্ধ)")
                return false
            }

            audioRecord = candidateRecord

            val sessionId = audioRecord?.audioSessionId ?: 0
            if (sessionId != 0) {
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                    }
                } catch (e: Throwable) {}

                try {
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                    }
                } catch (e: Throwable) {}

                try {
                    if (AutomaticGainControl.isAvailable()) {
                        autoGainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                    }
                } catch (e: Throwable) {}
            }

            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                onError("মাইক্রোফোন রেকর্ডিং শুরু করতে পারেনি")
                stop()
                return false
            }
            isRecording.set(true)

            recordingJob = scope.launch {
                val buffer = ByteArray(3200) // 100ms chunk at 16kHz 16-bit mono
                while (isRecording.get() && isActive) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readBytes > 0) {
                        if (isMuted) {
                            onAudioChunk("", 0f)
                            delay(50)
                            continue
                        }
                        val chunk = buffer.copyOf(readBytes)
                        val level = calculateRmsLevel(chunk)
                        val base64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                        onAudioChunk(base64, level)
                    } else if (readBytes < 0) {
                        delay(20)
                    }
                }
            }
            return true
        } catch (e: SecurityException) {
            onError("মাইক্রোফোন ব্যবহারের অনুমতি প্রয়োজন")
            stop()
            return false
        } catch (e: Exception) {
            onError("অডিও রেকর্ডিং ত্রুটি: ${e.localizedMessage}")
            stop()
            return false
        }
    }

    fun stop() {
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null
        try {
            echoCanceler?.release()
            echoCanceler = null
            noiseSuppressor?.release()
            noiseSuppressor = null
            autoGainControl?.release()
            autoGainControl = null

            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioRecord = null
    }

    private fun calculateRmsLevel(bytes: ByteArray): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i < bytes.size - 1) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
            sum += sample * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count)
        val normalized = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
        return normalized
    }
}
