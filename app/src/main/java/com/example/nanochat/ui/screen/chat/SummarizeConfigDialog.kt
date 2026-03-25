package com.example.nanochat.ui.screen.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.nanochat.data.model.BulletCount
import com.example.nanochat.data.model.SummarizeConfig
import com.example.nanochat.data.repository.NanoChatRepository

@Composable
fun SummarizeConfigDialog(
    initialConfig: SummarizeConfig?,
    preview: NanoChatRepository.SummarizeResult?,
    isLoading: Boolean,
    onGenerate: (SummarizeConfig) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedBulletCount by remember {
        mutableStateOf(initialConfig?.bulletCount ?: BulletCount.TWO)
    }

    fun buildConfig() = SummarizeConfig(bulletCount = selectedBulletCount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("要約設定") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Bullet count selector
                Text("箇条書き数", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BulletCount.entries.forEach { count ->
                        FilterChip(
                            selected = selectedBulletCount == count,
                            onClick = { selectedBulletCount = count },
                            label = { Text(count.label) }
                        )
                    }
                }

                // Preview area
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                "要約中…",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                } else if (preview != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("プレビュー", style = MaterialTheme.typography.labelMedium)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                preview.summaryText,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            "原: ${preview.originalTokens}t → 要約: ${preview.summaryTokens}t",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        },
        dismissButton = {
            if (preview != null && !isLoading) {
                TextButton(onClick = { onGenerate(buildConfig()) }) {
                    Text("再生成")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("キャンセル")
                }
            }
        },
        confirmButton = {
            if (preview != null && !isLoading) {
                TextButton(onClick = onConfirm) {
                    Text("保存する")
                }
            } else {
                TextButton(
                    onClick = { onGenerate(buildConfig()) },
                    enabled = !isLoading
                ) {
                    Text("要約する")
                }
            }
        }
    )
}
