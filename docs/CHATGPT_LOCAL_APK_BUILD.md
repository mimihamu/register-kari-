# ChatGPT内ローカルAPKビルド経路

この経路は、つぐレジ開発を GitHub Actions の稼働状態に依存させないためのローカル正式ビルド経路です。

## 方針

- ソース正本: `develop/v1.36`
- GitHub: 履歴・レビュー・Draft PR・バックアップ
- APK生成: ChatGPT側の固定Android環境で実行
- 対象:
  - つぐレジ `jp.co.tenposinfo.register.dev`
  - つぐレジ＋ `jp.co.tenposinfo.register.plus.dev`
  - つぐレジ CD `jp.co.tenposinfo.register.cd.dev`
- 署名: `ci/tsuguregi-development.jks.b64` から復元する固定開発署名
- 検証: unit test → Kotlin compile → 3 APK build → `ci/verify-apk-release-integrity.sh` → SHA-256

## 固定ツールチェーン

- JDK 17以上（基準17）
- Gradle 9.5.0
- Android SDK API 36
- Android Build Tools 36.0.0
- `aapt2`, `apksigner`, `zipalign`, `apkanalyzer`

## 実行

```bash
export ANDROID_HOME=/path/to/android-sdk
bash ci/local/check-environment.sh
bash ci/local/build-all-apks.sh
```

成果物は既定で `artifacts/local/` に出力する。

## 完成判定

「APKが生成できた」だけでは完成扱いにしない。3 APKすべてについて以下を満たすこと。

1. unit test PASS
2. Kotlin compile PASS
3. APK build PASS
4. package / version / minSdk / targetSdk PASS
5. APK Signature Scheme v2 PASS
6. 固定署名certificate SHA-256一致
7. ZIP integrity PASS
8. APK SHA-256生成
9. 必要に応じてCI外の独立v2 content digest検証

GitHub Actionsは補助的な二重確認として残すが、Actions障害だけで開発を停止しない。
