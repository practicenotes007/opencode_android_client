package com.yage.opencode_client.data.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed class TtsEvent {
    data class UtteranceStarted(val utteranceId: String) : TtsEvent()
    data class UtteranceCompleted(val utteranceId: String) : TtsEvent()
    data class UtteranceError(val utteranceId: String, val errorCode: Int) : TtsEvent()
    data object EngineReady : TtsEvent()
    data class EngineError(val message: String) : TtsEvent()
}

@Singleton
class TextToSpeechManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val eventChannel = Channel<TtsEvent>(Channel.BUFFERED)
    val events: Flow<TtsEvent> = eventChannel.receiveAsFlow()

    fun initialize(): Boolean {
        if (isInitialized) return true

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Try simplified Chinese specifically
                    val zhResult = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    if (zhResult == TextToSpeech.LANG_MISSING_DATA || zhResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Chinese TTS not supported, falling back to default locale")
                        tts?.language?.let { Log.d(TAG, "Using locale: $it") }
                    }
                }

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.let {
                            Log.d(TAG, "Utterance started: $it")
                            eventChannel.trySend(TtsEvent.UtteranceStarted(it))
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let {
                            Log.d(TAG, "Utterance completed: $it")
                            eventChannel.trySend(TtsEvent.UtteranceCompleted(it))
                        }
                    }

                    @Deprecated("Use onError with UtteranceId")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let {
                            Log.e(TAG, "Utterance error (deprecated): $it")
                            eventChannel.trySend(TtsEvent.UtteranceError(it, -1))
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        utteranceId?.let {
                            Log.e(TAG, "Utterance error: $it, code=$errorCode")
                            eventChannel.trySend(TtsEvent.UtteranceError(it, errorCode))
                        }
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        Log.d(TAG, "Utterance stopped: $utteranceId, interrupted=$interrupted")
                    }
                })

                isInitialized = true
                Log.d(TAG, "TTS engine initialized successfully")
                eventChannel.trySend(TtsEvent.EngineReady)
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
                eventChannel.trySend(TtsEvent.EngineError("TTS initialization failed"))
            }
        }

        return isInitialized
    }

    fun speak(text: String, utteranceId: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        val engine = tts ?: run {
            Log.e(TAG, "TTS not initialized")
            eventChannel.trySend(TtsEvent.EngineError("TTS not initialized"))
            return
        }

        if (text.isBlank()) {
            Log.w(TAG, "Skipping blank text for utterance: $utteranceId")
            eventChannel.trySend(TtsEvent.UtteranceCompleted(utteranceId))
            return
        }

        val params = android.os.Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

        val result = engine.speak(text, queueMode, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "speak failed with result: $result")
            eventChannel.trySend(TtsEvent.UtteranceError(utteranceId, result))
        }
    }

    fun stop() {
        tts?.stop()
        Log.d(TAG, "TTS stopped")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Log.d(TAG, "TTS shutdown")
    }

    val isSpeaking: Boolean
        get() = tts?.isSpeaking == true

    companion object {
        private const val TAG = "TextToSpeechManager"
    }
}
