package com.example.nanochat.ui.screen.preset

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nanochat.data.repository.PresetRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetEditScreen(
    presetRepository: PresetRepository,
    presetId: Long?,
    onBack: () -> Unit,
    viewModel: PresetViewModel = viewModel(
        factory = PresetViewModel.Factory(presetRepository)
    )
) {
    val uiState by viewModel.editUiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(presetId) {
        if (presetId != null && presetId > 0) {
            viewModel.loadPresetForEdit(presetId)
        }
    }

    val isValid = uiState.name.isNotBlank() && uiState.systemPrompt.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (uiState.isEditing) "\u30D7\u30EA\u30BB\u30C3\u30C8\u3092\u7DE8\u96C6" else "\u65B0\u3057\u3044\u30D7\u30EA\u30BB\u30C3\u30C8")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (uiState.isEditing) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "\u524A\u9664",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = uiState.emoji,
                    onValueChange = { viewModel.updateEmoji(it) },
                    label = { Text("\u7D75\u6587\u5B57") },
                    modifier = Modifier.width(80.dp),
                    singleLine = true,
                    placeholder = { Text("\uD83D\uDCDD") }
                )
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = { viewModel.updateName(it) },
                    label = { Text("\u540D\u524D") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("\u4F8B: \u30E1\u30E2\u6574\u5F62") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.description,
                onValueChange = { viewModel.updateDescription(it) },
                label = { Text("\u8AAC\u660E\uFF08\u4EFB\u610F\uFF09") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("\u3053\u306E\u30D7\u30EA\u30BB\u30C3\u30C8\u306E\u7528\u9014") }
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.systemPrompt,
                onValueChange = { viewModel.updateSystemPrompt(it) },
                label = { Text("\u30B7\u30B9\u30C6\u30E0\u30D7\u30ED\u30F3\u30D7\u30C8") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                placeholder = { Text("\u3053\u306E\u30D7\u30EA\u30BB\u30C3\u30C8\u3067\u4F7F\u3046\u30B7\u30B9\u30C6\u30E0\u30D7\u30ED\u30F3\u30D7\u30C8\u3092\u5165\u529B") }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.savePreset(onSaved = onBack) },
                modifier = Modifier.fillMaxWidth(),
                enabled = isValid && !uiState.isSaving
            ) {
                Text(if (uiState.isSaving) "\u4FDD\u5B58\u4E2D..." else "\u4FDD\u5B58")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("\u30D7\u30EA\u30BB\u30C3\u30C8\u3092\u524A\u9664") },
            text = { Text("\u300C${uiState.name}\u300D\u3092\u524A\u9664\u3057\u307E\u3059\u304B\uFF1F\n\u65E2\u5B58\u306E\u30C1\u30E3\u30C3\u30C8\u306B\u306F\u5F71\u97FF\u3057\u307E\u305B\u3093\u3002") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deletePreset(onDeleted = onBack)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("\u524A\u9664")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("\u30AD\u30E3\u30F3\u30BB\u30EB")
                }
            }
        )
    }
}
