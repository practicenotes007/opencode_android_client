package com.yage.opencode_client.ui.files

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yage.opencode_client.data.audio.DocumentSummarizer
import com.yage.opencode_client.data.audio.TtsEvent
import com.yage.opencode_client.data.audio.TtsManager
import com.yage.opencode_client.util.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class VoiceReadingPhase {
    IDLE,
    INITIALIZING,
    SUMMARIZING,
    READING_SUMMARY,
    READING_CONTENT,
    PAUSED,
    ERROR
}

data class VoiceReadingState(
    val phase: VoiceReadingPhase = VoiceReadingPhase.IDLE,
    val summary: String? = null,
    val paragraphs: List<String> = emptyList(),
    val currentParagraphIndex: Int = 0,
    val progressMessage: String = "",
    val errorMessage: String? = null,
    val isTtsReady: Boolean = false,
    val savedPosition: Int = 0,
    val hasSavedPosition: Boolean = false
)

@HiltViewModel
class VoiceReadingViewModel @Inject constructor(
    private val ttsManager: TtsManager,
    private val summarizer: DocumentSummarizer,
    private val settingsManager: SettingsManager
) : ViewModel() {

    private val _state = MutableStateFlow(VoiceReadingState())
    val state: StateFlow<VoiceReadingState> = _state.asStateFlow()

    private var currentFilePath: String? = null
    private var currentDocumentContent: String? = null
    private var isStopping = false

    init {
        collectTtsEvents()
    }

    private fun collectTtsEvents() {
        viewModelScope.launch {
            ttsManager.events.collect { event ->
                handleTtsEvent(event)
            }
        }
    }

    private suspend fun handleTtsEvent(event: TtsEvent) {
        when (event) {
            is TtsEvent.EngineReady -> {
                _state.update { it.copy(isTtsReady = true) }
            }
            is TtsEvent.UtteranceStarted -> {
                Log.d(TAG, "Utterance started: ${event.utteranceId}")
            }
            is TtsEvent.UtteranceCompleted -> {
                Log.d(TAG, "Utterance completed: ${event.utteranceId}")
                handleUtteranceCompleted(event.utteranceId)
            }
            is TtsEvent.UtteranceError -> {
                Log.e(TAG, "Utterance error: ${event.utteranceId}, code=${event.errorCode}")
                _state.update { it.copy(errorMessage = "语音播放出错") }
            }
            is TtsEvent.EngineError -> {
                _state.update {
                    it.copy(
                        phase = VoiceReadingPhase.ERROR,
                        errorMessage = event.message
                    )
                }
            }
        }
    }

    private suspend fun handleUtteranceCompleted(utteranceId: String) {
        val currentState = _state.value
        if (isStopping) return

        when {
            utteranceId == UTTERANCE_SUMMARY -> {
                // Summary finished, start reading content
                val startIndex = if (currentState.hasSavedPosition) {
                    currentState.savedPosition
                } else {
                    0
                }
                _state.update {
                    it.copy(
                        phase = VoiceReadingPhase.READING_CONTENT,
                        currentParagraphIndex = startIndex
                    )
                }
                speakCurrentParagraph(startIndex)
            }
            utteranceId.startsWith(UTTERANCE_PARAGRAPH_PREFIX) -> {
                // Paragraph finished, move to next
                val nextIndex = currentState.currentParagraphIndex + 1
                val filePath = currentFilePath ?: return

                // Save position
                settingsManager.setVoiceReadingPosition(filePath, nextIndex)
                _state.update { it.copy(currentParagraphIndex = nextIndex) }

                if (nextIndex < currentState.paragraphs.size) {
                    speakCurrentParagraph(nextIndex)
                } else {
                    // Finished reading all paragraphs
                    _state.update { it.copy(phase = VoiceReadingPhase.IDLE) }
                    Log.d(TAG, "Finished reading all paragraphs")
                }
            }
        }
    }

    private suspend fun speakCurrentParagraph(index: Int) {
        val paragraphs = _state.value.paragraphs
        if (index >= paragraphs.size) return

        val text = paragraphs[index]
        val utteranceId = "${UTTERANCE_PARAGRAPH_PREFIX}$index"
        ttsManager.synthesize(text, utteranceId)
    }

    fun startReading(filePath: String, documentContent: String) {
        if (_state.value.phase != VoiceReadingPhase.IDLE) {
            Log.w(TAG, "Already in phase: ${_state.value.phase}")
            return
        }

        currentFilePath = filePath
        currentDocumentContent = documentContent
        isStopping = false

        // Initialize TTS
        _state.update { it.copy(phase = VoiceReadingPhase.INITIALIZING, progressMessage = "正在初始化语音引擎...") }

        val initialized = ttsManager.initialize()
        if (!initialized) {
            _state.update {
                it.copy(
                    phase = VoiceReadingPhase.ERROR,
                    errorMessage = "语音引擎初始化失败，请检查系统TTS设置"
                )
            }
            return
        }

        // Check for saved position
        val savedPosition = settingsManager.getVoiceReadingPosition(filePath)
        val hasSavedPosition = savedPosition > 0

        // Split content into paragraphs
        val paragraphs = splitIntoParagraphs(documentContent)

        _state.update {
            it.copy(
                paragraphs = paragraphs,
                savedPosition = savedPosition,
                hasSavedPosition = hasSavedPosition
            )
        }

        // Start summarization
        _state.update { it.copy(phase = VoiceReadingPhase.SUMMARIZING, progressMessage = "正在生成文档摘要...") }

        viewModelScope.launch {
            val fileName = filePath.substringAfterLast('/')
            val result = summarizer.summarize(
                documentContent = documentContent,
                fileName = fileName,
                onProgress = { msg ->
                    _state.update { it.copy(progressMessage = msg) }
                }
            )

            result.onSuccess { summary ->
                _state.update {
                    it.copy(
                        summary = summary,
                        phase = VoiceReadingPhase.READING_SUMMARY,
                        progressMessage = ""
                    )
                }

                // Speak the summary
                val introText = if (hasSavedPosition) {
                    "欢迎回来。以下是本文档的内容回顾：$summary 将从上次离开的位置继续朗读。"
                } else {
                    "以下是本文档的内容概要：$summary 现在开始朗读正文。"
                }
                ttsManager.synthesize(introText, UTTERANCE_SUMMARY)
            }.onFailure { error ->
                Log.e(TAG, "Summarization failed: ${error.message}")
                // Fallback: skip summary, read directly
                val startIndex = if (hasSavedPosition) savedPosition else 0
                _state.update {
                    it.copy(
                        phase = VoiceReadingPhase.READING_CONTENT,
                        currentParagraphIndex = startIndex,
                        progressMessage = ""
                    )
                }
                speakCurrentParagraph(startIndex)
            }
        }
    }

    fun pauseReading() {
        if (_state.value.phase == VoiceReadingPhase.READING_CONTENT ||
            _state.value.phase == VoiceReadingPhase.READING_SUMMARY
        ) {
            ttsManager.stop()
            _state.update { it.copy(phase = VoiceReadingPhase.PAUSED) }
        }
    }

    fun resumeReading() {
        if (_state.value.phase != VoiceReadingPhase.PAUSED) return

        val currentState = _state.value
        if (currentState.currentParagraphIndex < currentState.paragraphs.size) {
            _state.update { it.copy(phase = VoiceReadingPhase.READING_CONTENT) }
            viewModelScope.launch {
                speakCurrentParagraph(currentState.currentParagraphIndex)
            }
        }
    }

    fun stopReading() {
        isStopping = true
        ttsManager.stop()

        // Save current position
        currentFilePath?.let { path ->
            settingsManager.setVoiceReadingPosition(path, _state.value.currentParagraphIndex)
        }

        _state.update {
            VoiceReadingState() // Reset to initial
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.shutdown()
    }

    companion object {
        private const val TAG = "VoiceReadingViewModel"
        private const val UTTERANCE_SUMMARY = "voice_reading_summary"
        private const val UTTERANCE_PARAGRAPH_PREFIX = "voice_reading_para_"

        fun splitIntoParagraphs(content: String): List<String> {
            if (content.isBlank()) return emptyList()

            // Split by double newlines (paragraphs), then by single newlines within paragraphs
            val rawParagraphs = content
                .split(Regex("\n\\s*\n"))
                .map { it.trim() }
                .filter { it.isNotBlank() }

            if (rawParagraphs.isEmpty()) {
                return listOf(content.trim())
            }

            // For very long paragraphs, further split by sentences
            val result = mutableListOf<String>()
            for (para in rawParagraphs) {
                if (para.length > MAX_PARAGRAPH_CHARS) {
                    // Split long paragraphs by sentence boundaries
                    val sentences = para.split(Regex("(?<=[。！？；\\n])\\s*"))
                        .map { it.trim() }
                        .filter { it.isNotBlank() }

                    var buffer = StringBuilder()
                    for (sentence in sentences) {
                        if (buffer.length + sentence.length > MAX_PARAGRAPH_CHARS && buffer.isNotEmpty()) {
                            result.add(buffer.toString().trim())
                            buffer = StringBuilder()
                        }
                        buffer.append(sentence)
                    }
                    if (buffer.isNotEmpty()) {
                        result.add(buffer.toString().trim())
                    }
                } else {
                    result.add(para)
                }
            }

            return result.ifEmpty { listOf(content.trim()) }
        }

        private const val MAX_PARAGRAPH_CHARS = 500
    }
}
