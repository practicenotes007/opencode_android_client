package com.yage.opencode_client.ui.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun VoiceReadingControls(
    state: VoiceReadingState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.phase != VoiceReadingPhase.IDLE,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Status text
                val statusText = when (state.phase) {
                    VoiceReadingPhase.INITIALIZING -> "正在初始化语音引擎..."
                    VoiceReadingPhase.SUMMARIZING -> state.progressMessage.ifEmpty { "正在生成文档摘要..." }
                    VoiceReadingPhase.READING_SUMMARY -> "正在播报文档摘要"
                    VoiceReadingPhase.READING_CONTENT -> {
                        val total = state.paragraphs.size
                        val current = state.currentParagraphIndex + 1
                        "正在朗读 ($current/$total)"
                    }
                    VoiceReadingPhase.PAUSED -> "已暂停"
                    VoiceReadingPhase.ERROR -> "出错了"
                    VoiceReadingPhase.IDLE -> ""
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Progress bar
                if (state.phase == VoiceReadingPhase.READING_CONTENT && state.paragraphs.isNotEmpty()) {
                    val progress = (state.currentParagraphIndex).toFloat() / state.paragraphs.size
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(2.dp)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Error message
                state.errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // Control buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (state.phase) {
                        VoiceReadingPhase.INITIALIZING,
                        VoiceReadingPhase.SUMMARIZING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            TextButton(onClick = onStop) {
                                Text("取消")
                            }
                        }
                        VoiceReadingPhase.READING_SUMMARY,
                        VoiceReadingPhase.READING_CONTENT -> {
                            IconButton(onClick = onPause) {
                                Icon(
                                    Icons.Default.Pause,
                                    contentDescription = "暂停",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(24.dp))
                            IconButton(onClick = onStop) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "停止",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        VoiceReadingPhase.PAUSED -> {
                            IconButton(onClick = onResume) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "继续",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(24.dp))
                            IconButton(onClick = onStop) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "停止",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        VoiceReadingPhase.ERROR -> {
                            TextButton(onClick = onDismissError) {
                                Text("关闭")
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            TextButton(onClick = onPlay) {
                                Text("重试")
                            }
                        }
                        VoiceReadingPhase.IDLE -> { /* not shown */ }
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceReadingButton(
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Default.VolumeUp,
            contentDescription = if (isActive) "停止朗读" else "语音朗读",
            tint = if (isActive) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
