# task-5 Completion Check

## Completion Criteria

| Criterion | Self-check | Evidence | QA | QA Evidence |
|---|---|---|---|---|
| mvn test が BUILD SUCCESS（全テストパス、Excelなし） | **NG（BUILD FAILURE）** | `LANG=ja_JP.UTF-8 mvn clean test`: `Tests run: 2, Failures: 2, Errors: 0`。testNormalEnd / testAbnormalEnd がともに失敗（下記根本原因） | | |
| テストログで YamlTestDataParser が使われている | OK | ログに `override component ... component name = testDataParser, ... override component definition classname = nablarch.test.core.reader.YamlTestDataParser` と `YamlTestDataParser does not use TestDataReader;` が出力。YAMLパーサは有効 | | |

## 失敗の根本原因（調査結果・修正せず停止）

`mvn clean test` は BUILD FAILURE。testDataParser は YamlTestDataParser に差し替わっている（ログ確認済み）が、MOM同期送信の応答電文モックが応答電文のテストデータリソースを解決できず例外発生。

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
- バイトコード調査による機序:
  - `TestSupport.getPathOf(name)` は `getPathResourceExisting(getTestDataPaths(), name)` を呼び、各パスで `testDataParser.isResourceExisting(path, name)` を評価する。
  - 上書き済みの `YamlTestDataParser.isResourceExisting` は `YamlLoader.isResourceExisting` に委譲し、その実装は `buildContainerPath(dir, name)` のパスが **ディレクトリであること（`File.isDirectory()`）** を要求する。
  - MOM同期送信モック `RequestTestingSendSyncSupport` は応答電文を **別リソース名 `<テストクラス>/<メソッド>`**（例 `SendProjectInsertMessageRequestTest/testAbnormalEnd`）として解決しようとする。
  - しかしコンバータが生成した YAML レイアウトは `SendProjectInsertMessageRequestTest/testAbnormalEnd.yaml`（**ファイル**）であり、`SendProjectInsertMessageRequestTest/testAbnormalEnd/`（**ディレクトリ**）は存在しない → `isResourceExisting=false` → リソース未検出で例外。
- つまり **MOM同期送信（sendSync）テストの応答電文について、`nablarch-testing-converter` が生成する YAML のファイル配置と、`nablarch-testing-yaml` の sync-send リソース解決（ディレクトリ前提）が不整合**。web / batch Example では sendSync 系の応答電文モックを使わないため顕在化しなかった、本 Example 固有の問題と考えられる。

## QA Expert Review

| Aspect | Verdict | Evidence / Improvement |
|---|---|---|
| Meaningful tests/verification | （停止） | BUILD FAILURE のため未実施 |
| Edge case coverage | （停止） | 同上 |

## Expert Reviews (code changes only)

（停止）— テストコード変更なし。

## Overall Verdict

- Self-check: NG（BUILD FAILURE）
- QA / Language / Software-engineering: 停止（未実施）
- Ready for user review: **停止（テスト失敗のため修正せず報告）**
