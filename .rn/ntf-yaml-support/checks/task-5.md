# task-5 Completion Check

## Completion Criteria

> 本タスクは当初 BUILD FAILURE だったが、根本原因を特定し `messaging-sync-send-boot.xml` 側へ YAML 設定を集約する修正（コミット `b31c3ba`）で解決し、再テストで BUILD SUCCESS となった。以下の表は修正後の結果。経緯は下部「前回の失敗と今回の対応（経緯）」に記載。

| Criterion | Self-check | Evidence | QA | QA Evidence |
|---|---|---|---|---|
| mvn test が BUILD SUCCESS（全テストパス、Excelなし） | OK | `LANG=ja_JP.UTF-8 mvn clean test`: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`（2026-10-06、修正コミット b31c3ba 後）。Excel不在（残存0件）で全パス | OK | `mvn clean test` を独立に再現し `Tests run: 2, Failures: 0`/BUILD SUCCESS を確認。`find . -iname '*.xls*'` でリポジトリ全体0件を確認し、Excel経由で通っていないことを担保 |
| テストログで YamlTestDataParser が使われている | OK | `testDataParser` 上書きログが2回出力（起動時のテストリポジトリ＋アクション実行時に diConfig で再初期化されたリポジトリ）。いずれも `override ... YamlTestDataParser` と `YamlTestDataParser does not use TestDataReader;`。実行時も YAML リーダーが使われていることを確認 | OK | override ログ2回（起動時／アクション実行時の再初期化後）と `YamlTestDataParser does not use TestDataReader;` の両出力を独立に確認。YAML↔Javaメソッド対応に過不足なし |

## 前回の失敗と今回の対応（経緯）

### 前回の失敗内容
Task #4 で Excel を削除した状態の `mvn clean test` が **BUILD FAILURE**。MOM同期送信の応答電文モックが応答電文のテストデータリソースを解決できず例外発生。

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

（補足: 当初はバイトコード推定から「`YamlLoader` がディレクトリを要求し、コンバータはファイル出力のため不整合」と推定したが、`YamlLoader.isResourceExisting` 単体呼び出しが true を返すことから誤りと判明し、上記の「実行時 parser が Excel リーダー」が真因と訂正した。詳細は `reports/task-5-sendsync-resource-root-cause.md`。）

### 今回の対応内容（OKとなった経緯）
1. 根本原因（実行時リポジトリの `testDataParser` が Excel リーダーのまま）に対し、**アクション起動 config である `messaging-sync-send-boot.xml`(test) に `yamlInterpreters`＋`YamlTestDataParser` の `testDataParser` 上書きを追加**（`dbInfo`/`test-data.xml` import より後に配置して参照解決）。`unit-test.xml` は同ファイルを import するため、重複定義は削除し**単一ソース化**（修正コミット `b31c3ba`）。
2. これにより、セットアップ/検証（`unit-test.xml` 経由）だけでなく、**アクション実行時に diConfig で再初期化されるリポジトリでも YamlTestDataParser が有効**になり、応答電文モックが YAML から応答電文を読めるようになった。
3. 再テスト `LANG=ja_JP.UTF-8 mvn clean test` → **BUILD SUCCESS（Tests run: 2, Failures: 0）**。override ログが起動時・実行時の両方で出力され、実行時も YAML リーダーが使われることを確認。Excel 残存0件で誤パスも排除。
4. 併せて Task #3 の差異①（errorMode レコードの再送要求フラグ `0`→`""`）が挙動に影響しないこと（NTF 仕様どおり）も、この再テスト全パスで裏付けられた。

## QA Expert Review

| Aspect | Verdict | Evidence / Improvement |
|---|---|---|
| Meaningful tests/verification | OK | Excelなしで `Tests run: 2, Failures: 0`/BUILD SUCCESS を独立再現。正常1＋異常4ショットの status/ログ/DB遷移(0→1,0→2)を YAML 駆動で検証。override が起動時・実行時の両経路で効くことをログ(L90/L219, L108/L247)で実証 |
| Edge case coverage | OK | 異常系4系統（Validation失敗110/フォーマット解析失敗110/タイムアウト100/送受信エラー100）と errorMode:timeout・msgException のモック制御まで網羅。Excel残存0件で誤パス無し |

## Expert Reviews (code changes only)

### Language Expert

| Aspect | Verdict | Evidence / Improvement |
|---|---|---|
| Best practices | OK | component-ref/dbInfo が import 順で解決可能なことを jar 実体まで追って確認。override は test-data.xml import より後で後勝ち成立。コメントも正確 |
| Codebase style consistency | OK | 隣接 batch の yamlInterpreters/testDataParser と構造・インデント・コメント文言まで一致。boot への配置差は sync-send 固有要件に基づく正当差でコメント明記 |
| GWT test format | N/A | XML設定変更のみ、テストコード変更なし |

### Software-engineering Expert

| Aspect | Verdict | Evidence / Improvement |
|---|---|---|
| Separation of concerns | OK | テスト設定は test リソース2ファイルに完結、本番 boot(src/main) 無汚染。YAML経路とExcel経路が override で明確分離 |
| System integrity | OK | 全参照が import 順含め解決可能、override 成立。単一 testDataParser(YAML) がセットアップ/実行/検証＋再初期化後の応答電文モックまで全フェーズ一貫使用、欠落フェーズなし |
| Maintainability | OK | boot 単一ソース化で重複排除、両ファイルに理由コメント明示で追跡可能 |

## Overall Verdict

- Self-check: OK（修正コミット b31c3ba 後、BUILD SUCCESS）
- QA: OK
- Language expert: OK
- Software-engineering expert: OK
- Ready for user review: Yes
