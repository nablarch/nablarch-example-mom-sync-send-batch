# task-5 Completion Check

## Completion Criteria

> **【解決済み】**（修正コミット `b31c3ba`）: 下記の根本原因②に対し、`messaging-sync-send-boot.xml` 側に `yamlInterpreters`＋`YamlTestDataParser` 上書きを集約（`unit-test.xml` の重複は削除）。再テストで BUILD SUCCESS。以下の表は修正後の結果。失敗の経緯と根本原因は記録として下部に温存する。

| Criterion | Self-check | Evidence | QA | QA Evidence |
|---|---|---|---|---|
| mvn test が BUILD SUCCESS（全テストパス、Excelなし） | OK | `LANG=ja_JP.UTF-8 mvn clean test`: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`（2026-10-06、修正コミット b31c3ba 後）。Excel不在（残存0件）で全パス | | |
| テストログで YamlTestDataParser が使われている | OK | `testDataParser` 上書きログが2回出力（起動時のテストリポジトリ＋アクション実行時に diConfig で再初期化されたリポジトリ）。いずれも `override ... YamlTestDataParser` と `YamlTestDataParser does not use TestDataReader;`。実行時も YAML リーダーが使われていることを確認 | | |

## 失敗の根本原因（確定・修正せず停止）

> **確定根本原因**（詳細: `reports/task-5-sendsync-resource-root-cause.md`）:
> テストのセットアップ/検証は `unit-test.xml`（YamlTestDataParser 上書きあり）で動くが、**アクション本体は testShots の `diConfig: messaging-sync-send-boot.xml` で起動**され、その config には YAML 上書きが無いため、実行時リポジトリの `testDataParser` は既定の **BasicTestDataParser（Excelリーダー）**。モックが応答電文を Excel から探すが、Excel は Task #4 で削除済みのため `couldn't find resource` となる。
> → YAML のファイル/ディレクトリ構造の問題ではなく、**アクション起動 config 側に YAML 対応設定が無い**という設定不足が真因。下記の当初推定（ディレクトリ前提説）は §4 のとおり誤りであり訂正済み。

`mvn clean test` は BUILD FAILURE。MOM同期送信の応答電文モックが応答電文のテストデータリソースを解決できず例外発生。

```
java.lang.IllegalArgumentException: couldn't find resource
  [SendProjectInsertMessageRequestTest/testAbnormalEnd] in [src/test/java/com/nablarch/example]
    at nablarch.test.TestSupport.getPathOf(TestSupport.java:295)
    at nablarch.test.core.messaging.RequestTestingSendSyncSupport.getMessages(RequestTestingSendSyncSupport.java:112)
    at nablarch.test.core.messaging.RequestTestingSendSyncSupport.getResponseMessageByRequestId(...)
    at nablarch.test.core.messaging.RequestTestingMessagingProvider$RequestTestingMessagingContext.createReceivedMessage(...)
    ... MessageSender.sendSync ... SendProjectInsertMessageAction.handle(...:64)
```

- 失敗結果: testNormalEnd は status code 期待`0`→実`20`・STATUS 期待`1`→実`2`、testAbnormalEnd は status code 期待`110`→実`20`・FATALログ未検出。いずれも応答電文が取得できず送信アクションが異常終了（status 20）したため。
- 確定した機序（ソースjar＋実機で検証）:
  - テストのセットアップ/検証（testShots・setup・expectedLog 読み込み）は `unit-test.xml` リポジトリ＝`YamlTestDataParser` で成功している（YAMLから読めている）。
  - アクションは `-diConfig messaging-sync-send-boot.xml` 相当で起動され、その実行時リポジトリの `testDataParser` は **BasicTestDataParser**（`test-data.xml` 由来、YAML上書きなし）。
  - 実行中、モック `RequestTestingMessagingProvider` → `RequestTestingSendSyncSupport.getMessages`(L112) → `TestSupport.getPathOf` → `getTestDataParser().isResourceExisting(...)` を評価。ここで `getTestDataParser()` は実行時リポジトリの **BasicTestDataParser** を返す。
  - `BasicTestDataParser.isResourceExisting` → `PoiXlsReader.isResourceExisting` は `src/test/java/com/nablarch/example` 配下に `SendProjectInsertMessageRequestTest.xls/.xlsx` を探す。**Excelは Task #4 で削除済み → 未検出 → false → 例外**。
  - 検証: `YamlLoader.isResourceExisting(base, "SendProjectInsertMessageRequestTest/testAbnormalEnd")` をリフレクションで直接呼ぶと **true**（＝YAMLリーダーが使われていれば解決できる）。失敗はあくまで実行時 parser が Excel リーダーであることによる。
- つまり **アクション起動 config（messaging-sync-send-boot.xml）に YAML 対応設定（YamlTestDataParser 上書き＋yamlInterpreters）が無い**ことが真因。web / batch Example は、アクション実行中にメッセージングモックがテストデータを再読込する経路を持たないため顕在化しなかった、本 Example（MOM同期送信）固有の設定不足。

## QA Expert Review

| Aspect | Verdict | Evidence / Improvement |
|---|---|---|
| Meaningful tests/verification | (pending) | |
| Edge case coverage | (pending) | |

## Expert Reviews (code changes only)

### Language Expert

| Aspect | Verdict | Evidence / Improvement |
|---|---|---|
| Best practices | (pending) | |
| Codebase style consistency | (pending) | |
| GWT test format | (pending) | |

### Software-engineering Expert

| Aspect | Verdict | Evidence / Improvement |
|---|---|---|
| Separation of concerns | (pending) | |
| System integrity | (pending) | |
| Maintainability | (pending) | |

## Overall Verdict

- Self-check: OK（修正コミット b31c3ba 後、BUILD SUCCESS）
- QA / Language / Software-engineering: (pending)
- Ready for user review: (pending)
