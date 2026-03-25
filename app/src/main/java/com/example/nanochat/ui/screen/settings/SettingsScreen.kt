package com.example.nanochat.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nanochat.data.repository.NanoChatRepository
import com.example.nanochat.data.repository.SettingsRepository
import kotlin.math.roundToInt

// ML Kit FeatureStatus constants
private const val FEATURE_UNAVAILABLE = 0
private const val FEATURE_DOWNLOADABLE = 1
private const val FEATURE_DOWNLOADING = 2
private const val FEATURE_AVAILABLE = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    chatRepository: NanoChatRepository,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(settingsRepository, chatRepository)
    )
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Model Status Card ──
            ModelStatusCard(
                modelStatus = uiState.modelStatus,
                isDownloading = uiState.isDownloading,
                downloadProgress = uiState.downloadProgress,
                onDownload = { viewModel.downloadModel() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Generation Settings ──
            Text(
                text = "生成設定",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Temperature slider
            Text(
                text = "Temperature: ${"%.2f".format(uiState.temperature)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = uiState.temperature,
                onValueChange = { viewModel.updateTemperature(it) },
                valueRange = 0f..1f,
                steps = 19, // 0.05 increments
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "低い値 = 正確・決定的、高い値 = 創造的・多様",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // TopK slider
            Text(
                text = "Top-K: ${uiState.topK}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = uiState.topK.toFloat(),
                onValueChange = { viewModel.updateTopK(it.roundToInt()) },
                valueRange = 1f..100f,
                steps = 98,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "各ステップで考慮するトークン候補数",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Context Settings ──
            Text(
                text = "コンテキスト設定",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.contextWindowSize.toString(),
                onValueChange = { viewModel.updateContextWindowSize(it) },
                label = { Text("コンテキストウィンドウサイズ") },
                placeholder = { Text("4096") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text(
                text = "コンテキストに含める最大トークン数（デフォルト: 4096）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.systemPrompt,
                onValueChange = { viewModel.updateSystemPrompt(it) },
                label = { Text("システムプロンプト") },
                placeholder = { Text("モデルの振る舞いを指示するプロンプト（省略可）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 4
            )
            Text(
                text = "コンテキストを圧迫するため、短めに（~200トークン以下推奨）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { viewModel.saveSettings() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isSaving
            ) {
                Text(if (uiState.isSaving) "保存中..." else "保存")
            }

            if (uiState.saveSuccess) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "設定を保存しました",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ModelStatusCard(
    modelStatus: Int,
    isDownloading: Boolean,
    downloadProgress: Float,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (modelStatus) {
                FEATURE_AVAILABLE -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Gemini Nano モデル",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        isDownloading -> "ダウンロード中..."
                        modelStatus == FEATURE_AVAILABLE -> "利用可能"
                        modelStatus == FEATURE_DOWNLOADABLE -> "ダウンロードが必要"
                        modelStatus == FEATURE_DOWNLOADING -> "ダウンロード中"
                        modelStatus == FEATURE_UNAVAILABLE -> "利用不可（このデバイスでは使えません）"
                        else -> "確認中..."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (modelStatus) {
                        FEATURE_AVAILABLE -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                if (modelStatus == FEATURE_DOWNLOADABLE && !isDownloading) {
                    OutlinedButton(onClick = onDownload) {
                        Text("ダウンロード")
                    }
                }
            }

            if (isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
