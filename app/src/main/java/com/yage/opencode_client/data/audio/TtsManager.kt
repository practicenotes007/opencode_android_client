package com.yage.opencode_client.data.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.yage.opencode_client.util.SettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TTS orchestrator — selects the right [TtsProvider] and handles playback.
 *
 * ## Provider routing
 *
 * | Setting                        | Provider used           |
 * |--------------------------------|-------------------------|
 * | [TtsProviderType.SYSTEM]       | [TextToSpeechManager]   |
 * | [TtsProviderType.BAILIAN]      | [BailianTtsProvider]    |
 * | [TtsProviderType.VOLCANO_ARK]  | [VolcanoArkTtsProvider] |
 *
 * ## Playback model
 *
 * - **System TTS**: [TextToSpeechManager.synthesize] returns
 *   [SynthesisResult.HandledByProvider]; events arrive via the provider's
 *   event flow and are relayed through [events].
 * - **Cloud TTS**: [synthesize] downloads audio bytes → returns
 *   [SynthesisResult.AudioData]; [TtsManager] plays them via [MediaPlayer]
 *   and emits synthetic [TtsEvent]s.
 *
 * ## Audio caching
 *
 * Synthesized audio is cached on disk (app cache dir) keyed by
 * `SHA-256(text)`. Cache files are cleaned by the OS when storage is low.
 */
@Singleton
class TtsManager @Inject constructor(
    private val settingsManager: SettingsManager,
    private val systemTtsProvider: TextToSpeechManager,
    @ApplicationContext private val context: Context
) {

    private val eventChannel = Channel<TtsEvent>(Channel.BUFFERED)
    val events: Flow<TtsEvent> = eventChannel.receiveAsFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var providerEventJob: Job? = null

    private var activeProvider: TtsProvider? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isInitialized = false

    private val cacheDir: File by lazy {
        File(context.cacheDir, "tts_audio").also { it.mkdirs() }
    }

    /** Currently selected provider (routed from settings). */
    val currentProviderType: TtsProviderType
        get() = settingsManager.ttsProvider

    // ── Public API ────────────────────────────────────────────────────

    fun initialize(): Boolean {
        if (isInitialized) return true

        activeProvider = createProvider()
        val provider = activeProvider ?: return false

        // Wire provider events → our event channel
        providerEventJob = scope.launch {
            provider.events.collect { event ->
                eventChannel.trySend(event)
            }
        }

        val ok = provider.initialize()
        isInitialized = ok
        if (!ok) {
            Log.e(TAG, "Provider initialization failed: $currentProviderType")
        }
        return ok
    }

    /**
     * Synthesize speech for [text]. Handles the full lifecycle:
     * 1. Calls provider [TtsProvider.synthesize]
     * 2. If cloud audio → plays via [MediaPlayer], emits [TtsEvent]s
     * 3. If handled by provider → waits for events
     */
    suspend fun synthesize(text: String, utteranceId: String): SynthesisResult {
        val provider = activeProvider ?: run {
            val msg = "TtsManager not initialized"
            eventChannel.trySend(TtsEvent.EngineError(msg))
            return SynthesisResult.Failed(msg)
        }

        // Try disk cache first (cloud providers only)
        if (currentProviderType != TtsProviderType.SYSTEM) {
            val cached = loadFromCache(text)
            if (cached != null) {
                playAudioData(cached, utteranceId)
                return SynthesisResult.HandledByProvider
            }
        }

        val result = provider.synthesize(text, utteranceId)

        when (result) {
            is SynthesisResult.AudioData -> {
                // Save to cache and play
                saveToCache(text, result.bytes)
                playAudioData(result.bytes, utteranceId)
                return SynthesisResult.HandledByProvider
            }
            is SynthesisResult.HandledByProvider -> {
                // Provider handles playback; events will flow naturally
            }
            is SynthesisResult.Failed -> {
                eventChannel.trySend(TtsEvent.EngineError(result.error))
            }
        }

        return result
    }

    fun stop() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            reset()
            release()
        }
        mediaPlayer = null
        activeProvider?.stop()
    }

    fun shutdown() {
        stop()
        activeProvider?.shutdown()
        activeProvider = null
        providerEventJob?.cancel()
        isInitialized = false
        Log.d(TAG, "TtsManager shutdown")
    }

    val isSpeaking: Boolean
        get() = mediaPlayer?.isPlaying == true || (activeProvider?.isSpeaking == true)

    // ── Provider factory ──────────────────────────────────────────────

    private fun createProvider(): TtsProvider = when (settingsManager.ttsProvider) {
        TtsProviderType.SYSTEM -> systemTtsProvider
        TtsProviderType.BAILIAN -> BailianTtsProvider(
            apiKey = settingsManager.bailianApiKey,
            appKey = settingsManager.bailianAppKey
        )
        TtsProviderType.VOLCANO_ARK -> VolcanoArkTtsProvider(
            appId = settingsManager.volcanoArkAppId,
            accessToken = settingsManager.volcanoArkAccessToken
        )
    }

    // ── Audio playback (cloud TTS) ────────────────────────────────────

    private fun playAudioData(audioBytes: ByteArray, utteranceId: String) {
        stop() // Release any previous MediaPlayer

        val tempFile = File(cacheDir, "playback_${utteranceId}.mp3")
        tempFile.writeBytes(audioBytes)

        mediaPlayer = MediaPlayer().apply {
            setDataSource(tempFile.absolutePath)

            setOnPreparedListener {
                eventChannel.trySend(TtsEvent.UtteranceStarted(utteranceId))
                start()
            }

            setOnCompletionListener {
                eventChannel.trySend(TtsEvent.UtteranceCompleted(utteranceId))
                tempFile.delete()
            }

            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                eventChannel.trySend(TtsEvent.UtteranceError(utteranceId, what))
                tempFile.delete()
                true
            }

            prepareAsync()
        }
    }

    // ── Disk cache ────────────────────────────────────────────────────

    private fun cacheKey(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun loadFromCache(text: String): ByteArray? {
        val file = File(cacheDir, cacheKey(text))
        return if (file.exists()) file.readBytes() else null
    }

    private fun saveToCache(text: String, audioBytes: ByteArray) {
        val file = File(cacheDir, cacheKey(text))
        file.writeBytes(audioBytes)
    }

    companion object {
        private const val TAG = "TtsManager"
    }
}
