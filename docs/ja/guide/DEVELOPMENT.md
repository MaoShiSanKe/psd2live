# 開発とコマンドライン

[ドキュメント一覧](../../README.md) · [中文](../../zh/guide/DEVELOPMENT.md) · [English](../../en/guide/DEVELOPMENT.md)

ソースからの実行、コマンドラインの利用、開発への参加向けのページです。リリースパッケージはランタイムを同梱しているため、通常の利用では不要です。

## 必要環境

- JDK 21。Gradle は同梱の Wrapper で実行するため、別途インストールは不要です。
- Windows では `.\gradlew.bat`、Linux / macOS では `./gradlew` を使います。以下の例は `./gradlew` で表記します。
- 公式 Cubism SDK は必須ではありません。ソースビルドは内蔵レンダラーを使います。ネイティブブリッジは [Cubism ネイティブプレビュー](CUBISM_SDK_SETUP.md)を参照してください。

## 実行

```bash
./gradlew run                     # 引数なし：GUI を起動
./gradlew run --args="--help"     # CLI のヘルプ
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

Windows ではリポジトリ直下の `run-gui.bat` でも GUI を起動できます。引数がなければ GUI、引数があれば CLI として動作し、`--input` が必須です（`--help` を除く）。CLI は PSD から書き出しファイルを直接生成し、編集用の `.psd2live` プロジェクトは作りません。

## CLI オプション

| オプション | 既定値 | 内容 |
| --- | --- | --- |
| `--input <path>` | 必須 | 入力するレイヤー付き PSD |
| `--output <path>` | PSD と同じ場所の `psd2live-output` | 出力先 |
| `--lang <zh\|en\|ja>` | システム言語 | ログの言語 |
| `--atlas <size>` | 4096 | テクスチャアトラスのサイズ |
| `--mesh-spacing <px>` | 64 | メッシュ間隔 |
| `--head-strength <value>` | 1.0 | 頭の変形の強さ |
| `--body-strength <value>` | 1.0 | 体の変形の強さ |
| `--mesh-only` | オフ | メッシュのみ生成 |
| `--no-deformers` | オフ | デフォーマを生成しない |
| `--no-motions` | オフ | モーションを出力しない |
| `--no-physics` | オフ | 物理を生成しない |
| `--no-cmo3` | オフ | CMO3 を出力しない |
| `--no-moc3` | オフ | MOC3 を出力しない |
| `--no-json` | オフ | 診断 JSON を出力しない |
| `--upscale <1\|2\|4>` | 1 | テクスチャ高解像度化の倍率。1 で無効 |
| `--upscale-python <path>` | `python` | nunif の依存関係を入れた Python |
| `--nunif-dir <path>` | 空 | nunif のソースディレクトリ |
| `--upscale-model <path>` | 空 | 重みファイルのディレクトリ |
| `--upscale-tile <64..512>` | 256 | 推論のタイルサイズ |
| `--upscale-noise <-1..3>` | 1 | ノイズ除去レベル。-1 で無効 |
| `--no-upscale-neural-alpha` | オフ | アルファをバイリニアで拡大 |

補足：

- 既定値は [`Main.kt`](../../../src/main/kotlin/io/github/psd2live/Main.kt) に従います。GUI のメッシュ間隔の初期値は `PipelineConfig` の 40 で、CLI の 64 とは異なります。
- CMO3、MOC3、診断 JSON のうち少なくとも一つは出力してください。
- ニューラルアルファは既定で有効です。`--upscale-neural-alpha` は旧コマンドとの互換用です。
- 高解像度化の準備は[テクスチャ高解像度化](../../zh/guide/TEXTURE_UPSCALE.md)（中国語）を参照してください。

## テストとパッケージ作成

```bash
./gradlew test                                   # 全テスト（CI は Ubuntu と Windows で実行）
./gradlew test --tests "io.github.psd2live.core.SwingDeformerTest"   # 単一のテストクラス
./gradlew createDistributable                    # ランタイム付きのアプリディレクトリ
./gradlew packageDistributionForCurrentOS        # 現在のプラットフォーム向けインストーラー
```

- どちらもビルドしたプラットフォームのネイティブライブラリのみを含み、アプリのリソースに `LICENSE`、`THIRD_PARTY_NOTICES.md`、`licenses/` を同梱します。
- 公式 SDK のリソース（`src/main/resources/cubism/`）は、`-Ppsd2live.includeCubism=true` または `PSD2LIVE_INCLUDE_CUBISM=true` を指定したときだけ含まれます。SDK を含むパッケージは公開配布できません。[CI とリリース](../../en/guide/CUBISM_CI_RELEASE.md)（英語）を参照してください。
- Linux では `./native/package_linux.sh` で、システムの JDK 21 を使うローカル起動パッケージを作成できます（`dist/linux-<タイムスタンプ>/` に出力）。詳しくは [native/README.md](../../../native/README.md) を参照してください。
- 独立した lint タスクはありません。コードスタイルは `kotlin.code.style=official` です。

## コード構成

ソースは `src/main/kotlin/` 以下の二つのトップレベルパッケージに分かれています。

| パッケージ | 役割 |
| --- | --- |
| `org.umamo.runtime` | `PuppetModel`、キーフォーム補間と評価 |
| `org.umamo.format` | PSD、CMO3、MOC3、画像形式の読み書き |
| `org.umamo.interop` | `PuppetModel` と CMO3 / MOC3 の相互変換 |
| `org.umamo.render` | LWJGL / OpenGL プレビュー |
| `org.umamo.edit` | モデルに対する不変の編集プリミティブ |
| `io.github.psd2live.core` | 生成パイプライン（`PSD2LivePipeline`、`LayerClassifier`、`AdaptiveMeshGenerator`、`RigBuilder`、`MotionGenerator`、`PhysicsGenerator`）と再生可能な編集 |
| `io.github.psd2live.project` | `.psd2live` アーカイブ、セッション、ワークスペース状態のシリアライズ |
| `io.github.psd2live.history` | 分岐する元に戻す / やり直し |
| `io.github.psd2live.agent` | ローカル MCP サーバーと公開ツールの定義 |
| `io.github.psd2live.ui` | Compose UI：`state`（ViewModel、ショートカット登録）、`views`（ワークスペースとパネル）、`components`（ダイアログと部品）、`tutorial`。キャンバス編集と描画は `ui` 直下 |
| `io.github.psd2live.i18n` | UI 文言。リソースは `src/main/resources/i18n/` |

生成と書き出しのロジックは `core` / `project` に置き、Compose のコードには書かないでください。

## 基本ルール：再構築と再生

`PuppetModel` は永続化されません。プロジェクトが保存するのは、元画像、レイヤー分類、設定と、シリアライズ可能な編集記録（`RigEditOverlay`）です。開くたび、また変更のたびに次の処理が行われます。

1. `RigBuilder` が元画像から基本のリグを再生成する
2. `RigEditOverlay.applyTo` が編集を決まった順序で再生する：パラメータの削除 / 作成 → Warp と構造 → キーフォーム → 記録順の編集ジャーナル → 最後に揺れを生成

そのため新しい編集機能では次の点を守ってください。

- 変更は `RigEditOverlay` のシリアライズ可能なフィールドに記録し（できれば編集ジャーナルの JSON コマンドとして）、ワークスペース状態のコーデックで保存・復元します。`PuppetModel` だけを変更すると再構築で失われます。
- UI と MCP は同じ編集コマンドを使います。`org.umamo.edit` の低レベルメソッドは、そのまま公開インターフェースになるわけではありません。
- 受け入れ確認の流れ：ドメインデータ → 履歴の再生 → 保存と再読み込み → 対象 Cubism バージョンの処理 → 書き出しと読み戻し → 目視確認。詳しくは[ランタイムと書き出しの境界](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)（中国語）を参照してください。

UI 文言を追加するときは、`Messages.properties`、`Messages_zh_CN.properties`、`Messages_ja.properties` の三つに同時に追加し、キー数をそろえてください。

## 書き出し結果の確認

- `.model3.json` が参照するすべてのファイルを一緒に納品します。
- ログパネルと診断 JSON の警告を確認します。
- 編集を続けるには `.psd2live` プロジェクトを保存します。`.psd2live.json` はレポートにすぎません。

関連：[Cubism ネイティブプレビュー](CUBISM_SDK_SETUP.md) · [`build.gradle.kts`](../../../build.gradle.kts)
