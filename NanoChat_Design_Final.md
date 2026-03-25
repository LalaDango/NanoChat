# NanoChat 設計書 — Gemini Nano V3 専用チャットアプリ（最終版）
> LocalLLMChatをベースにした、Galaxy S26向けオンデバイスAIチャットアプリ
> Claude Code向けブリーフィング
> 確定日: 2026-03-22

---

## 0. 決定事項サマリー

| 項目 | 決定 |
|------|------|
| プロジェクト構成 | **別リポジトリ**（LocalLLMChatのフォーク → NanoChat） |
| 要約機能 | **ML Kit Summarization API**（箇条書き1〜3個、品質保証あり） |
| 4kトークン超過時 | **B) マーク表示**（半透明 + 「context外」ラベル、動的に毎回再計算） |

---

## 1. プロジェクト概要

### 何を作るか
LocalLLMChat（Kotlin/Jetpack Compose）のフォークとして、FastFlowLM (HTTP/SSE) の代わりに **ML Kit GenAI Prompt API** 経由で **Gemini Nano V3** とチャットするAndroidアプリ。完全オフライン動作。個人用。

### 根本的な違い
| | LocalLLMChat | NanoChat |
|---|---|---|
| 通信方式 | HTTP REST API (OpenAI互換) + SSE ストリーミング | **ML Kit SDK (in-process)** + Kotlin Flow ストリーミング |
| モデル管理 | ユーザーがFastFlowLMサーバーを起動 | **Android AICore** がモデルDL・NPU実行を自動管理 |
| コンテキスト | 8192+ tokens (モデル依存) | **~4,096 tokens** (Nano V3制約) |
| ネットワーク | ローカルHTTP接続必須 | **完全オフライン**（モデルDL後） |
| ツール呼び出し | OpenAI function calling対応 | **非対応** |
| 要約 | ローカルLLMにプロンプトで要約 | **ML Kit Summarization API** |

---

## 2. 対象デバイス・要件

- **Galaxy S26 / S26+ / S26 Ultra** (Android 16, API 36)
- **compileSdk**: 36 (Android 16)
- **minSdk**: 26 (ML Kit GenAI要件)
- **targetSdk**: 36
- **ブートローダー**: ロック済み必須（アンロック端末では動作不可）

---

## 3. 依存関係の変更

### 追加
```kotlin
implementation("com.google.mlkit:genai-prompt:1.0.0-beta1")
implementation("com.google.mlkit:genai-summarization:1.0.0-beta1")
```

### 削除（不要になる）
```kotlin
// Retrofit & OkHttp — 全て不要
implementation(libs.retrofit)
implementation(libs.retrofit.converter.gson)
implementation(libs.okhttp)
implementation(libs.okhttp.logging)
```

### 保持
- Room, Compose, Navigation, DataStore, Coroutines, Markwon — 全てそのまま
- Gson — DB用のJSON変換で引き続き使用

---

## 4. アーキテクチャ変更箇所

### 4.1 削除するファイル/クラス

| ファイル | 理由 |
|---------|------|
| `data/remote/ApiClient.kt` | Retrofit不要 |
| `data/remote/ChatApi.kt` | HTTP API不要 |
| `data/remote/ChatRequest.kt` | HTTP API不要 |
| `data/remote/ChatResponse.kt` | HTTP API不要 |
| `data/remote/ApiChatMessage.kt` | HTTP API不要 |
| `data/remote/ToolModels.kt` | ツール非対応 |
| `data/remote/UsageResponse.kt` | ML Kitに該当レスポンスなし |
| `data/tool/` ディレクトリ全体 | ツール非対応 |
| `xml/network_security_config.xml` | ネットワーク不要 |

### 4.2 大幅に書き換えるファイル

#### `ChatRepository.kt` — 最大の変更点

**Before**: Retrofit + SSE → FastFlowLM HTTP API
**After**: ML Kit GenAI Prompt API → AICore → Gemini Nano V3

