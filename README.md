# TapPhotoCxrl

**グラスのタップで写真を撮ってスマホで表示する**最小サンプル。`HelloToggleCxrl` の骨格 (Foreground Service による CXR-L 接続常駐 + application-level handshake / heartbeat) をそのまま流用し、双方向通信のペイロードだけ「写真リクエスト / 撮影結果」に置き換えてある。

## このプロジェクトの狙い

- **グラスでタップ** → スマホ側の CXR-L SDK が `takePhoto()` を発行 → 撮影バイトが返ってきたらスマホに表示
- グラス側はビュー HUD として **緑のコーナーブラケット → 白フラッシュ → ✓** のシンプルなキャプチャ演出 + シャッター音
- スマホ側は **最新 1 枚** だけを保持 (履歴やストレージ書き出しはなし)

撮影自体は CXR-L SDK の `takePhoto(width, height, quality)` がスマホ→グラスの内部チャネルで完結させるため、**グラス側にカメラ実装は不要**。グラス側 APK が要るのは CUSTOMAPP セッション (= CustomCMD = グラスからのタップトリガー送信) を成立させるためだけ。

撮影演出 (`CaptureFx.kt`) は **画像アセット 0 / Compose `Canvas` で線描画** + **`ToneGenerator` で合成シャッター音**。色・サイズ・タイミングは全てファイル先頭の定数で調整可能。

## このリポジトリと依存リポジトリ

```
┌──────────────────────────────────────────┐
│ TapPhotoCxrl  ← このリポジトリ
│   phone/  : スマホアプリ (Compose)
│   glass/  : グラスアプリ (Compose)
└────┬───────────────────────────┬─────────┘
     │ ① depends on              │ ② Caps シリアライザ / グラス側 Bridge
     │ (Gradle composite build)  │ (Rokid maven)
     ▼                            ▼
   CxrGlobal              com.rokid.cxr:client-l (phone)
   (Hi Rokid global       com.rokid.cxr:cxr-service-bridge (glass)
    対応の薄いラッパー)
```

