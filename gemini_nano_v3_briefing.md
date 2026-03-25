# Gemini Nano V3 ブリーフィング
> 別のClaudeへ渡す前提で、事実確認済みの情報のみをまとめたドキュメント。
> 調査日: 2026-03-22

---

## TL;DR

Gemini Nano V3は**Android AICoreを通じてアプリから利用できるオンデバイスLLM**。
クラウドに一切データを送らず、ML Kit GenAI APIを叩くだけで使える。
ただし「汎用チャット」ではなく**短入力・短出力の特定タスク専用**として設計されている。

---

## アーキテクチャの基本構造

```
自作アプリ
  ↓ ML Kit GenAI Prompt API
Android AICore（OSのシステムサービス）
  ↓ NPU/GPU/CPUへ自動ルーティング
Gemini Nano V3 モデル（AICoreが管理・DL）
```

- アプリはモデルファイルを持たない。AICoreが一元管理。
- 複数アプリからのリクエストをAICoreがキューで処理 → RAM競合なし。
- NPU最適化はOS側が自動で行う。開発者がVRAM管理を意識する必要なし。
- **バックグラウンドアプリからの使用は不可**（フォアグラウンド限定）。
- ブートローダーアンロック端末では動作しない。

---

## パラメーター数・世代

| 世代 | パラメーター数 | 量子化 |
|------|--------------|--------|
| Nano-1 | 1.8B | 4-bit |
| Nano-2 | 3.25B | 4-bit |
| Nano-3（V3） | **非公開**（おそらく数B台） | 4-bit |

V3の正確なパラメーター数はGoogleが未公開。Pixel 10に搭載された最新版。

---

## 各APIの制約（公式ドキュメントから確認済み）

| API | 状態 | 入力上限 | 出力 | 対応言語 |
|-----|------|---------|------|---------|
| 要約 | Beta | ~4,000 token（≈英3,000語） | 箇条書き1〜3個 | 英・日・韓 |
| 校正 | Beta | 256 token | 修正案1件以上 | 英・日・韓・仏・独・伊・西 |
| 書き換え | Beta | 256 token | 提案1件以上（6スタイル） | 英・日・韓・仏・独・伊・西 |
| 画像説明 | Beta | 画像1枚 | 短い説明文1件 | 英語のみ |
| 音声認識 | Alpha | ストリーミング | テキストストリーム | Basic: 15言語 / Advanced: Pixel 10のみ |
| プロンプト | Beta | テキスト or 画像+テキスト | テキスト（ストリーム可） | モデル依存 |

**プロンプトAPIのセッション全体コンテキスト上限: 約4,096 token**
（出所: Chrome Built-in AI開発者フォーラム、localaimaster.comのまとめ記事）

---

## よくある誤情報

> ❌ 「Gemini Nano V3は128kトークンのコンテキストウィンドウを持つ」

これは**誤り**。128kはクラウド版Gemini 2.5 Flash-Liteの仕様。
Nanoのオンデバイス制約は~4,096トークン。
Perplexityがこの誤情報を自信満々に出力した事例あり（2026-03-22確認）。

---

## 自作アプリへの組み込み方

### 依存関係（例: プロンプトAPI）
```kotlin
implementation("com.google.mlkit:genai-prompt:1.0.0-beta1")
```

### 基本フロー
1. `checkFeatureStatus()` でモデルの状態確認（UNAVAILABLE / DOWNLOADABLE / DOWNLOADING / AVAILABLE）
2. 必要なら `downloadFeature()` でDLトリガー
3. リクエスト作成 → `runInference()` で推論
4. 使い終わったら `close()` でリソース解放

---

## 公式リソース（直接fetch確認済み）

| 内容 | URL |
|------|-----|
| 要約API | https://developers.google.com/ml-kit/genai/summarization/android |
| 校正API | https://developers.google.com/ml-kit/genai/proofreading/android |
| 書き換えAPI | https://developers.google.com/ml-kit/genai/rewriting/android |
| 画像説明API | https://developers.google.com/ml-kit/genai/image-description/android |
| 音声認識API | https://developers.google.com/ml-kit/genai/speech-recognition/android |
| プロンプトAPI概要 | https://developers.google.com/ml-kit/genai/prompt/android |
| ML Kit GenAI概要 | https://developers.google.com/ml-kit/genai |
| Androidブログ（V3リリース） | https://android-developers.googleblog.com/2025/08/the-latest-gemini-nano-with-on-device-ml-kit-genai-apis.html |
| GitHubサンプル | https://github.com/googlesamples/mlkit/tree/master/android/genai |

**注意**: `developer.android.com` はfetchブロックされる環境あり。
上記の `developers.google.com` 側のURLを使うこと。

---

## 設計思想まとめ

- 汎用チャットではなく「**短入力・短出力の単発タスクをオフラインで超低遅延処理する裏方**」
- LoRAアダプターで各APIごとにファインチューニング済み → プロンプトエンジニアリング不要
- コンテキスト4kの制約内でどうタスク設計するかがAndroid AIアプリ開発の肝
- プロンプトAPIは自由度が高い代わりに品質保証は開発者責任