核心コードのイメージ:
```kotlin
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.FeatureStatus
import com.google.mlkit.genai.prompt.DownloadStatus

class NanoChatRepository(
    private val database: AppDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val settingsRepository: SettingsRepository
) {
    private val generativeModel: GenerativeModel = Generation.getClient()

    // モデル状態チェック
    suspend fun checkModelStatus(): Int {
        return generativeModel.checkStatus()
    }

    // モデルDLトリガー
    fun downloadModel(): Flow<DownloadStatus> {
        return generativeModel.download()
    }

    // メッセージ送信 — ストリーミング
    suspend fun sendMessage(
        conversationId: Long,
        userMessage: String,
        imageAttachment: ProcessedAttachment.ImageAttachment? = null,
        onStreamUpdate: ((content: String) -> Unit)? = null
    ): Result<String> {
        // 1. ユーザーメッセージをDBに保存
        val activePath = getActivePathMessages(conversationId)
        val parentId = activePath.lastOrNull()?.id
        val userMsgId = addMessage(conversationId, "user", userMessage, parentMessageId = parentId)

        // 2. コンテキストを構築（4096トークン制約内に収める）
        val prompt = buildPromptFromHistory(conversationId, upToMessageId = userMsgId)
        val temperature = settingsRepository.temperature.first()
        val topK = settingsRepository.topK.first()

        // 3. ストリーミング推論
        val fullResponse = StringBuilder()
        try {
            val request = if (imageAttachment != null) {
                val bitmap = decodeBitmap(imageAttachment)
                generateContentRequest(ImagePart(bitmap), TextPart(prompt)) {
                    this.temperature = temperature
                    this.topK = topK
                }
            } else {
                generateContentRequest(TextPart(prompt)) {
                    this.temperature = temperature
                    this.topK = topK
                }
            }

            generativeModel.generateContentStream(request).collect { chunk ->
                val text = chunk.candidates.firstOrNull()?.text ?: ""
                fullResponse.append(text)
                onStreamUpdate?.invoke(fullResponse.toString())
            }

            // 4. アシスタントメッセージをDBに保存
            addMessage(conversationId, "assistant", fullResponse.toString(), parentMessageId = userMsgId)
            updateConversationTitleIfNeeded(conversationId)
            return Result.success(fullResponse.toString())
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    // ─── コンテキスト構築（4kトークン制約） ───

    data class ContextBuildResult(
        val prompt: String,
        val includedMessageIds: Set<Long>  // context内のメッセージIDセット（UI表示用）
    )

    suspend fun buildPromptFromHistory(
        conversationId: Long,
        upToMessageId: Long? = null
    ): ContextBuildResult {
        val systemPrompt = settingsRepository.systemPrompt.first()
        val maxTokens = settingsRepository.contextWindowSize.first()
        var activePath = getActivePathMessages(conversationId)

        if (upToMessageId != null) {
            val idx = activePath.indexOfFirst { it.id == upToMessageId }
            if (idx >= 0) activePath = activePath.subList(0, idx + 1)
        }

        // 手動除外メッセージを先にフィルタ
        val candidates = activePath.filter { !it.isExcluded }

        // system promptのトークン数を先に確保
        val systemTokens = if (systemPrompt.isNotBlank()) estimateTokens("System: $systemPrompt\n\n") else 0
        val budget = maxTokens - systemTokens - 200  // 出力用に200トークン確保

        // 末尾（最新）から積み上げて、budgetを超えたらストップ
        var tokenCount = 0
        val included = mutableListOf<MessageEntity>()
        for (msg in candidates.reversed()) {
            val content = if (msg.isSummarized && msg.summaryText != null) msg.summaryText else msg.content
            val role = if (msg.role == "user") "User" else "Assistant"
            val estimated = estimateTokens("$role: $content\n\n")
            if (tokenCount + estimated > budget) break
            included.add(0, msg)
            tokenCount += estimated
        }

        val prompt = buildString {
            if (systemPrompt.isNotBlank()) {
                appendLine("System: $systemPrompt")
                appendLine()
            }
            for (msg in included) {
                val role = if (msg.role == "user") "User" else "Assistant"
                val content = if (msg.isSummarized && msg.summaryText != null) msg.summaryText else msg.content
                appendLine("$role: $content")
                appendLine()
            }
        }

        return ContextBuildResult(prompt, included.map { it.id }.toSet())
    }

    // トークン推定（簡易版。countTokens() APIで正確に計算も可能）
    private fun estimateTokens(text: String): Int {
        // 英語: ~4文字/トークン、日本語: ~1.5文字/トークン
        // 混在を想定して ~2文字/トークン で安全側に推定
        return (text.length / 2).coerceAtLeast(1)
    }

    // ─── 要約機能（ML Kit Summarization API） ───

    suspend fun generateSummary(content: String): Result<String> {
        // ML Kit Summarization APIを使用
        // com.google.mlkit:genai-summarization:1.0.0-beta1
        // 箇条書き1〜3個を返す。品質保証あり。
        // 入力上限: ~4,000 tokens
        // 対応言語: 英語・日本語・韓国語
        return try {
            val summarizer = Summarization.getClient(
                summarizerOptions {
                    setOutputType(SummarizerOptions.OutputType.ONE_BULLET)
                    // or TWO_BULLETS, THREE_BULLETS
                }
            )
            val status = summarizer.checkFeatureStatus().await()
            if (status != FeatureStatus.AVAILABLE) {
                return Result.failure(Exception("Summarization not available: $status"))
            }
            val request = SummarizationRequest.builder(content)
                .setContext(SummarizationRequest.InputType.ARTICLE)
                .build()
            val result = summarizer.runInference(request).await()
            summarizer.close()
            Result.success(result.summary)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

#### `SettingsRepository.kt`

**削除**: `baseUrl`, `modelName`, `disabledTools`
**追加**: `temperature`, `topK`
**保持**: `contextWindowSize` (デフォルト4096), `systemPrompt`

```kotlin
companion object {
    const val DEFAULT_CONTEXT_WINDOW_SIZE = 4096
    const val DEFAULT_SYSTEM_PROMPT = ""
    const val DEFAULT_TEMPERATURE = 0.7f
    const val DEFAULT_TOP_K = 40
}

