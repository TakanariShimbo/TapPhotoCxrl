# TapPhotoCxrl

**グラスのタップで写真/動画/音声を取り、スマホで表示・保存する**最小サンプル。`HelloToggleCxrl` の骨格 (Foreground Service による CXR-L 接続常駐 + application-level handshake / heartbeat) を流用しつつ、撮影は **グラス側で Camera2 を直接叩く** 方式。Hi Rokid の `takePhoto()` は使わないので、システム標準の撮影オーバーレイ (右上の小さいプレビュー画像) も出ない。

## このプロジェクトの狙い

- **PHOTO モード**: グラスでタップ → グラス側 Camera2 で 1024×768 を 1 枚撮影 → JPEG をスマホへ送って最新 1 枚として表示
- **VIDEO モード**: グラスでタップで開始/停止 → グラス側 Camera2 で 480×360 を **固定周期** で連続キャプチャ → スマホでライブプレビュー (fps 表示付き) (音声なし)
- **AUDIO モード**: グラスでタップで開始/停止 → Hi Rokid の音声ストリーム (16 kHz mono PCM) をスマホで受信 (グラス側はトリガのみ、録音は Hi Rokid SDK 内部)
- **MOVIE モード**: VIDEO + AUDIO の同時録り (映像と音声を 1 本の MP4 に mux)
- **モード切替**: グラスで前後スワイプ (DPAD_LEFT / DPAD_RIGHT) → `PHOTO → VIDEO → AUDIO → MOVIE → PHOTO` のサイクル
- グラス側は HUD として **緑のコーナーブラケット → 白フラッシュ → ✓** のキャプチャ演出 + シャッター音 + 上端にモードバッジ (`PHOTO` / `● VIDEO` / `● AUDIO` / `● MOVIE`)
- スマホ側は最新コンテンツを表示しつつ、**保存ボタン**でストレージに書き出し (PHOTO → JPEG / VIDEO → MP4 / AUDIO → WAV / MOVIE → 音声入り MP4、いずれも MediaStore へ。VIDEO/MOVIE の動画は撮影時刻ベースで gap-fill)

撮影パイプラインは `GlassCamera` (`glass/.../client/GlassCamera.kt`) に閉じ込めてあり、API は `takePhoto(...)` / `startContinuous(...)` + `stopContinuous()` の 2 系統だけ。Hi Rokid `takePhoto()` の代替として使える。

撮影演出 (`CaptureFx.kt`) は **画像アセット 0 / Compose `Canvas` で線描画** + **`ToneGenerator` で合成シャッター音**。色・サイズ・タイミングは全てファイル先頭の定数で調整可能。

> なぜ自前 Camera2 にしたか: Hi Rokid `takePhoto()` の各回で **撮影完了オーバーレイがグラス HUD に常時出る** (連射でも消えない)。SDK 側にキャンセル API も無く `sendExit` も効かなかったため、**Hi Rokid のカメラ層を経由しない** 方針に切り替えた。トランスポート (CustomCMD) だけは Hi Rokid の ARTC を借用している。

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

