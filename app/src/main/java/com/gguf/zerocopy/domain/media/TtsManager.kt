package com.gguf.zerocopy.domain.media

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    var isSpeaking: Boolean = false
        private set
    var isInitialized: Boolean = false
        private set
    var onDone: (() -> Unit)? = null

    private val initListener = TextToSpeech.OnInitListener { status ->
        isInitialized = (status == TextToSpeech.SUCCESS)
        if (isInitialized) {
            tts?.language = Locale.getDefault()
        }
    }

    fun speak(text: String) {
        if (!isInitialized) return
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isSpeaking = true }
            override fun onDone(utteranceId: String?) { isSpeaking = false; onDone?.invoke() }
            override fun onError(utteranceId: String?) { isSpeaking = false }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { isSpeaking = false }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_utterance")
    }

    fun stop() {
        tts?.stop()
        isSpeaking = false
    }

    fun ensureInit() {
        if (tts == null) {
            tts = TextToSpeech(context, initListener)
        }
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        isSpeaking = false
    }
}
