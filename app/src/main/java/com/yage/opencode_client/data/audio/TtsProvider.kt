package com.yage.opencode_client.data.audio

import kotlinx.coroutines.flow.Flow

/**
 * Text-to-Speech provider abstraction.
 *
 * Implementations:
 * - [TextToSpeechManager] — Android system TTS (direct playback, always available)
 * - [BailianTtsProvider] — Alibaba Bailian cloud TTS API (download → play)
 * - [VolcanoArkTtsProvider] — Volcano Ark (ByteDance) cloud TTS API (download → play)
 */
interface TtsProvider {

    /** Lifecycle events emitted by the provider during synthesis/playback. */
    val events: Flow<TtsEvent>

    /** Initialize the TTS engine / prepare API client. Returns true on success. */
    fun initialize(): Boolean

    /**
     * Synthesize speech for the given text fragment.
     *
     * Cloud providers download audio bytes and return [SynthesisResult.AudioData].
     * The system provider plays directly and returns [SynthesisResult.HandledByProvider].
     *
     * Must be called from a coroutine scope — cloud providers perform network I/O.
     */
    suspend fun synthesize(text: String, utteranceId: String): SynthesisResult

    /** Stop any in-flight synthesis or playback immediately. */
    fun stop()

    /** Release all resources (engine, connections, caches). */
    fun shutdown()

    /** Whether the provider is currently producing or playing audio. */
    val isSpeaking: Boolean
}

/**
 * Result of a [TtsProvider.synthesize] call.
 */
sealed interface SynthesisResult {

    /**
     * Audio data ready for playback.
     * [TtsManager] will decode and play this via Android MediaPlayer.
     */
    data class AudioData(
        val bytes: ByteArray,
        val mimeType: String = "audio/mpeg"
    ) : SynthesisResult

    /**
     * The provider handled playback internally (e.g. Android system TTS
     * speaks directly through the audio HAL). No further action needed;
     * events will arrive via [TtsProvider.events] as usual.
     */
    data object HandledByProvider : SynthesisResult

    /**
     * Synthesis failed — human-readable error message.
     */
    data class Failed(val error: String) : SynthesisResult
}

/** TTS lifecycle events — same contract for all providers. */
sealed class TtsEvent {
    data class UtteranceStarted(val utteranceId: String) : TtsEvent()
    data class UtteranceCompleted(val utteranceId: String) : TtsEvent()
    data class UtteranceError(val utteranceId: String, val errorCode: Int) : TtsEvent()
    data object EngineReady : TtsEvent()
    data class EngineError(val message: String) : TtsEvent()
}

/** Which TTS provider is currently selected by the user. */
enum class TtsProviderType(val displayName: String) {
    SYSTEM("系统 TTS"),
    BAILIAN("百炼 TTS"),
    VOLCANO_ARK("火山方舟 TTS");
}
