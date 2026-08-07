# つぐレジ

Androidタブレット向けPOS「つぐレジ」と、管理アプリ「つぐレジ＋」、顧客表示アプリ「つぐレジ CD」の開発リポジトリです。

## アプリ構成

- `app` — つぐレジ
  - applicationId: `jp.co.tenposinfo.register`
- `management-app` — つぐレジ＋
  - applicationId: `jp.co.tenposinfo.register.plus`
- `customer-display` — つぐレジ CD
  - applicationId: `jp.co.tenposinfo.register.cd`

## 技術構成

- Android / Kotlin / Jetpack Compose
- compileSdk / targetSdk: 36
- minSdk: 26
- SQLiteOpenHelper
- つぐレジは横画面固定
- 開発版は専用development署名を使用

## 開発方針

各バージョンは前バージョンの最終HEADから累積開発します。`main`へ直接コミットせず、`develop/vX.XX`ごとにDraft PRを作成し、明示的な依頼があるまでマージしません。

コード変更後は、累積ユニットテスト、新機能テスト、3アプリのKotlin compile、3アプリのdebug APK build、SHA-256算出、GitHub Actions成功、Artifact保存まで確認します。

実機で確認していない項目は「実機確認済み」と扱いません。

## データ保護

売上SQLite、Sales Journal、Drive上JSON、同期fingerprint、取込済み売上、SENT済みOutbox、隔離履歴、同期履歴を通常の再試行・同期処理で破壊的に削除しない方針です。

## 旧資料名について

初期仕様書には旧仮称 `REGISTER（仮）`、`売上管理アプリ（仮）`、`Customer Display（仮）` が残る場合があります。現在のユーザー向け正式名称は、それぞれ `つぐレジ`、`つぐレジ＋`、`つぐレジ CD` です。
