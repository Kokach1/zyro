package com.exodia.batteryalert.platform.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.exodia.batteryalert.core.alert.SpeechPort
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android TextToSpeech coordinator.
 * Implements non-overlapping priority speech queue.
 */
class SpeechCoordinator(
    private val context: Context,
    private val onInitComplete: ((Boolean) -> Unit)? = null
) : SpeechPort, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val isInitialized = AtomicBoolean(false)
    private val isSpeakingNow = AtomicBoolean(false)

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            onInitComplete?.invoke(false)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            val success = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            isInitialized.set(success)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeakingNow.set(true)
                }

                override fun onDone(utteranceId: String?) {
                    isSpeakingNow.set(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeakingNow.set(false)
                }
            })
            onInitComplete?.invoke(success)
        } else {
            isInitialized.set(false)
            onInitComplete?.invoke(false)
        }
    }

    override fun speak(text: String, isUrgent: Boolean) {
        if (!isInitialized.get() || tts == null) return

        val queueMode = if (isUrgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = UUID.randomUUID().toString()

        try {
            tts?.speak(text, queueMode, null, utteranceId)
        } catch (e: Exception) {
            // TTS playback exception handled gracefully
        }
    }

    override fun isSpeaking(): Boolean = isSpeakingNow.get()

    override fun stop() {
        try {
            tts?.stop()
            isSpeakingNow.set(false)
        } catch (e: Exception) {
            // Ignore stop error
        }
    }

    fun release() {
        try {
            stop()
            tts?.shutdown()
            tts = null
            isInitialized.set(false)
        } catch (e: Exception) {
            // Ignore shutdown error
        }
    }
}