val temperature: Flow<Float> = context.dataStore.data.map { preferences ->
    preferences[TEMPERATURE_KEY] ?: DEFAULT_TEMPERATURE
}

val topK: Flow<Int> = context.dataStore.data.map { preferences ->
    preferences[TOP_K_KEY] ?: DEFAULT_TOP_K
}
```

#### `ChatViewModel.kt`

主な変更:
- ツール関連の全stateを削除 (`toolExecutionStatus`, `askUserDialog`, `availableTools`, `modelSupportsTools`)
- `streamingReasoning` → 削除（Nanoに思考タグはない）
- **`contextIncludedIds: Set<Long>` を追加** — context内メッセージのIDセット
- `sendMessage()` のコールバックが `onStreamUpdate: (String) -> Unit` のみに簡略化
- モデル状態チェック (`checkModelStatus()`) の追加
- 画像添付は1枚のみに制限

```kotlin
data class ChatUiState(
    val conversation: ConversationEntity? = null,
    val messages: List<MessageEntity> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val contextWindowSize: Int = 4096,
    val imageAttachment: ProcessedAttachment.ImageAttachment? = null,  // 1枚のみ
    val attachmentWarning: String? = null,
    val streamingContent: String = "",
    val showSummarizeDialog: Boolean = false,
    // ... 要約関連stateは保持
    val siblingInfoMap: Map<Long, ChatRepository.SiblingInfo> = emptyMap(),
    val editingMessageId: Long? = null,
    val editingText: String = "",
    // ── NanoChat追加 ──
    val modelStatus: Int = FeatureStatus.UNAVAILABLE,  // AVAILABLE / DOWNLOADING / DOWNLOADABLE / UNAVAILABLE
    val contextIncludedIds: Set<Long> = emptySet(),     // context内メッセージID（context外は半透明表示）
)
```

#### `ChatScreen.kt`

**削除するUI要素:**
- `ToolCallBubble` — 全削除
- `ToolExecutionIndicator` — 全削除
- `AskUserQuestionDialog` — 全削除
- ツールメニュー（Build icon + DropdownMenu） — 全削除
- 翻訳ボタン — 全削除（translategemma依存）
- `<think>`タグ処理 — StreamingMessageBubbleとMessageBubble内のThinkBlock表示を全削除

**変更するUI要素:**
- `MessageBubble` の半透明表示を拡張:
  - 既存: `isExcluded` が true → 半透明 (alpha 0.45)
  - **追加**: メッセージIDが `contextIncludedIds` に含まれない → 半透明 (alpha 0.45) + 小さな「context外」ラベル
  - 両方の条件は独立して判定（手動除外 と 自動トリムは別物）
- 画像添付を1枚制限に変更（`imageAttachments: List` → `imageAttachment: Single?`）

**追加するUI要素:**
- モデルステータスインジケーター（TopAppBarに小さなバッジ: "Nano ready" / "Downloading..." / "Unavailable"）

```kotlin
// context外メッセージの表示イメージ
@Composable
private fun MessageBubble(
    message: MessageEntity,
    isInContext: Boolean,  // ← 追加
    // ... 既存の引数
) {
    val effectiveAlpha = when {
        message.isExcluded -> 0.45f       // 手動除外
        !isInContext -> 0.45f              // 自動トリム（context外）
        else -> 1f
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(effectiveAlpha),
    ) {
        // ... メッセージバブル本体

        // context外ラベル（手動除外とは別表示）
        if (!isInContext && !message.isExcluded) {
            Text(
                text = "context外",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
```

#### `SettingsScreen.kt`

大幅に簡略化:
- baseURL入力 → **削除**
- モデル選択ドロップダウン → **「モデル状態」表示カード** (AVAILABLE/DOWNLOADING/UNAVAILABLE + DLボタン)
- コンテキストサイズ → **4096固定表示**
- **追加**: temperatureスライダー (0.0〜1.0)
- **追加**: topKスライダー (1〜100)
- **追加**: 「Auto-trim old messages」チェックボックス

#### `LocalLLMChatApp.kt` → `NanoChatApp.kt`

- `ToolRegistry` → 削除
- `ChatRepository` → `NanoChatRepository`（ToolRegistry引数なし）

```kotlin
class NanoChatApp : Application() {
    lateinit var database: AppDatabase
    lateinit var settingsRepository: SettingsRepository
    lateinit var chatRepository: NanoChatRepository

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        settingsRepository = SettingsRepository(this)
        chatRepository = NanoChatRepository(
            database = database,
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            settingsRepository = settingsRepository
        )
    }
}
```

### 4.3 そのまま使えるファイル

| ファイル | 備考 |
|---------|------|
| Room DB全体 (AppDatabase, Dao, Entity) | ただしツール関連カラムは削除してv1からクリーン開始 |
| NavGraph.kt | そのまま |
| ConversationListScreen/ViewModel | そのまま（モデルステータスバッジのみ追加） |
| SummarizeConfigDialog.kt | ML Kit Summarization APIに合わせて簡略化（LengthPresetを箇条書き数に変更） |
| SessionTokenCounter.kt | context外メッセージの動的表示に対応 |
| Theme, Color, Type | そのまま |
| FileProcessor.kt | 画像添付は1枚制限に変更 |
| AttachmentPreview.kt | 1枚対応に簡略化 |

---

## 5. ML Kit GenAI Prompt API リファレンス

### 依存関係
```kotlin
implementation("com.google.mlkit:genai-prompt:1.0.0-beta1")
implementation("com.google.mlkit:genai-summarization:1.0.0-beta1")
```

### 基本パターン

```kotlin
// クライアント取得
val generativeModel = Generation.getClient()

// 状態確認
val status = generativeModel.checkStatus()
// → FeatureStatus.AVAILABLE / DOWNLOADABLE / DOWNLOADING / UNAVAILABLE

// DLトリガー（Kotlin Flow）
generativeModel.download().collect { status ->
    when (status) {
        is DownloadStatus.DownloadStarted -> ...
        is DownloadStatus.DownloadProgress -> ...
        DownloadStatus.DownloadCompleted -> ...
        is DownloadStatus.DownloadFailed -> ...
    }
}

// 非ストリーミング推論
val response = generativeModel.generateContent("prompt text here")
val text = response.text

// ストリーミング推論（Kotlin Flow）
generativeModel.generateContentStream("prompt text").collect { chunk ->
    val partialText = chunk.candidates[0].text
}

// パラメータ付き
val response = generativeModel.generateContent(
    generateContentRequest(TextPart("prompt")) {
        temperature = 0.7f
        topK = 40
        maxOutputTokens = 1024
    }
)

// マルチモーダル（画像+テキスト）— 1枚のみ
val response = generativeModel.generateContent(
    generateContentRequest(ImagePart(bitmap), TextPart("この画像を説明して"))
)

// トークンカウント
val tokenCount = generativeModel.countTokens(request)

// warmup（起動時にNanoをメモリにロード）
generativeModel.warmup()
```

### ML Kit Summarization API

```kotlin
val summarizer = Summarization.getClient(
    summarizerOptions {
        setOutputType(SummarizerOptions.OutputType.ONE_BULLET)
        // ONE_BULLET / TWO_BULLETS / THREE_BULLETS
    }
)

val status = summarizer.checkFeatureStatus().await()
// → FeatureStatus.AVAILABLE / DOWNLOADABLE / DOWNLOADING / UNAVAILABLE

val request = SummarizationRequest.builder(articleText)
    .setContext(SummarizationRequest.InputType.ARTICLE)
    // ARTICLE or CONVERSATION
    .build()

val result = summarizer.runInference(request).await()
val summary = result.summary  // 箇条書きテキスト

summarizer.close()
```

### 制約
- **入力上限**: ~4,000 tokens（≈英語3,000語）
- **セッション全体コンテキスト**: ~4,096 tokens
- **フォアグラウンド限定**: バックグラウンドでは `BACKGROUND_USE_BLOCKED`
- **推論クォータ**: 短時間の連続リクエストで `BUSY` エラー → exponential backoff
- **バッテリークォータ**: 日次制限超過で `PER_APP_BATTERY_USE_QUOTA_EXCEEDED`
- **検証済み言語**: Prompt API = 英語・韓国語、Summarization API = 英語・日本語・韓国語
- **ブートローダー**: アンロック端末では動作不可
- **画像**: 1枚のみ（Prompt API制約）

---

## 6. コンテキスト管理（4kトークン制約対応）

### 仕組み

**「自動トリム」は永続的な状態変更ではない。** 毎回メッセージ送信時に `buildPromptFromHistory()` が走り、「今この瞬間、4,096トークンに収まるのはどこからどこまでか」をリアルタイムで計算する。

```
会話: [A] [B] [C] [D] [E] [F] ← 合計6,000tok
                  ~~~~~~~~~~~
                  context内 (4,000tok)
        ~~~~~~~~~
        context外 (2,000tok、半透明 + "context外"ラベル)
```

ユーザーが [E] を手動除外すると、[E] 分のトークンが浮いて [B] がcontext内に復活する:

```
会話: [A] [B] [C] [D] [E除外] [F]
            ~~~~~~~ ~~~~~~  ~~~
            context内に復活！
```

### 2種類の「非表示」は独立

| 仕組み | トリガー | 永続性 | DB変更 | UI表示 |
|--------|---------|--------|--------|--------|
| 自動トリム | 4k超過時に毎回自動計算 | 一時的（動的再計算） | なし | 半透明 + 「context外」ラベル |
| 手動除外 | ユーザーが目アイコンをタップ | 永続（`isExcluded=true`） | あり | 半透明（既存動作） |

### 方針
1. **system prompt は短く**: 200トークン以内推奨（設定画面に注意書き表示）
2. **会話履歴は直近N往復のみ**: 古いメッセージを自動トリム（API送信対象から除外、DBはそのまま）
3. **要約機能を活用**: 長いメッセージをML Kit Summarization APIで要約してcontext節約
4. **countTokens() APIで正確なトークン計算可能**（簡易推定との併用）

---

## 7. 画像入力対応

LocalLLMChatの `FileProcessor.kt` (ImageAttachment) を流用。ただし **1枚制限**。

```kotlin
// ML Kit Prompt APIのマルチモーダル入力
val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
val response = generativeModel.generateContent(
    generateContentRequest(ImagePart(bitmap), TextPart(userMessage))
)
```

UI変更:
- `imageAttachments: List<ImageAttachment>` → `imageAttachment: ImageAttachment?`（単数）
- 2枚目を添付しようとしたらSnackbar警告「画像は1枚までです」

---

## 8. 要約機能（ML Kit Summarization API）

### LocalLLMChatとの違い

| | LocalLLMChat | NanoChat |
|---|---|---|
| 方式 | ローカルLLMにプロンプトで要約 | ML Kit Summarization API |
| 出力形式 | 自由形式テキスト | 箇条書き 1〜3個 |
| 長さ制御 | LengthPreset (SHORT/STANDARD/DETAILED) | OutputType (1/2/3 bullets) |
| 品質保証 | モデル依存 | LoRAファインチューニング済み |
| 入力上限 | モデルのコンテキスト依存 | ~4,000 tokens |

### SummarizeConfigDialog の変更

既存の `LengthPreset` (SHORT/STANDARD/DETAILED) を Bullet数選択に変更:
- 1 bullet（最も短い）
- 2 bullets（標準）
- 3 bullets（詳細）

`priorityTopics` / `excludeTopics` → 削除（ML Kit Summarization APIにはこの機能がない）

---

## 9. 削除する機能一覧

| 機能 | 理由 |
|------|------|
| ツール呼び出し (DateTimeTool, AskUserQuestion) | Nano V3はfunction calling非対応 |
| 翻訳機能 | translategemma依存。Nanoでは品質不明 |
| `<think>` タグ処理 | Nanoは思考タグを出力しない |
| baseURL設定 | HTTP接続自体がない |
| モデル選択 | Gemini Nano一択 |
| cleartext traffic設定 | ネットワーク通信なし |
| usage統計 (prompt_tokens, completion_tokens, TPS) | ML Kit APIから取得不可 |
| repeat_penalty / frequency_penalty / presence_penalty | ML Kit APIに該当パラメータなし |
| ToolCallBubble / ToolExecutionIndicator | ツール非対応 |
| AskUserQuestionDialog | ツール非対応 |

---

## 10. 追加する機能

| 機能 | 詳細 |
|------|------|
| モデル状態チェックUI | AVAILABLE/DOWNLOADING/UNAVAILABLE の表示 + DLトリガーボタン（設定画面 & 会話リストヘッダー） |
| warmup() | アプリ起動時にNanoをメモリにロード（初回推論の遅延軽減） |
| 自動トリム + context外マーク | 4kトークン超過時に古いメッセージを半透明 + 「context外」ラベルで表示。動的再計算。 |
| エラーハンドリング | BUSY → exponential backoff, BACKGROUND_USE_BLOCKED → Snackbar警告, PER_APP_BATTERY_USE_QUOTA_EXCEEDED → 「しばらく待って」表示 |
| countTokens() | トークンバーの正確な数値計算 |
| 画像1枚制限 | 2枚目添付時にSnackbar警告 |

---

## 11. DB スキーマ（v1 からクリーン開始）

新規リポジトリなので version 1 から開始。ツール関連カラムを削除してスキーマをクリーンにする。

### MessageEntity（クリーン版）
```kotlin
@Entity(tableName = "messages", ...)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,           // "user" or "assistant"
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val summaryText: String? = null,
    val isSummarized: Boolean = false,
    val summarizeConfigJson: String? = null,
    val isExcluded: Boolean = false,
    val parentMessageId: Long? = null,
    val siblingIndex: Int = 0,
    val activeChildId: Long? = null
)
// 削除: promptTokens, completionTokens, totalTokens, decodingSpeedTps,
//       prefillSpeedTps, translatedText, toolCallsJson, toolCallId
```

### ConversationEntity（変更なし）
```kotlin
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val activeRootMessageId: Long? = null
)
```

---

## 12. build.gradle.kts

```kotlin
android {
    namespace = "com.example.nanochat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.nanochat"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    // ML Kit GenAI
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta1")
    implementation("com.google.mlkit:genai-summarization:1.0.0-beta1")

    // Compose (保持)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room (保持)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore (保持)
    implementation(libs.androidx.datastore.preferences)

    // Coroutines (保持)
    implementation(libs.kotlinx.coroutines.android)

    // Markwon (保持)
    implementation(libs.markwon.core)
    implementation(libs.markwon.tables)
    implementation(libs.markwon.html)
    implementation(libs.markwon.strikethrough)

    // Gson — DB用JSON変換のみ
    implementation("com.google.code.gson:gson:2.11.0")

    // 削除: retrofit, retrofit-converter-gson, okhttp, okhttp-logging
}
```

**注意**: compileSdk 36 (Android 16) にはAGP 8.8以降が必要になる可能性あり。
ビルドエラーが出たら `gradle/libs.versions.toml` の `agp` バージョンを確認。

---

## 13. 公式リソース

| 内容 | URL |
|------|-----|
| ML Kit GenAI概要 | https://developers.google.com/ml-kit/genai |
| Prompt API概要 | https://developers.google.com/ml-kit/genai/prompt/android |
| Prompt API Get Started | https://developers.google.com/ml-kit/genai/prompt/android/get-started |
| Prompt API Kotlin リファレンス | https://developers.google.com/android/reference/kotlin/com/google/mlkit/genai/prompt/package-summary |
| GenerativeModel API | https://developers.google.com/android/reference/kotlin/com/google/mlkit/genai/prompt/GenerativeModel |
| Prompt Design ガイド | https://developers.google.com/ml-kit/genai/prompt/android/prompt-design |
| Summarization API | https://developers.google.com/ml-kit/genai/summarization/android |
| GitHubサンプル | https://github.com/googlesamples/mlkit/tree/master/android/genai |

---

## 14. Claude Codeへの作業指示（推奨ステップ）

### Phase 1: プロジェクトセットアップ
1. LocalLLMChatをコピーして `NanoChat` にリネーム
2. `build.gradle.kts` を修正（依存関係の追加/削除、compileSdk 36）
3. パッケージ名を `com.example.nanochat` に変更
4. 不要ファイルの削除 (`data/remote/*`, `data/tool/*`, `network_security_config.xml`)
5. AndroidManifest.xmlからネットワーク関連設定を削除

### Phase 2: コア実装
6. `NanoChatRepository.kt` を新規作成（ML Kit Prompt API + Summarization API連携）
7. `SettingsRepository.kt` を修正（baseURL削除、temperature/topK追加）
8. `NanoChatApp.kt` を修正（ToolRegistry削除、NanoChatRepository使用）
9. Room DBスキーマをv1にリセット（ツール関連カラム削除）

### Phase 3: UI修正
10. `ChatViewModel.kt` からツール関連state削除、`contextIncludedIds` 追加
11. `ChatScreen.kt` からツール関連UI削除、think tag処理削除、context外マーク追加
12. `SettingsScreen.kt` をNano用に再構築（モデルステータス、temperature、topKスライダー）
13. `SummarizeConfigDialog.kt` をML Kit Summarization API用に変更（bullets数選択）
14. ConversationListScreenにモデルステータスバッジ追加
15. 画像添付を1枚制限に変更

### Phase 4: テスト・仕上げ
16. エラーハンドリング追加（BUSY, BACKGROUND_USE_BLOCKED, QUOTA_EXCEEDED）
17. warmup() をApplication.onCreate()に追加
18. パッケージ名・アプリ名の最終調整
19. ProGuardルールからRetrofit関連を削除

---

## 15. 注意事項

- **日本語品質**: Prompt APIの検証済み言語は英語と韓国語のみ。日本語は動くが品質保証なし。Summarization APIは日本語対応。実機テストが必須。
- **クォータ制限**: AICore は per-app でクォータを課す。連続リクエストで BUSY になる場合は exponential backoff が必要。
- **フォアグラウンド限定**: バックグラウンドサービスからの推論は不可。
- **4kトークン制約**: 最大のボトルネック。system promptを短く保ち、要約を積極活用。
- **compileSdk 36 + AGP**: Android 16 対応のAGP 8.8以降が必要になる可能性あり。
- **ブートローダー**: アンロック端末では `FEATURE_NOT_FOUND` エラー。これはAPI仕様で回避不可。
- **モデルDL**: Gemini Nanoは~1GBのダウンロードが必要。Wi-Fi接続時のDLを推奨。AICore管理なので、他のアプリが既にDL済みなら即利用可能。