> ベースは [HelloToggleCxrl](https://github.com/TakanariShimbo/HelloToggleCxrl)。ハンドシェイク / heartbeat / Foreground Service / 認証フローはそのまま、ペイロード部分は写真 (グラス側 Camera2 → JPEG → スマホ) に差し替えている。

## 端末構成

| | phone | glass |
|---|---|---|
| 端末 | Pixel 8 (Android 14+) | Rokid Glasses (YodaOS SPRITE / Android 12 ベース) |
| 表示名 | `TapPhotoCxrl Host` | `TapPhotoCxrl Client` |
| パッケージ | `com.example.tapphoto.host` | `com.example.tapphoto.client` |
| 役割 | 認証 / 接続維持 / 受信画像・音声の表示と保存 | UI 描画 / タップ受信 / **Camera2 撮影** / JPEG 送信 / 音声トリガ送信 |

## 動作概要

### グラス側
画面上端中央にモードバッジ: `PHOTO` / `● VIDEO` / `● AUDIO` / `● MOVIE` (VIDEO・AUDIO・MOVIE 中は赤丸が点滅)。

`CaptureState` は `IDLE / CAPTURING / CAPTURED / FAILED / RUNNING` の 5 値。CAPTURING / CAPTURED / FAILED は PHOTO モード専用 (1 枚撮影フロー)、RUNNING は VIDEO / AUDIO / MOVIE が「実行中」を表す共通状態。

| Mode | State | 画面 | 音 |
|---|---|---|---|
| PHOTO | IDLE | 「タップで撮影」(灰) | 無音 |
| PHOTO | CAPTURING | 緑のコーナーブラケットが `scaleIn 1.06→1.0 + fadeIn` で出現 | 無音 |
| PHOTO | CAPTURED | 枠内のみ白フラッシュ + 中央に ✓ がスケールイン | `TONE_PROP_BEEP2` (シャッター "ピッ") |
| PHOTO | FAILED | 「撮影失敗」(赤) | `TONE_PROP_NACK` (失敗 "ブッ") |
| VIDEO | IDLE | 「タップで動画」(灰) | 無音 |
| VIDEO | RUNNING | コーナーブラケット常時表示 + 「タップで停止」(下端、点滅) | 無音 |
| VIDEO | FAILED | 「動画失敗」(赤) | `TONE_PROP_NACK` |
| AUDIO | IDLE | 「タップで録音」(灰) | 無音 |
| AUDIO | RUNNING | 中央に `● REC` (赤丸点滅 + 太字) + 「タップで停止」(下端、点滅) | 無音 |
| AUDIO | FAILED | 「録音失敗」(赤) | `TONE_PROP_NACK` |
| MOVIE | IDLE | 「タップで動画録画」(灰) | 無音 |
| MOVIE | RUNNING | コーナーブラケット常時表示 + 「タップで停止」(下端、点滅) | 無音 |
| MOVIE | FAILED | 「動画録画失敗」(赤) | `TONE_PROP_NACK` |
| 接続中/未接続 | — | 「Connecting…」(灰) / 「Phone not connected」(赤) | — |

CAPTURED / FAILED は **2 秒後に IDLE 復帰**。RUNNING は次のタップ (停止) または接続喪失まで継続。

| ジェスチャ | キーコード | 動作 |
|---|---|---|
| シングルタップ | `KEYCODE_ENTER` | PHOTO: 1 枚撮影 / VIDEO・MOVIE: 開始⇄停止トグル / AUDIO: 録音開始⇄停止トグル |
| 前/後スワイプ | `KEYCODE_DPAD_LEFT/RIGHT` | モード循環 (`PHOTO → VIDEO → AUDIO → MOVIE → PHOTO`)。RUNNING 中なら停止してから切替 |

ブラケット / ✓ / フラッシュ / モードバッジ は画像アセットを使わず Compose `Canvas` の `Path` で描画。シャッター音は `ToneGenerator` の合成音 (アセット同梱なし)。

撮影パイプライン (`GlassCamera` + `GlassBridge`):

1. `CameraDevice.openCamera` → `CameraCaptureSession` を `[previewSurface, jpegReader.surface]` 2 面で構成
2. `TEMPLATE_PREVIEW` を repeating で流して AE/AWB を収束させる (起動直後のみ **700ms warmup**)
3. `TEMPLATE_STILL_CAPTURE` を 1 ショット投げる → `ImageReader.OnImageAvailableListener` で JPEG bytes と **撮影時刻** を取得
4. PHOTO は 3 を 1 回だけ。VIDEO / MOVIE は **撮影スレッド** (`glass-camera`) が `CAMERA_FRAME_PERIOD_MS` (1000ms) の **固定周期** で 3 を打ち続ける (送信時間に引きずられない)
5. VIDEO / MOVIE の送信は別スレッド (`glass-bt-sender`) で動き、producer↔consumer 間は **容量 1 の slot** (`AtomicReference`)。送信が周期に追いつかなければ古いキューが新フレームに上書きされる (`queued frame skipped` ログ)。撮影時刻は wire の `ts` に載るので、phone は drop 区間を timestamp で検出できる
6. 取得した JPEG は **回転を適用せず**、Caps に `rot = SENSOR_ORIENTATION` を載せて送信 (回転はスマホ側で実施)。frame に種別フィールドは無い — phone 側は `VideoRecorder.recording` で写真フレームと連続フレームを区別する
7. MOVIE は VIDEO のフロー (1〜6) に加えて、開始時に `capture_start{kind=movie}` イベントを送るだけで音声録音は phone 側で `cxrLink.startAudioStream(1)` を呼んで Hi Rokid SDK に任せる (グラス側は音声に触れない)

### スマホ側
- Hi Rokid 認証 → token を `EncryptedSharedPreferences` に永続化
- Foreground Service (`dataSync` 型) が CXR-L 接続を常駐
- 接続状態カード + 操作ボタン + **写真/ライブパネル** (アスペクト 4:3、`fit` スケール)
- パネルのタイトルは状態によって変化:
  - 通常: 「最新の写真 (HH:mm:ss)」
  - 連続撮影中: 「**ライブ映像 (X.X fps)**」 — `FpsTracker` がローリング平均で算出
- グラスからのイベント別動作:
  - `frame{w, h, rot, ts, data}` → `GlassImage.decode` で回転込み Bitmap → `PhotoStore`。`VideoRecorder.recording` が true なら追加で `FpsTracker.tick` + `VideoRecorder.add` (mp4 化用に蓄積)
  - `capture_start{kind, period_ms?}` → `CaptureMode` を該当モードへ。VIDEO / MOVIE なら `VideoRecorder.startNewSession(period_ms)`、AUDIO / MOVIE なら `AudioRecorder.startNewSession()` + `cxrLink.startAudioStream(1)` で Hi Rokid から PCM ストリーミング受信開始 (PCM は `cacheDir/audio_<ts>.pcm` に逐次書き出し)
  - `capture_end` → 開始したものを停止: `VideoRecorder.stopRecording()` / `AudioRecorder.stopRecording()` + `cxrLink.stopAudioStream()`
  - `mode_change{mode}` → スマホ側の表示モードを PHOTO/VIDEO/AUDIO/MOVIE に同期 (バッファ整合をここで実施)
- "newer wins" バッファ整合: モード遷移 (`mode_change` または `capture_start`) 時に新モードが要求しないバッファを `switchSessionBuffers()` でまとめて破棄。例: 新 PHOTO は VIDEO/AUDIO バッファを破棄、新 VIDEO は AUDIO バッファ + 表示中の写真を破棄、新 AUDIO は VIDEO バッファ + 表示中の写真を破棄、新 MOVIE は写真をクリアしつつ VIDEO/AUDIO バッファ両方を新規セッションで上書き
- 保存ボタン: 蓄積優先度 **動画+音声 (MOVIE) > 動画 (VIDEO) > 音声 (AUDIO) > 写真 (PHOTO)**。それぞれ MediaStore (`Movies/TapPhotoCxrl` / `Movies/TapPhotoCxrl` / `Music/TapPhotoCxrl` / `Pictures/TapPhotoCxrl`) に書き出す
  - **VIDEO** (動画): jcodec で MP4 を作成。各フレームの撮影 `ts` から **1.5×period 以上の隙間** を検出すると前フレームを複製で穴埋めし、playback FPS = `1000/period_ms` で実時間再生に揃える
  - **AUDIO** (音声): 44 byte RIFF header (16 kHz / mono / 16-bit) を頭に付けて PCM body をコピーした WAV
  - **MOVIE** (動画+音声): 3 段パイプライン。① `MediaEncoder.encodeVideoMp4` で video-only MP4 (VIDEO と同じパス) → ② `MediaEncoder.encodePcmToAacMp4` (AAC LC / 64 kbps) → ③ `MediaEncoder.combineAvMp4` で両 track を再エンコードなしで 1 本の MP4 に mux
- スマホ→グラスの送信は `session_open` / `ping` / `session_close` のみ (キャプチャトリガは全て glass-local)

## アーキテクチャ

```
phone:                                       glass:
┌──────────────┐  rk_custom_client    ┌──────────────┐
│   Compose    │   session_open       │              │
│   MainAct    │   ping (5s)          │              │
│   PhotoStore │   session_close      │              │
│ VideoRecorder│ ───────────────────▶ │  Compose     │
│ AudioRecorder│                      │  MainAct     │
│      ▲       │                      │  GlassBridge │
│ ConnectionSv │                      │     │        │
│ (Foreground) │                      │     ▼        │
│   CXRLink    │  rk_custom_key       │  GlassCamera │
│   GlassImage │  ◀─────────────────  │  (Camera2)   │
│      ▲       │  frame (binary)      │              │
│      │       │  capture_start/end   │              │
│      │       │  mode_change         │              │
│      │       │                      │              │
│      │       │  IAudioStreamCbk     │              │
│      └───────│  ◀─── PCM bytes ──── │  Hi Rokid    │
│              │     (audio stream)   │   (mic)      │
└──────────────┘                      └──────────────┘
       │                                     │
       └────────── Hi Rokid (BT) ────────────┘
```

### glass-driven な責任分担

- **トリガ・状態管理・UI フィードバック (シャッター音/✓ 表示)** は全部 glass-local
- カメラ系 (PHOTO / VIDEO / MOVIE の映像側) はグラス側で Camera2 を直接叩いて JPEG をスマホへ送信
- 音声 (AUDIO / MOVIE の音声側) は録音そのものを Hi Rokid SDK に任せ (グラスはトリガを送るだけ)、スマホ側で PCM を受信
- MOVIE は上の 2 つの合体: 同じトリガ (`capture_start{kind=movie}` / `capture_end`) で映像と音声を同時に走らせ、保存時に MP4 へ mux
- スマホ→glass の制御メッセージは存在しない (heartbeat と session lifecycle だけ)

### セッション handshake & heartbeat

HelloToggleCxrl と同じ application-level セッションを継承。

- phone が `appStart` 成功後に `session_open` を送信し、以降 5 秒ごとに `ping` を流す
- glass は `session_open` または `ping` を受け取るたびに 12 秒の watchdog を再 arm
- 12 秒無受信なら glass は UI を `Phone not connected` に
- phone の `[接続停止]` 操作時は `onDestroy` で明示的に `session_close` を送ってから `disconnect()`

## 通信プロトコル

| チャンネル | 方向 | `event` の取りうる値 |
|---|---|---|
| `rk_custom_client` | phone → glass | `session_open` / `session_close` / `ping` |
| `rk_custom_key` | glass → phone | `frame` (binary 画像) / `capture_start` / `capture_end` / `mode_change` |
| Hi Rokid audio stream | glass → phone | `IAudioStreamCbk.onAudioReceived(data, offset, length)` で raw PCM (16 kHz mono 16-bit) — Caps を経由せず SDK 内部の AIDL ストリーム |

ペイロードは `com.rokid.cxr.Caps` を positional で書き込み (キー文字列とその値が交互に並ぶ)。共通フィールド:

```
write("event"), write(<event>)
write("ts"),    writeInt64(<epoch ms>)
```

`frame` の追加フィールド (`ts` は **撮影時刻** = ImageReader callback 時点):

```
write("w"),    writeInt32(<width>)
write("h"),    writeInt32(<height>)
write("rot"),  writeInt32(<degrees, 通常 SENSOR_ORIENTATION>)
write("data"), write(<JPEG bytes>)         // TYPE_BINARY
```

frame 自体に種別を持たせない (写真かフレームかは phone 側のセッション状態で判断)。

`capture_start` の追加フィールド (`kind` は開始したセッションの種別。`period_ms` は VIDEO / MOVIE のときだけ載る — phone はこれを動画 playback FPS と gap-fill 閾値に使う):

```
write("kind"),      write("photo" | "video" | "audio" | "movie")
write("period_ms"), writeInt64(<glass の CAMERA_FRAME_PERIOD_MS>)   // VIDEO / MOVIE のみ
```

`capture_end` は共通フィールド (`event` + `ts`) のみ。直前の `capture_start{kind}` が指していたセッションを終了する。音声 (AUDIO / MOVIE) は phone 側で `cxrLink.startAudioStream(1)` / `stopAudioStream()` を呼んで Hi Rokid SDK のストリームを購読する。

`mode_change` の追加フィールド:

```
write("mode"), write("photo" | "video" | "audio" | "movie")
```

> JPEG は Caps の binary フィールドに直接埋め込んで送る。`CXRServiceBridge.sendMessage(String, Caps, byte[])` の別 byte 引数経路は phone 側 (CXR-L) から拾えないため、Caps 内 binary に統一している。1024×768 q=80 で約 50KB / 480×360 q=50 で約 5KB の payload で動作確認済み。

## 動作要件

| カテゴリ | 必要条件 | 動作確認済み |
|---|---|---|
| スマホ | Android (minSdk 31 / compileSdk 36) | Google Pixel 8 / Android 16 (SDK 36) |
| グラス | スマホとペアリング済み + `CAMERA` 権限付与 | Rokid Glasses / YodaOS SPRITE 1.18.007-20260427-150201 |
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
# 初回は CAMERA 権限ダイアログが出る。Hi Rokid 経由起動だと dialog を見逃すことがあるので
# その場合は: adb shell pm grant com.example.tapphoto.client android.permission.CAMERA
```

### 5. スマホ側アプリをビルド & スマホへ投入

```bash
cd ../phone
./gradlew installDebug
```

## 使い方

1. スマホでアプリ起動 → `[認証]` (初回のみ、グローバル版 Hi Rokid の認証ダイアログが出る)
2. `[接続開始]` → 通知バーに Foreground Service の通知が出る
3. グラス側に `com.example.tapphoto.client` が自動起動して「タップで撮影」(PHOTO mode) が表示される
4. **PHOTO**: グラスをタップ → ブラケット出現 → ~1.5 秒後にスマホへ画像 + グラスは枠内フラッシュ + ✓ + シャッター音
5. **VIDEO**: 前/後スワイプ → モードバッジが `● VIDEO` に → タップで連続撮影開始 → スマホ側パネルが「ライブ映像 (fps)」に切替 → もう一度タップで停止
6. **AUDIO**: もう一度スワイプ → モードバッジが `● AUDIO` に → タップで録音開始 (中央に `● REC`) → もう一度タップで停止 → スマホ側で「保存 (音声)」ボタンが押せるようになる
7. **MOVIE**: もう一度スワイプ → モードバッジが `● MOVIE` に → タップで音あり動画録画開始 → もう一度タップで停止 → スマホ側で「保存 (動画+音声)」が押せる

接続を切るときはスマホで `[接続停止]`。再認証は `[再認証]` で token を破棄。

## チューニング可能な定数

### 撮影パラメータ (`glass/app/src/main/java/com/example/tapphoto/client/GlassBridge.kt`)

```kotlin
// PHOTO モード
private const val PHOTO_TARGET_W = 1024
private const val PHOTO_TARGET_H = 768
private const val PHOTO_QUALITY = 80

// VIDEO / MOVIE モード (低解像度・低品質で fps を稼ぐ)
private const val VIDEO_TARGET_W = 480
private const val VIDEO_TARGET_H = 360
private const val VIDEO_QUALITY = 50
```

`GlassCamera` 側はターゲットサイズに最も近い「**同じアスペクト比**」の出力サイズを自動選択 (4:3 を target にすれば 4:3 が選ばれる)。

### Camera2 ループ (`glass/app/src/main/java/com/example/tapphoto/client/GlassCamera.kt`)

```kotlin
const val CAMERA_FRAME_PERIOD_MS = 1000L       // VIDEO / MOVIE 撮影の固定周期 (= 期待 fps の逆数)。capture_start で phone へ通知される
private const val WARMUP_MS = 700L             // preview 開始から初回キャプチャまで
```

`CAMERA_FRAME_PERIOD_MS` を縮めると fps は上がるが BT 送信や JPEG エンコードがメモリ圧迫源になり LMK で殺されやすくなる (Rokid Glass の RAM 余裕は薄い)。1000ms 以上が安定動作の目安。

`WARMUP_MS` は AE / AWB が収束する時間。短くすると初回フレームの色味が崩れる。

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
- **タップしても何も起きない / カメラが開かない**: glass の CAMERA 権限未付与。`adb shell pm grant com.example.tapphoto.client android.permission.CAMERA` で明示付与
- **ストリームの 1 枚目だけ色味がおかしい**: `WARMUP_MS` が短すぎる可能性。`GlassCamera.kt` で値を上げて再ビルド
- **VIDEO / MOVIE 中のスマホ側 fps が期待値 (1000/`CAMERA_FRAME_PERIOD_MS`) より低い**: BT が周期に追いついていない可能性。glass logcat で `queued frame skipped` が出ていれば skip 発生。`VIDEO_QUALITY` / 解像度を下げるか、`CAMERA_FRAME_PERIOD_MS` を伸ばす
- **VIDEO / MOVIE 中にグラスアプリが落ちる**: メモリ圧迫で LMK が刈っている可能性。`CAMERA_FRAME_PERIOD_MS` を伸ばすか、解像度・JPEG 品質を下げる
- **グラス側が `Phone not connected` のまま**: phone 側 Foreground Service が起動していない、または phone がサイレント kill された。スマホで `[接続開始]` を押し直す
- **ビルド時に `Could not resolve com.example.cxrglobal:lib`**: CxrGlobal リポを並列に clone していない、または `phone/settings.gradle.kts` の `includeBuild` パスが合っていない

## 既知の制限

- ストレージ書き出しは保存ボタン押下時のみ。VIDEO/AUDIO/MOVIE のバッファはアプリ kill / セッション再開で消える (永続化なし)
- BT 物理切断 → 復帰時の自動再接続なし (手動で `[接続停止]` → `[接続開始]`)
- token 期限切れの自動検出なし
- グラス用 APK の自動デプロイは未実装 (手動 `adb install` 想定)
- glass 側 Camera2 でカメラを掴んでいる間 Hi Rokid 純正のカメラ機能 (シャッターボタン撮影など) と競合する可能性 (現状未確認)
- VIDEO / MOVIE の BT スループット上限 ≒ 1 fps (Rokid Glass + 480×360 JPEG で安定動作する設定)。それより高い fps は LMK 殺しに当たりやすい
- MOVIE の音声と映像は別々のクロックで録音/録画している (PCM は到着順、frame は `ts` 通り)。`capture_start{kind=movie}` / `capture_end` を同時に発火してはいるが、BT 経由の到達タイミング差や Hi Rokid 側の音声バッファ遅延で 100ms 程度の音ズレが出る可能性あり
