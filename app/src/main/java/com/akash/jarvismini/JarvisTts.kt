package com.akash.jarvismini

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class JarvisTts(context: Context) {
    private lateinit var tts: TextToSpeech
    private var ready = false
    private var pending: String? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.UK
                ready = true
                pending?.let { speakNow(it) }
                pending = null
            }
        }
    }

    fun speak(text: String) {
        if (ready) speakNow(text) else pending = text
    }

    private fun speakNow(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    fun shutdown() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}
