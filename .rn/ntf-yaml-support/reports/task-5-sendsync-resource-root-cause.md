# 根本原因レポート — Task #5 テスト失敗（sendSync 応答電文リソース未検出）

- 対象: nablarch-example-mom-sync-send-batch / ブランチ `ntf-yaml-support`
- 事象ID: ②（Task #5 BUILD FAILURE）
- 調査日: 2026-10-06
- 結論: **アクション起動用 diConfig（`messaging-sync-send-boot.xml`）に `YamlTestDataParser` 上書きが無く、実行時のメッセージングモックが既定の `BasicTestDataParser`（Excelリーダー）で応答電文を探すため。Excel 削除後は該当ブックが見つからず `couldn't find resource` となる。** YAML のファイル/ディレクトリ構造の問題ではない（初版の推測を訂正）。

---

## 1. 事象（What）

Excel 削除後の `mvn clean test` が BUILD FAILURE。testNormalEnd / testAbnormalEnd がともに失敗し、送信アクションが異常終了（status 20）。

```
java.lang.IllegalArgumentException: couldn't find resource
  [SendProjectInsertMessageRequestTest/testAbnormalEnd] in [src/test/java/com/nablarch/example]
    at nablarch.test.TestSupport.getPathOf(TestSupport.java:295)
    at nablarch.test.core.messaging.RequestTestingSendSyncSupport.getMessages(RequestTestingSendSyncSupport.java:112)
    at ...RequestTestingMessagingProvider$RequestTestingMessagingContext.createReceivedMessage(...)
    at ...MessageSender.sendSync(...) at SendProjectInsertMessageAction.handle(...:64)
```

一方、`YamlTestDataParser` 自体は有効化されている（起動ログに override と `YamlTestDataParser does not use TestDataReader;` が出力）。また **テストのセットアップ（testShots / setup / expectedLog の読み込み）は YAML から成功**している（例: ログに testAbnormalEnd.yaml の expectedLog1 `メッセージ受信側でValidationエラーが発生しました。` を読み込んだ記録あり）。失敗するのは **アクション実行中にモックが応答電文を取得する箇所のみ**。

## 2. 2つのリポジトリ（Where）— ここが核心

本テストは **2つの異なる DI リポジトリ** を使い分けている。

| フェーズ | 使用設定 | `testDataParser` の実体 |
|---|---|---|
| テストのセットアップ／検証 | `unit-test.xml` | **YamlTestDataParser**（本移行で上書き追加） |
| アクション本体の実行 | `messaging-sync-send-boot.xml`（testShots の `diConfig`） | **BasicTestDataParser**（＝Excelリーダー。上書きなし） |

根拠（本リポジトリの実ファイル）:

- `src/test/resources/unit-test.xml`
  - L7: `<import file="messaging-sync-send-boot.xml"/>`
  - L13-18: `yamlInterpreters` リスト追加（本移行）
  - L20-24: `<component name="testDataParser" class="nablarch.test.core.reader.YamlTestDataParser">`（本移行の上書き）
- `src/test/resources/messaging-sync-send-boot.xml`（＝アクション起動用 diConfig）
  - L13-14: `<component name="defaultMessagingProvider" class="nablarch.test.core.messaging.RequestTestingMessagingProvider"/>`（応答電文モック）
  - L17: `<import file="nablarch/test/test-data.xml"/>` → この中で `testDataParser` が **BasicTestDataParser** として定義される
  - **`YamlTestDataParser` 上書きも `yamlInterpreters` も無い**
- `src/test/java/.../SendProjectInsertMessageRequestTest/testNormalEnd.yaml` 他
  - testShots の `diConfig: "messaging-sync-send-boot.xml"` … アクションはこの config で起動される

NTF のリクエスト単体テストは、アクションを `-diConfig messaging-sync-send-boot.xml` 相当で起動する。このときアクション実行コンテキストのリポジトリは **messaging-sync-send-boot.xml 由来**であり、`unit-test.xml` でのみ行った `testDataParser` 上書きは**効かない**。

### 確定した機序: 実行中はリポジトリが入れ替わる

テストは `@BatchRequestTest` + `BatchRequestTestSupport.execute(sheetName)` でアクションを起動する。起動は `MainForRequestTesting`（`nablarch.fw.launcher.Main` 派生）が担い、ソース上以下のとおりリポジトリを **diConfig で再初期化し、実行後に戻す**:

```java
// MainForRequestTesting
protected void setUpSystemRepository(String configFilePath) {
    RepositoryInitializer.reInitializeRepository(configFilePath);   // configFilePath = diConfig = messaging-sync-send-boot.xml
}
public Integer handle(CommandLine cmd, ExecutionContext ctx) {
    try { return super.handle(cmd, ctx); }
    finally { RepositoryInitializer.revertDefaultRepository(); }     // 実行後に unit-test.xml 側へ復帰
}
// Main 本体(nablarch-fw-standalone): diConfig を読み
//   SystemRepository.load(new DiContainer(new XmlComponentDefinitionLoader(diConfig)))  (L236-242)
```

ライフサイクル:

| フェーズ | 有効なリポジトリ | `testDataParser` |
|---|---|---|
| セットアップ（testShots 等の読込） | `unit-test.xml` | YamlTestDataParser ✅ |
| **アクション実行（sendSync のモック読込）** | **`messaging-sync-send-boot.xml` に再初期化** | **BasicTestDataParser（Excel）** ← 失敗箇所 |
| 実行後の検証 | `unit-test.xml` に復帰 | YamlTestDataParser |

すなわち、アクション実行中は **`unit-test.xml` は参照されない**。`unit-test.xml` だけに YAML 上書きを置いても、実行中のモック読込には効かない。

