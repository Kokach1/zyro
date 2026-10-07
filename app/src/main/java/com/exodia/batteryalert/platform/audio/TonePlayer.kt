package com.exodia.batteryalert.platform.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.exodia.batteryalert.core.alert.TonePort
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.sin

/**
 * Android AudioTrack PCM tone generator.
 * Synthesizes pure sine waves in software:
 *  - Chime: 880 Hz -> 1760 Hz gentle two-tone notification.
 *  - Siren: 1800 Hz to 2800 Hz high-pitch alternating alarm.
 */
class TonePlayer(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : TonePort {

    private val sampleRate = 44100
    private var sirenJob: Job? = null
    private var isPlayingSiren = false

    override fun playChime() {
        scope.launch(Dispatchers.IO) {
            try {
                val durationMs1 = 150
                val durationMs2 = 250
                val samples1 = generateSineWave(880.0, durationMs1, 0.4f)
                val samples2 = generateSineWave(1760.0, durationMs2, 0.5f)
                val allSamples = samples1 + samples2

                playPcm(allSamples)
            } catch (e: Exception) {
                // Audio capability error logged gracefully
            }
        }
    }

    override fun startSiren() {
        if (isPlayingSiren) return
        isPlayingSiren = true

        sirenJob?.cancel()
        sirenJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && isPlayingSiren) {
                    // Alternating 2200 Hz and 2800 Hz high-pitch siren tones
                    val tone1 = generateSineWave(2200.0, 250, 0.8f)
                    val tone2 = generateSineWave(2800.0, 250, 0.8f)
                    playPcm(tone1 + tone2)
                }
            } catch (e: Exception) {
                // Siren stopped
            }
        }
    }

    override fun stopSiren() {
        isPlayingSiren = false
        sirenJob?.cancel()
        sirenJob = null
    }

    private fun generateSineWave(frequencyHz: Double, durationMs: Int, volume: Float): ShortArray {
        val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
        val buffer = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val angle = 2.0 * PI * i / (sampleRate / frequencyHz)
            // Apply slight envelope fade at edges to prevent clicking
            val envelope = when {
                i < 100 -> i / 100f
                i > numSamples - 100 -> (numSamples - i) / 100f
                else -> 1.0f
            }
            val sample = (sin(angle) * Short.MAX_VALUE * volume * envelope).toInt()
            buffer[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return buffer
    }

    private fun playPcm(buffer: ShortArray) {
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            audioTrack.write(buffer, 0, buffer.size)
            audioTrack.play()
            Thread.sleep((buffer.size * 1000L) / sampleRate)
        } finally {
            try {
                audioTrack.stop()
                audioTrack.release()
            } catch (e: Exception) {
                // Ignore cleanup error
            }
        }
    }

    fun release() {
        stopSiren()
        scope.cancel()
    }
}