| 役割 | リポジトリ / 依存 | 説明 |
|---|---|---|
| ① ライブラリ | [TakanariShimbo/CxrGlobal](https://github.com/TakanariShimbo/CxrGlobal) | グローバル版 Hi Rokid 対応の CXR-L 薄いラッパー。本リポは Gradle composite build (`includeBuild("../../CxrGlobal")`) で取り込む |
| 本体 | **TapPhotoCxrl** (このリポ) | スマホ + グラスの双方向通信サンプル (タップ撮影 → スマホ表示) |
| ② Caps (phone) | `com.rokid.cxr:client-l:1.0.1` (Rokid maven) | Wire 互換のため本家 SDK の Caps シリアライザだけ借用 |
| ② Bridge (glass) | `com.rokid.cxr:cxr-service-bridge:1.0-20260212.103714-88` (Rokid maven) | グラス側の `CXRServiceBridge` 実装 |

> ベースは [HelloToggleCxrl](https://github.com/TakanariShimbo/HelloToggleCxrl)。ハンドシェイク / heartbeat / Foreground Service / 認証フローはそのまま、ペイロード部分だけ写真用に差し替えている。

## 端末構成

| | phone | glass |
|---|---|---|
| 端末 | Pixel 8 (Android 14+) | Rokid Glasses (YodaOS SPRITE / Android 12 ベース) |
| 表示名 | `TapPhotoCxrl Host` | `TapPhotoCxrl Client` |
| パッケージ | `com.example.tapphoto.host` | `com.example.tapphoto.client` |
| 役割 | 認証 / 接続維持 / 撮影 API 呼び出し / 画像表示 | UI 描画 / タップ受信 / リクエスト送信 |

## 動作概要

### グラス側
| State | 画面 | 音 |
|---|---|---|
| **IDLE** | 「タップで撮影」(灰) | 無音 |
| **CAPTURING** | 緑のコーナーブラケット (4 隅 L 字) が `scaleIn 1.06→1.0 + fadeIn` で出現 | 無音 |
| **CAPTURED** | 枠内のみ白フラッシュ (alpha 0→0.45→0、220ms) + 中央に ✓ がスケールイン | `ToneGenerator` の `TONE_PROP_BEEP2` (シャッター "ピッ") |
| **FAILED** | 「撮影失敗」(赤) | `TONE_PROP_NACK` (失敗 "ブッ") |
| 接続中/未接続 | 「Connecting…」(灰) / 「Phone not connected」(赤) | — |

CAPTURED / FAILED は **2 秒後に Idle 復帰**。

| ジェスチャ | キーコード | 動作 |
|---|---|---|
| シングルタップ | `KEYCODE_ENTER` | 撮影リクエストをスマホへ送信 |

その他のキーは system に通す (ダブルタップでアプリ終了など標準動作)。

ブラケット / ✓ / フラッシュは画像アセットを使わず Compose `Canvas` の `Path` で描画 (どの解像度でもジャギなし)。シャッター音は `ToneGenerator` の合成音 (アセット同梱なし)。

### スマホ側
- Hi Rokid 認証 → token を `EncryptedSharedPreferences` に永続化
- Foreground Service (`dataSync` 型) が CXR-L 接続を常駐
- 接続状態カード + 操作ボタン + **最新の写真パネル** (アスペクト 4:3、`fit` スケール)
- グラスから `request_photo` を受け取ったら `cxrLink.takePhoto(1024, 768, 80)` を発行
- バイト列受信 → `BitmapFactory.decodeByteArray` → `PhotoStore` に格納 → `photo_done` をグラスへ返送
- 失敗時は `photo_failed` を返送

## アーキテクチャ

```
phone:                                     glass:
┌──────────────┐  rk_custom_client  ┌──────────────┐
│   Compose    │   session_open     │              │
│   MainAct    │   ping (5s)        │              │
│   PhotoStore │   photo_done       │              │
│      ▲       │   photo_failed     │              │
│      │       │ ─────────────────▶ │   Compose    │
│ ConnectionSv │                    │   MainAct    │
│ (Foreground) │                    │      ▲       │
│   CXRLink    │                    │      │       │
│   takePhoto  │                    │      │       │
│   ImageCbk   │  rk_custom_key     │ GlassBridge  │
│      ▲       │  ◀───────────────  │ CXRServiceBr │
│      │       │  request_photo     │              │
└──────────────┘                    └──────────────┘
       │                                   │
       └───────── Hi Rokid (BT) ───────────┘
```

### セッション handshake & heartbeat
HelloToggleCxrl と同じ application-level セッションを継承。

- phone が `appStart` 成功後に `session_open` を送信し、以降 5 秒ごとに `ping` を流す
- glass は `session_open` または `ping` を受け取るたびに 12 秒の watchdog を再 arm
- 12 秒無受信なら glass は UI を `Phone not connected` に
- phone の `[接続停止]` 操作時は `onDestroy` で明示的に `session_close` を送ってから `disconnect()`

## 通信プロトコル

| チャンネル | 方向 | 用途 |
|---|---|---|
| `rk_custom_client` | phone → glass | `event` ∈ {`session_open`, `session_close`, `ping`, `photo_done`, `photo_failed`} |
| `rk_custom_key` | glass → phone | `event` = `request_photo` |

ペイロードは `com.rokid.cxr.Caps` を positional で書き込み (キー文字列とその値が交互に並ぶ)。すべてのフレーム共通フォーマット:

```
write("event"), write(<event>)
write("ts"),    writeInt64(<epoch ms>)
```

## 動作要件

| カテゴリ | 必要条件 | 動作確認済み |
|---|---|---|
| スマホ | Android (minSdk 31 / compileSdk 36) | Google Pixel 8 / Android 16 (SDK 36) |
| グラス | スマホとペアリング済みであること | Rokid Glasses / YodaOS SPRITE 1.18.007-20260427-150201 |
| Hi Rokid アプリ | グローバル版 (`com.rokid.sprite.global.aiapp`) インストール済み | G1.5.9.0408 (versionCode 10050009) |
| ビルド環境 | Android Studio (Kotlin 2.2.10 / AGP 9.2.0 / Compose BOM 2026.02.01) | — |

## セットアップ

### 1. 隣接配置で 2 リポジトリを clone

CxrGlobal は Gradle composite build (`includeBuild("../../CxrGlobal")`) で参照するので **同じ親ディレクトリに並べて** clone する:

```bash
cd ~/AndroidStudioProjects
git clone https://github.com/TakanariShimbo/CxrGlobal.git
git clone https://github.com/TakanariShimbo/TapPhotoCxrl.git
# → CxrGlobal / TapPhotoCxrl が並ぶ
```

### 2. SDK パスを設定

`phone/local.properties` と `glass/local.properties` のそれぞれに:

```properties
sdk.dir=/path/to/Android/Sdk
```

### 3. JDK は Android Studio バンドル JBR を使う

```bash
export JAVA_HOME=/opt/android-studio/jbr
export PATH=$JAVA_HOME/bin:$PATH
```

### 4. グラス側アプリをビルド & グラスへ投入

```bash
cd TapPhotoCxrl/glass
./gradlew installDebug
# (もしくは ./gradlew assembleDebug → adb install で手動)
```

### 5. スマホ側アプリをビルド & スマホへ投入

```bash
cd ../phone
./gradlew installDebug
```

## 使い方

1. スマホでアプリ起動 → `[認証]` (初回のみ、グローバル版 Hi Rokid の認証ダイアログが出る)
2. `[接続開始]` → 通知バーに Foreground Service の通知が出る
3. グラス側に `com.example.tapphoto.client` が自動起動して「タップで撮影」が表示される
4. グラスをタップ → 緑コーナーブラケット出現 → 数秒後にスマホへ画像が出る + グラスは枠内フラッシュ + ✓ + シャッター音 (2 秒後に Idle へ)

接続を切るときはスマホで `[接続停止]`。再認証は `[再認証]` で token を破棄。

## チューニング可能な定数

### 撮影パラメータ (`phone/app/src/main/java/com/example/tapphoto/host/ConnectionService.kt`)

```kotlin
private const val PHOTO_WIDTH = 1024
private const val PHOTO_HEIGHT = 768
private const val PHOTO_QUALITY = 80
```

### グラス HUD の見た目 (`glass/app/src/main/java/com/example/tapphoto/client/CaptureFx.kt`)

```kotlin
private val BracketColor = Color(0xFF4DFF6F)         // 蛍光グリーン
private const val BRACKET_HEIGHT_FRACTION = 0.42f    // 枠の大きさ (画面高さに対する比率)
private const val BRACKET_ARM_FRACTION = 0.14f       // L 字の腕の長さ (枠辺長に対する比率)
private val BRACKET_STROKE = 3.dp                    // L 字の太さ
private val CHECK_STROKE = 4.dp                      // ✓ の太さ
private val CHECK_SIZE = 56.dp                       // ✓ サイズ
private const val FLASH_PEAK_ALPHA = 0.45f           // フラッシュ最大不透明度
```

### 音 (`glass/app/src/main/java/com/example/tapphoto/client/CameraSfx.kt`)

`ToneGenerator` の tone ID を差し替えると音色が変わる (`TONE_PROP_BEEP2` ⇄ `TONE_CDMA_PIP` など)。

## トラブルシューティング

- **Hi Rokid 行が `not installed`**: グローバル版 (`com.rokid.sprite.global.aiapp`) がインストールされていない、または `phone/app/src/main/AndroidManifest.xml` の `<queries>` 漏れ
- **タップしてもブラケットが出たまま戻らない**: `onImageReceived` / `onImageError` のどちらも届いていない。logcat で `ConnectionService` タグの `takePhoto` / `onImageReceived` を確認
- **「撮影失敗」が出る**: `takePhoto` が同期的に false を返したか、SDK 内部エラー。logcat の `onImageError code=...` を確認
- **グラス側が `Phone not connected` のまま**: phone 側 Foreground Service が起動していない、または phone がサイレント kill された。スマホで `[接続開始]` を押し直す
- **ビルド時に `Could not resolve com.example.cxrglobal:lib`**: CxrGlobal リポを並列に clone していない、または `phone/settings.gradle.kts` の `includeBuild` パスが合っていない

## 既知の制限

- 履歴 / ストレージ書き出しなし (最新 1 枚のみメモリ保持。アプリ kill で消える)
- BT 物理切断 → 復帰時の自動再接続なし (手動で `[接続停止]` → `[接続開始]`)
- token 期限切れの自動検出なし
- グラス用 APK の自動デプロイは未実装 (手動 `adb install` 想定)
