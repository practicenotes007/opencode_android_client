package com.yage.opencode_client.data.audio

import android.util.Log
import com.yage.opencode_client.data.repository.OpenCodeRepository
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentSummarizer @Inject constructor(
    private val repository: OpenCodeRepository
) {
    suspend fun summarize(
        documentContent: String,
        fileName: String,
        onProgress: (String) -> Unit = {}
    ): Result<String> {
        onProgress("正在创建AI会话...")

        // 1. Create a temporary session for summarization
        val session = repository.createSession(title = "语音朗读: $fileName")
            .getOrElse { error ->
                Log.e(TAG, "Failed to create session: ${error.message}")
                return Result.failure(error)
            }

        Log.d(TAG, "Created summarization session: ${session.id}")

        try {
            onProgress("正在发送文档内容给AI...")

            // 2. Send the summarization prompt
            val prompt = buildString {
                appendLine("请阅读以下文档内容，用中文总结这篇文章的主题和结构提纲。")
                appendLine()
                appendLine("要求：")
                appendLine("1. 先用一句话概括这篇文章讲的是什么")
                appendLine("2. 然后列出文章分为几个主要部分，每个部分的核心内容用一两句话说明")
                appendLine("3. 格式简洁，适合语音播报，不要用markdown格式，不要用列表符号")
                appendLine("4. 总长度控制在200字以内")
                appendLine()
                appendLine("文档内容：")
                appendLine("---")
                appendLine(documentContent.take(MAX_CONTENT_LENGTH))
            }

            repository.sendMessage(
                sessionId = session.id,
                text = prompt,
                agent = "build"
            ).getOrElse { error ->
                Log.e(TAG, "Failed to send summarization prompt: ${error.message}")
                return Result.failure(error)
            }

            onProgress("AI正在分析文档...")

            // 3. Poll for the assistant response
            val summary = pollForResponse(session.id, onProgress)
            return Result.success(summary)
        } finally {
            // 4. Clean up the temporary session
            try {
                repository.deleteSession(session.id)
                Log.d(TAG, "Deleted summarization session: ${session.id}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete summarization session: ${e.message}")
            }
        }
    }

    private suspend fun pollForResponse(
        sessionId: String,
        onProgress: (String) -> Unit
    ): String {
        var attempt = 0
        while (attempt < MAX_POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            attempt++

            val messages = repository.getMessages(sessionId).getOrNull() ?: continue

            val assistantMessage = messages.lastOrNull { msg ->
                msg.info.role == "assistant" && msg.info.finish != null
            }

            if (assistantMessage != null) {
                val summary = assistantMessage.parts
                    .filter { it.type == "text" }
                    .joinToString("\n") { it.text.orEmpty() }
                    .trim()

                if (summary.isNotEmpty()) {
                    Log.d(TAG, "Got summary: ${summary.length} chars after $attempt attempts")
                    return summary
                }
            }

            if (attempt % 5 == 0) {
                onProgress("AI正在分析文档...(${attempt * POLL_INTERVAL_MS / 1000}秒)")
            }
        }

        Log.w(TAG, "Summarization timed out after $MAX_POLL_ATTEMPTS attempts")
        return "AI总结超时，将直接开始朗读文档。"
    }

    companion object {
        private const val TAG = "DocumentSummarizer"
        private const val MAX_POLL_ATTEMPTS = 30
        private const val POLL_INTERVAL_MS = 2000L
        private const val MAX_CONTENT_LENGTH = 30000
    }
}
