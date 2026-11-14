package com.example.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlin.math.tanh

class AudioOutputPlayer(
    private val onPlaybackLevel: (Float) -> Unit,
    private val onPlaybackFinished: () -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // Pre-buffer threshold: ~200ms of audio (24000 * 2 bytes/sec = 48000 B/s => ~9600 bytes)
        // Accumulating ~9.6KB before starting playback prevents network jitter / choppy stutter
        private const val PRE_BUFFER_BYTES = 9600
        // Mid-turn recovery cushion if network delays cause momentary starve (~100ms)
        private const val RECOVERY_BUFFER_BYTES = 4800
    }

    private var audioTrack: AudioTrack? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val isPlaying = AtomicBoolean(false)
    private val isTurnComplete = AtomicBoolean(false)
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var volumeGain: Float = 1.0f // Default 1.0f for pure, unclipped studio clarity

    private var lastLevelUpdateTime = 0L

    fun start() {
        if (isPlaying.get() && audioTrack != null) return

        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = (minBufferSize * 6).coerceAtLeast(48000)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        try {
            audioTrack = AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            audioTrack?.setVolume(1.0f)
            isPlaying.set(true)
            isTurnComplete.set(false)

            playbackJob = scope.launch(Dispatchers.IO) {
                val preBufferList = ArrayList<ByteArray>()
                var bufferedBytes = 0
                var isPreBuffering = true
                var targetThreshold = PRE_BUFFER_BYTES

                var turnStartHeadPosition = 0L
                var totalTurnFramesWritten = 0L
                var inTurn = false

                while (isPlaying.get() && isActive) {
                    val rawChunk = audioQueue.poll(40, TimeUnit.MILLISECONDS)

                    if (rawChunk != null && rawChunk.isNotEmpty()) {
                        val track = audioTrack ?: break

                        if (!inTurn) {
                            inTurn = true
                            turnStartHeadPosition = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                            totalTurnFramesWritten = 0L
                            isPreBuffering = true
                            targetThreshold = PRE_BUFFER_BYTES
                            preBufferList.clear()
                            bufferedBytes = 0
                        }

                        if (isPreBuffering) {
                            preBufferList.add(rawChunk)
                            bufferedBytes += rawChunk.size

                            if (bufferedBytes >= targetThreshold || isTurnComplete.get()) {
                                // Flush jitter buffer into AudioTrack
                                for (chunk in preBufferList) {
                                    val processed = applySafeGainAndLimiter(chunk, volumeGain)
                                    val written = track.write(processed, 0, processed.size, AudioTrack.WRITE_BLOCKING)
                                    if (written > 0) {
                                        totalTurnFramesWritten += (written / 2)
                                    }
                                }
                                preBufferList.clear()
                                bufferedBytes = 0
                                isPreBuffering = false

                                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    track.play()
                                }

                                reportLevelThrottled(rawChunk)
                            }
                        } else {
                            val processed = applySafeGainAndLimiter(rawChunk, volumeGain)
                            val written = track.write(processed, 0, processed.size, AudioTrack.WRITE_BLOCKING)
                            if (written > 0) {
                                totalTurnFramesWritten += (written / 2)
                            }
                            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                track.play()
                            }
                            reportLevelThrottled(processed)
                        }
                    } else {
                        // audioQueue is empty
                        val track = audioTrack ?: break

                        if (isTurnComplete.get() && inTurn) {
                            // If any chunks remain in preBufferList, flush them now
                            if (preBufferList.isNotEmpty()) {
                                for (chunk in preBufferList) {
                                    val processed = applySafeGainAndLimiter(chunk, volumeGain)
                                    val written = track.write(processed, 0, processed.size, AudioTrack.WRITE_BLOCKING)
                                    if (written > 0) {
                                        totalTurnFramesWritten += (written / 2)
                                    }
                                }
                                preBufferList.clear()
                                bufferedBytes = 0
                                isPreBuffering = false
                                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    track.play()
                                }
                            }

                            // Precise Hardware Buffer Drain:
                            // Wait until playbackHeadPosition has passed all frames written for this turn!
                            var currentHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                            var framesRemaining = (turnStartHeadPosition + totalTurnFramesWritten) - currentHead
                            var maxWaitIterations = 150 // At most ~3 seconds timeout

                            while (framesRemaining > 0 && isPlaying.get() && maxWaitIterations-- > 0) {
                                val remainingMs = ((framesRemaining * 1000L) / SAMPLE_RATE).coerceIn(10L, 50L)
                                delay(remainingMs)
                                currentHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                                framesRemaining = (turnStartHeadPosition + totalTurnFramesWritten) - currentHead
                            }

                            // Small acoustic settling cushion (40ms)
                            delay(40)
                            onPlaybackLevel(0f)

                            // Reset turn state
                            inTurn = false
                            isTurnComplete.set(false)
                            isPreBuffering = true
                            targetThreshold = PRE_BUFFER_BYTES

                            onPlaybackFinished()
                        } else if (inTurn && !isPreBuffering) {
                            // Turn is still ongoing, but queue is momentarily empty due to network jitter.
                            // Check if the hardware buffer has underrun:
                            val currentHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                            val framesRemaining = (turnStartHeadPosition + totalTurnFramesWritten) - currentHead

                            if (framesRemaining <= 0) {
                                // Underrun occurred: switch back to buffering mode for smooth recovery
                                isPreBuffering = true
                                targetThreshold = RECOVERY_BUFFER_BYTES
                                onPlaybackLevel(0f)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            isPlaying.set(false)
        }
    }

    private fun reportLevelThrottled(chunk: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - lastLevelUpdateTime >= 60) {
            lastLevelUpdateTime = now
            val level = calculateRms(chunk)
            onPlaybackLevel(level)
        }
    }

    fun enqueueAudio(pcmBytes: ByteArray) {
        if (!isPlaying.get() || audioTrack == null) {
            start()
        }
        audioQueue.offer(pcmBytes)
    }

    fun notifyTurnComplete() {
        isTurnComplete.set(true)
    }

    fun interrupt() {
        audioQueue.clear()
        isTurnComplete.set(false)
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            // ignore
        }
        onPlaybackLevel(0f)
        onPlaybackFinished()
    }

    fun stop() {
        isPlaying.set(false)
        isTurnComplete.set(false)
        audioQueue.clear()
        playbackJob?.cancel()
        playbackJob = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioTrack = null
        onPlaybackLevel(0f)
    }

    /**
     * Applies safe volume amplification with a smooth hyperbolic tangent (tanh) soft limiter
     * instead of harsh hard-clipping. If gain is 1.0f, the pristine original PCM stream
     * is passed through without any alteration or distortion.
     */
    private fun applySafeGainAndLimiter(bytes: ByteArray, gain: Float): ByteArray {
        if (gain == 1.0f) return bytes // Pure, uncompressed studio sound from Gemini Live
        val result = ByteArray(bytes.size)
        var i = 0
        while (i < bytes.size - 1) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
            val normalized = sample.toFloat() / 32768f
            // Smooth saturation curve prevents digital square-wave distortion / crackling
            val amplified = normalized * gain
            val saturated = tanh(amplified.toDouble()).toFloat()
            val finalSample = (saturated * 32767f).toInt().coerceIn(-32767, 32767).toShort()

            result[i] = (finalSample.toInt() and 0xFF).toByte()
            result[i + 1] = ((finalSample.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
        return result
    }

    private fun calculateRms(bytes: ByteArray): Float {
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
        val normalized = (rms / 24000.0).toFloat().coerceIn(0f, 1f)
        return normalized
    }
}