## 3. 失敗の連鎖（How）

1. アクションが `MessageSender.sendSync` を実行。
2. モック `RequestTestingMessagingProvider$RequestTestingMessagingContext.createReceivedMessage` が応答電文を用意するため
   `new RequestTestingSendSyncSupport(testClass).getResponseMessage("testAbnormalEnd", ...)` を呼ぶ。
3. `RequestTestingSendSyncSupport.getMessages`:
   ```java
   String resourceName = support.getResourceName("testAbnormalEnd");  // = "SendProjectInsertMessageRequestTest/testAbnormalEnd"
   String path = support.getPathOf(resourceName);                      // ← ここで例外
   ```
4. `TestSupport.getPathOf` → `getPathResourceExisting` → `getTestDataParser().isResourceExisting(basePath, resourceName)`。
   - このとき `getTestDataParser()` は **実行時リポジトリ**（messaging-sync-send-boot.xml）の `testDataParser` ＝ **BasicTestDataParser** を返す。
   - `BasicTestDataParser.isResourceExisting` → `PoiXlsReader.isResourceExisting`：basePath 配下に `SendProjectInsertMessageRequestTest.xls` / `.xlsx` が存在するかを探す。
   - **Excel は Task #4 で削除済み → 見つからず false。**
5. 全候補パスで false → `getPathResourceExisting` が null → `getPathOf` が
   `couldn't find resource [SendProjectInsertMessageRequestTest/testAbnormalEnd]` を送出。
6. 例外によりアクションが異常終了（status 20）。期待値（0 / 110）と不一致、期待 FATAL ログも未出力でテスト失敗。

### Task #1（Excel あり）で成功していた理由
Excel 存在時は、同じ BasicTestDataParser（PoiXlsReader）が `SendProjectInsertMessageRequestTest.xlsx` を発見し、シート `testAbnormalEnd` から応答電文を読めていた。モックが Excel リーダーを使う構造自体は従来どおりで、Excel があれば動く。

### web / batch Example で顕在化しなかった理由
web / batch は、テスト対象アクションを別 diConfig で起動し **その実行中にメッセージングモックがテストデータを再読込する**という経路を持たない。`testDataParser` を使うのはテスト側リポジトリ（unit-test.xml＝YAML 上書き済み）だけなので、上書き不足が表に出なかった。本 Example（MOM 同期送信）は、モックが実行時に応答電文を読むため、アクション起動 config 側の `testDataParser` 実体が問題になる。

## 4. 初版レポートの訂正

本調査の初版（checks/task-5.md）では「`YamlLoader.isResourceExisting` がディレクトリ（`File.isDirectory()`）を要求し、コンバータがファイルを出力するため不整合」と推定した。これは **誤り**。実測では、プロジェクトルートを CWD として
`YamlLoader.isResourceExisting("src/test/java/com/nablarch/example", "SendProjectInsertMessageRequestTest/testAbnormalEnd")`
は **true** を返す（`buildContainerPath` は末尾セグメントを除いた `.../SendProjectInsertMessageRequestTest` を対象とし、これは実在するディレクトリ）。
したがって `YamlTestDataParser` が使われていれば解決は成功する。失敗の真因は §2・§3 のとおり **実行時リポジトリの `testDataParser` が Excel リーダーのまま**である点。

（検証: 実 jar の `YamlLoader.isResourceExisting` をリフレクションで直接呼び出し true を確認。テスト実行時のみ false になるのは、実行時リポジトリの parser が BasicTestDataParser だからという整合的な説明が付く。）

## 5. 影響と実施した対応（解決済み）

- 本件は **設定の不足**（アクション起動 config に YAML 対応が無い）に起因し、コンバータ出力や YAML 構造の問題ではない。
- **実施した対応（コミット `b31c3ba`）**:
  1. **`src/test/resources/messaging-sync-send-boot.xml` に** `yamlInterpreters` と `YamlTestDataParser` の `testDataParser` 上書きを追加（アクション実行時リポジトリでも YAML リーダーを使う）。`unit-test.xml` は同ファイルを import するため、重複定義は削除して**単一ソース化**。本 Example（MOM同期送信）固有の、アクション起動 config への反映。
  2. 再テスト `mvn clean test` → **BUILD SUCCESS（Tests run: 2, Failures: 0）**。`YamlTestDataParser.getSendSyncMessage` による応答電文の YAML 読込も含め、起動時・実行時の両経路で YAML リーダーが使われることをログで確認。
- 残る errorMode 列の差（①）は、この再テスト全パスにより挙動非影響であることを確認済み（defect ではない）。

## 付録: エビデンス（調査時点＝修正前の状態）

- 実ファイル（修正前）: `src/test/resources/unit-test.xml`（override あり）, `src/test/resources/messaging-sync-send-boot.xml`（override なし・モック定義・test-data.xml import）, testShots `diConfig: messaging-sync-send-boot.xml`
  - ※修正後（b31c3ba）は override を messaging-sync-send-boot.xml に集約し、unit-test.xml 側は削除。
- ソース（`*-sources.jar`）: `TestSupport`(getResourceName/getPathOf/getPathResourceExisting/getTestDataParser=SystemRepository.get("testDataParser")), `RequestTestingSendSyncSupport`(getMessages L112), `RequestTestingMessagingProvider`(mock), `BasicTestDataParser`/`PoiXlsReader.isResourceExisting`（Excel 探索）, `YamlLoader.isResourceExisting`/`buildContainerPath`（ディレクトリ判定）
- 実機確認（修正前）: YAML あり・Excel なしで `mvn test` が再現性をもって同一エラー。`YamlLoader.isResourceExisting(...)` 単体呼び出しは true。修正後は同条件で BUILD SUCCESS。
