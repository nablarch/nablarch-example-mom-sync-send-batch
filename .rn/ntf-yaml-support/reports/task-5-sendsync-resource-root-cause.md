# 根本原因レポート — Task #5 テスト失敗（sendSync 応答電文リソース未検出）

- 対象: nablarch-example-mom-sync-send-batch / ブランチ `ntf-yaml-support`
- 事象ID: ②（Task #5 BUILD FAILURE）
- 調査日: 2026-10-06（2026-10-19 追記: §6 失敗ログの詳細、§7 nablarch-document の記載状況と改善案。§2〜§5 を書き直し）
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

## 2. 原因 — テストは 2 つの設定ファイルを切り替えて動く

### 2.1 要点

- このテストでは、**アクションを実行している間だけ、`unit-test.xml` ではなく `messaging-sync-send-boot.xml`（テストショットの `diConfig`）の設定が使われる。**
- YAML 移行では、テストデータを読むコンポーネント `testDataParser` を `YamlTestDataParser` に置き換える設定を、**`unit-test.xml` にだけ**書いていた。
- そのため、アクションの実行中は `testDataParser` が既定の `BasicTestDataParser`（Excel 用）に戻っていた。その間に同期送信のモックが応答電文を読もうとして、削除済みの Excel を探し、失敗した。

### 2.2 フェーズごとに使われる設定ファイル

| フェーズ | 主な処理 | 使われる設定ファイル | `testDataParser`（修正前） |
|---|---|---|---|
| ① テストの準備 | テストショット一覧・準備データ・期待ログの読み込み | `unit-test.xml` | `YamlTestDataParser` ✅ |
| ② **アクションの実行** | 業務アクションの実行。**同期送信モックによる応答電文の読み込み** | **`messaging-sync-send-boot.xml`（`diConfig`）で作り直したリポジトリ** | **`BasicTestDataParser`（Excel 用）** ❌ |
| ③ 実行後の検証 | ステータスコード・テーブル・ログ・要求電文の検証 | `unit-test.xml`（②の後に戻る） | `YamlTestDataParser` ✅ |

①と③が成功し、②だけが失敗した理由がこの表で説明できる。

### 2.3 リポジトリが切り替わる仕組み（ソースで確認）

テストクラスは `@BatchRequestTest` と `BatchRequestTestSupport.execute(シート名)` でアクションを起動する。起動は `MainForRequestTesting`（`nablarch.fw.launcher.Main` のサブクラス）が行う。`MainForRequestTesting` は、実行の直前に `diConfig` でリポジトリを作り直し、実行後に元のリポジトリへ戻す。

```java
// nablarch.test.core.standalone.MainForRequestTesting
protected void setUpSystemRepository(String configFilePath) {
    // configFilePath は diConfig（= messaging-sync-send-boot.xml）
    RepositoryInitializer.reInitializeRepository(configFilePath);
}
public Integer handle(CommandLine cmd, ExecutionContext ctx) {
    try {
        return super.handle(cmd, ctx);                 // ここでアクションが動く（②）
    } finally {
        RepositoryInitializer.revertDefaultRepository(); // unit-test.xml のリポジトリへ戻す（③へ）
    }
}
```

### 2.4 修正前の設定ファイルの状態

| ファイル | 役割 | YAML 関連の設定（修正前） |
|---|---|---|
| `src/test/resources/unit-test.xml` | テスト用の設定。①③で使われる | `messaging-sync-send-boot.xml` を import したうえで、`yamlInterpreters` と `testDataParser`（`YamlTestDataParser`）の上書きを定義 ✅ |
| `src/test/resources/messaging-sync-send-boot.xml` | アクション起動用の設定（`diConfig`）。②で使われる | `defaultMessagingProvider` を `RequestTestingMessagingProvider`（モック）に差し替え、`nablarch/test/test-data.xml` を import。**`test-data.xml` が定義する `testDataParser`（`BasicTestDataParser`）がそのまま使われ、YAML への上書きは無い** ❌ |
| `SendProjectInsertMessageRequestTest/testNormalEnd.yaml` ほか | テストデータ | testShots の `diConfig: "messaging-sync-send-boot.xml"` で②の設定ファイルを指定 |

`unit-test.xml` は `messaging-sync-send-boot.xml` を import しているが、逆方向（`messaging-sync-send-boot.xml` が `unit-test.xml` を読む）の関係は無い。そのため、`unit-test.xml` に書いた上書きは②には効かない。

## 3. 失敗するまでの流れ

### 3.1 処理の流れ

1. 業務アクション `SendProjectInsertMessageAction`（L64）が `MessageSender.sendSync` で同期送信する。
2. ②のリポジトリでは送信先がモック `RequestTestingMessagingProvider` に差し替わっている。モックは実際には送信せず、`RequestTestingMessagingContext.createReceivedMessage` で応答電文をテストデータから用意する。そのために `new RequestTestingSendSyncSupport(テストクラス).getResponseMessage("testAbnormalEnd", ...)` を呼ぶ。
3. `RequestTestingSendSyncSupport.getMessages` は、まず応答電文のテストデータがある場所を調べる。
   ```java
   String resourceName = support.getResourceName("testAbnormalEnd"); // "SendProjectInsertMessageRequestTest/testAbnormalEnd"
   String path = support.getPathOf(resourceName);                     // ← ここで例外
   ```
4. `TestSupport.getPathOf` は、読み込み先の候補（`nablarch.test.resource-root` にテストクラスのパッケージ階層を付けたもの。ここでは `src/test/java/com/nablarch/example`）ごとに、`getTestDataParser().isResourceExisting(basePath, resourceName)` でテストデータの有無を判定する。
   - `getTestDataParser()` は `SystemRepository.get("testDataParser")`、つまり**その時点のリポジトリ**から取得する。②の間は `BasicTestDataParser` が返る。
   - `BasicTestDataParser.isResourceExisting` は `PoiXlsReader.isResourceExisting` を使い、`SendProjectInsertMessageRequestTest.xls` / `.xlsx` があるかを調べる。
   - Excel は Task #4 で削除済みなので false になる。
5. どの候補でも見つからないので、`getPathOf` が `IllegalArgumentException: couldn't find resource [SendProjectInsertMessageRequestTest/testAbnormalEnd] in [src/test/java/com/nablarch/example]` を送出する。
6. 例外は業務アクションの中で起きるので、業務トランザクションはロールバックされる。`PROJECT_INS_REQ.STATUS` は異常終了の `2` に更新され、プロセスはステータスコード 20（予期しない例外）で終了する。
7. ③の検証で、期待値（testNormalEnd：ステータスコード 0・STATUS=1、testAbnormalEnd：ステータスコード 110・FATAL ログ）と一致せず、2 件とも失敗する。

テスト結果（surefire）には 7 の二次的な症状しか出ない。原因の例外（5）はログの Stack Trace にだけ出る（§6.3〜6.6）。

### 3.2 YAML 用パーサが使われていれば成功していた理由

- 判定（4）が `YamlTestDataParser.isResourceExisting`（`YamlLoader.isResourceExisting`）なら、`src/test/java/com/nablarch/example/SendProjectInsertMessageRequestTest/` ディレクトリを探すので、見つかる（§4）。
- 続く応答電文の読み込みは、`RequestTestingSendSyncSupport` が `testDataParser` を `BasicTestDataParser` にキャストして `getSendSyncMessage` を呼ぶ。`YamlTestDataParser` は `BasicTestDataParser` のサブクラスで `getSendSyncMessage` を YAML 用に実装しているので、キャストも読み込みも問題ない（修正後のテスト成功で確認済み）。

### 3.3 Excel があったころ（Task #1）に成功していた理由

- 仕組みは昔から同じで、②では `BasicTestDataParser` が使われていた。
- Excel があったので、`BasicTestDataParser` が `SendProjectInsertMessageRequestTest.xlsx` を見つけ、シート `testAbnormalEnd` から応答電文を読めていた。
- Excel を削除して初めて、②だけ Excel 用パーサが使われていることが表に出た。

### 3.4 web / batch の Example で問題が出なかった理由

- web / batch のテストには、アクションの実行中（②）にテストデータを読む処理が無い。`testDataParser` を使うのは①③だけなので、`unit-test.xml` の上書きだけで足りた。
- 移行手順・テンプレートはこれらの Example を参考に作ったため、「`unit-test.xml` に書けばよい」という前提になっていた。
- ②でテストデータを読むのは、同期送信のモック（MOM の `RequestTestingMessagingProvider`、HTTP の `RequestTestingMessagingClient`）を使うテストである。全 Example のうち該当するのは本 Example と http-messaging-send の 2 つで、http-messaging-send でも同じ対応をした。mom-delayed-send もモックを使うが非同期送信で、応答電文を読まないため影響しない。

## 4. 最初の推定（初版）の訂正

- 初版（`checks/task-5.md`）では、「`YamlLoader.isResourceExisting` はディレクトリ（`File.isDirectory()`）を要求するが、コンバーターはファイルを出力するので一致しない」と推定した。**これは誤り。**
- `YamlLoader.isResourceExisting` は、リソース名の最後の要素（シート名）を除いた `.../SendProjectInsertMessageRequestTest` をディレクトリとして探す（`buildContainerPath`）。このディレクトリは実在する。
- 実際に、プロジェクトのルートをカレントディレクトリにして、実 jar の `YamlLoader.isResourceExisting("src/test/java/com/nablarch/example", "SendProjectInsertMessageRequestTest/testAbnormalEnd")` をリフレクションで呼ぶと **true** が返った。
- つまり、**YAML のファイル構成やコンバーターの出力には問題が無い**。単体で呼べば true なのにテスト中だけ見つからないのは、テスト中（②）には YAML 用パーサがそもそも使われていないから、と説明がつく（§2・§3）。

## 5. 対応内容（解決済み）

### 5.1 修正（コミット `b31c3ba`、2026-10-06）

| ファイル | 変更内容 |
|---|---|
| `src/test/resources/messaging-sync-send-boot.xml` | `yamlInterpreters` のリストと、`testDataParser` を `YamlTestDataParser` に置き換える定義（`dbInfo`・`interpreters` を指定）を追加。追加位置は `nablarch/test/test-data.xml` の import と `dbInfo` の定義の後なので、既定の `BasicTestDataParser` を上書きする |
| `src/test/resources/unit-test.xml` | 同じ定義（`yamlInterpreters`・`testDataParser`）を削除し、設定は `messaging-sync-send-boot.xml` にある旨のコメントを残した |

追加した定義（修正時点）:

```xml
<!-- messaging-sync-send-boot.xml -->
<list name="yamlInterpreters">
  <component-ref name="nullInterpreter"/>
  <component-ref name="dateTimeInterpreter"/>
  <component-ref name="lineSeparatorInterpreter"/>
  <component-ref name="compositeInterpreter"/>
</list>

<component name="testDataParser" class="nablarch.test.core.reader.YamlTestDataParser">
  <property name="dbInfo" ref="dbInfo"/>
  <property name="interpreters" ref="yamlInterpreters"/>
</component>
```

### 5.2 設定を 1 か所にまとめた理由

- `unit-test.xml` は `messaging-sync-send-boot.xml` を import している。`messaging-sync-send-boot.xml` に書けば、①③（`unit-test.xml` 経由）と②（`diConfig` として直接）の**すべてのフェーズ**で `YamlTestDataParser` が使われる。
- 両方に書くと、片方だけ直して食い違うおそれがある。1 か所にまとめれば、その心配が無い。
- `dbInfo` は、もともと `messaging-sync-send-boot.xml` に定義されているので、②でも参照できる。

### 5.3 確認結果

- Excel が無い状態で `LANG=ja_JP.UTF-8 mvn clean test` を実行し、**BUILD SUCCESS（Tests run: 2, Failures: 0, Errors: 0）**。
- ログで、②のリポジトリ再初期化でも `testDataParser` が `YamlTestDataParser` に置き換わることと、応答電文が `YamlTestDataParser.getSendSyncMessage` で YAML から読まれることを確認した。
- 別事象①（errorMode 行の `0` が消える）も、このテスト成功により挙動に影響しないことを確認した（不具合ではない）。

### 5.4 残っている点・他への影響

- `yamlInterpreters` に `nullInterpreter`・`lineSeparatorInterpreter` が残っている。YAML では指定しないのが正しい（nablarch-document の `setup/testdata.rst` 参照）。これは別事象 #5 で対応し、本件では変更しない（§7.3）。
- 同期送信のモックを使う他のプロジェクトでも、YAML に移行するときは **`diConfig` の設定ファイルにも `YamlTestDataParser` の置き換えを書く**必要がある。`unit-test.xml` がそのファイルを import していれば、`diConfig` 側の 1 か所に書けば足りる。移行手順書とドキュメントへの反映は §7 にまとめた。

## 6. 失敗ログの詳細（修正前の再現ログ）

- ログファイル: `reports/logs/task-5-sendsync-resource-failure.log`（923 行）
- 取得日時: 2026-10-08 15:26（修正前の状態＝`messaging-sync-send-boot.xml` に YAML 上書きなし、Excel 削除済みで `LANG=ja_JP.UTF-8 mvn clean test`）
- 以下の行番号はこのログファイルの行番号。ログの共通部分（`boot_proc = [] proc_sys = [] req_id = [null] usr_id = [null]`）は省略した。

### 6.1 テスト側リポジトリ（unit-test.xml）の起動 — YAML パーサは有効

```
L4   15:26:06.452 -DEBUG- XmlComponentDefinitionLoader load component config file. file = classpath:unit-test.xml
L5   15:26:06.507 -DEBUG- XmlComponentDefinitionLoader load component config file. file = classpath:messaging-sync-send-boot.xml
L36  15:26:06.790 -WARN-  XmlComponentDefinitionLoader override component classname was not matched.  replace all component configuration.
                          component name = defaultMessagingProvider, defined component definition classname = nablarch.fw.messaging.provider.JndiLookingUpJmsMessagingProvider,
                          override component definition classname = nablarch.test.core.messaging.RequestTestingMessagingProvider
L39  15:26:06.798 -WARN-  XmlComponentDefinitionLoader override component classname was not matched.  replace all component configuration.
                          component name = testDataParser, defined component definition classname = nablarch.test.core.reader.BasicTestDataParser,
                          override component definition classname = nablarch.test.core.reader.YamlTestDataParser
L55  15:26:06.902 -INFO-  nablarch.test.core.reader.YamlTestDataParser YamlTestDataParser does not use TestDataReader; the injected value is ignored.
```

- L39：`unit-test.xml` で `testDataParser` が `BasicTestDataParser` から `YamlTestDataParser` に置き換わっている（修正前は `unit-test.xml` 側に上書きがあった）。
- L140：`adding expected log message. [Log level=[FATAL] messages=[メッセージ受信側でValidationエラーが発生しました。]]`。testAbnormalEnd.yaml の expectedLog1 を YAML から読めている。**テスト側の読み込みは成功している。**

### 6.2 アクション起動時のリポジトリ再初期化（diConfig）— YAML パーサの上書きが無い

```
L141 15:26:08.610 -DEBUG- nablarch.test.core.standalone.TestShot Invoking test target.
L142 15:26:08.611 -DEBUG- nablarch.test.core.standalone.TestShot 	request path =[SendProjectInsertMessageAction]
L144 15:26:08.611 -DEBUG- nablarch.test.core.standalone.TestShot 	command line options={setUpTable=1, expectedTable=1, expectedLog=expectedLog1,
                          responseMessage=case1, userId=batch_user, expectedMessage=case1, requestPath=SendProjectInsertMessageAction,
                          diConfig=messaging-sync-send-boot.xml}
L145 15:26:08.661 -DEBUG- XmlComponentDefinitionLoader load component config file. file = classpath:messaging-sync-send-boot.xml
L172 15:26:08.777 -DEBUG- XmlComponentDefinitionLoader load component config file. file = classpath:nablarch/test/test-data.xml
L173 15:26:08.779 -DEBUG- XmlComponentDefinitionLoader load component config file. file = classpath:nablarch/test/test-data-interpreter.xml
L174 15:26:08.782 -WARN-  ... component name = systemTimeProvider   ... override ... = nablarch.test.FixedSystemTimeProvider
L175 15:26:08.782 -WARN-  ... component name = businessDateProvider ... override ... = nablarch.test.FixedBusinessDateProvider
L176 15:26:08.782 -WARN-  ... component name = defaultMessagingProvider ... override ... = nablarch.test.core.messaging.RequestTestingMessagingProvider
L177 15:26:08.782 -WARN-  XmlComponentDefinitionLoader component property was overridden. component name = initializer, property = initializeList
```

- L144：テストショットの `diConfig=messaging-sync-send-boot.xml` でアクションを起動している。
- L145：`unit-test.xml` ではなく `messaging-sync-send-boot.xml` から**リポジトリを作り直している**（§2 の `MainForRequestTesting#setUpSystemRepository`）。
- L174〜177：上書きの WARN が出ているのは systemTimeProvider・businessDateProvider・defaultMessagingProvider・initializer だけ。**6.1 の L39 にあたる `testDataParser` → `YamlTestDataParser` の WARN が無い。** つまりアクション実行中の `testDataParser` は、L172 の `nablarch/test/test-data.xml` が定義する `BasicTestDataParser`（Excel 用）のままである。**これがログ上の決定的な根拠。**

### 6.3 例外の発生箇所（アクションスレッド）

`DataReadHandler` が業務アクションの異常終了として出力したもの（L263〜）:

```
L263 15:26:09.022 -WARN- nablarch.fw.handler.DataReadHandler [202610081526089330002] req_id = [SendProjectInsertMessageAction] usr_id = [batch_user] application was abnormal end.
	input data = {PROJECT_TYPE=development, ..., PROJECT_INS_REQ_ID=1, ...}
Stack Trace Information :
java.lang.IllegalArgumentException: couldn't find resource [SendProjectInsertMessageRequestTest/testAbnormalEnd] in [src/test/java/com/nablarch/example]
	at nablarch.test.TestSupport.getPathOf(TestSupport.java:295)
	at nablarch.test.core.messaging.RequestTestingSendSyncSupport.getMessages(RequestTestingSendSyncSupport.java:112)
	at nablarch.test.core.messaging.RequestTestingSendSyncSupport.getResponseMessageByRequestId(RequestTestingSendSyncSupport.java:82)
	at nablarch.test.core.messaging.RequestTestingSendSyncSupport.getResponseMessage(RequestTestingSendSyncSupport.java:64)
	at nablarch.test.core.messaging.RequestTestingMessagingProvider$RequestTestingMessagingContext.createReceivedMessage(RequestTestingMessagingProvider.java:196)
	at nablarch.test.core.messaging.RequestTestingMessagingProvider$RequestTestingMessagingContext.sendSync(RequestTestingMessagingProvider.java:152)
	at nablarch.fw.messaging.MessageSender.sendSyncWithProvider(MessageSender.java:149)
	at nablarch.fw.messaging.MessageSender.sendSync(MessageSender.java:83)
	at com.nablarch.example.SendProjectInsertMessageAction.handle(SendProjectInsertMessageAction.java:64)
	at com.nablarch.example.SendProjectInsertMessageAction.handle(SendProjectInsertMessageAction.java:31)
	at nablarch.fw.ExecutionContext.handleNext(ExecutionContext.java:95)
	at nablarch.fw.handler.DataReadHandler.handle(DataReadHandler.java:59)
	...
	at nablarch.fw.handler.LoopHandler.handle(LoopHandler.java:68)
	...
	at nablarch.fw.handler.MultiThreadExecutionHandler$1.call(MultiThreadExecutionHandler.java:334)
	at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)
	...
	at java.base/java.lang.Thread.run(Thread.java:840)
```

- 例外は業務アクション（`SendProjectInsertMessageAction.java:64` の `MessageSender.sendSync`）からモック（`RequestTestingMessagingProvider`）経由で、`TestSupport.getPathOf` で発生している。
- `TestSupport.getPathOf` は `getTestDataParser().isResourceExisting(basePath, resourceName)` でリソースの有無を判定する（§3）。ここで使われるのが 6.2 の `BasicTestDataParser` なので、`SendProjectInsertMessageRequestTest.xlsx` を探して見つからない。
- 行番号 295 は実行時 jar（`nablarch-testing` 実行バージョン）のもの。ローカルの `nablarch-testing` ソースとは行番号がずれる（ローカルでは L310 付近）。
- 業務トランザクションはロールバックされ、`UPDATE PROJECT_INS_REQ SET STATUS = '2'`（異常終了ステータス）が実行・コミットされている（L297〜L308）。

### 6.4 Caused by（メインスレッド側でラップされた例外）

マルチスレッド実行ハンドラがスレッドの終了状態を回収するとき、`ExecutionException` でラップして再送出している（L310〜）:

```
L310 15:26:09.054 -WARN- nablarch.core.log.app.FailureLogUtil [202610081526088160001] req_id = [SendProjectInsertMessageAction] usr_id = [batch_user]
                         fail_code = [MSG99999] an unexpected exception occurred.
Stack Trace Information :
java.util.concurrent.ExecutionException: java.lang.IllegalArgumentException: couldn't find resource [SendProjectInsertMessageRequestTest/testAbnormalEnd] in [src/test/java/com/nablarch/example]
	at java.base/java.util.concurrent.FutureTask.report(FutureTask.java:122)
	at java.base/java.util.concurrent.FutureTask.get(FutureTask.java:191)
	at nablarch.fw.handler.MultiThreadExecutionHandler.reportThreadStatus(MultiThreadExecutionHandler.java:282)
	at nablarch.fw.handler.MultiThreadExecutionHandler.terminate(MultiThreadExecutionHandler.java:262)
	at nablarch.fw.handler.MultiThreadExecutionHandler.handle(MultiThreadExecutionHandler.java:128)
	...
	at nablarch.fw.handler.StatusCodeConvertHandler.handle(StatusCodeConvertHandler.java:83)
	at nablarch.fw.launcher.Main.handle(Main.java:135)
	at nablarch.test.core.standalone.MainForRequestTesting.handle(MainForRequestTesting.java:22)
	at nablarch.test.core.standalone.TestShot.invokeTarget(TestShot.java:123)
	at nablarch.test.core.standalone.TestShot.executeTestShot(TestShot.java:85)
	at nablarch.test.core.standalone.StandaloneTestSupportTemplate.executeTestShot(StandaloneTestSupportTemplate.java:166)
	at nablarch.test.core.standalone.StandaloneTestSupportTemplate.executeTest(StandaloneTestSupportTemplate.java:118)
	at nablarch.test.core.standalone.StandaloneTestSupportTemplate.execute(StandaloneTestSupportTemplate.java:96)
	at nablarch.test.core.standalone.StandaloneTestSupportTemplate.execute(StandaloneTestSupportTemplate.java:58)
	at com.nablarch.example.SendProjectInsertMessageRequestTest.testAbnormalEnd(SendProjectInsertMessageRequestTest.java:22)
	... (JUnit / surefire のフレーム)
Caused by: java.lang.IllegalArgumentException: couldn't find resource [SendProjectInsertMessageRequestTest/testAbnormalEnd] in [src/test/java/com/nablarch/example]
	at nablarch.test.TestSupport.getPathOf(TestSupport.java:295)
	at nablarch.test.core.messaging.RequestTestingSendSyncSupport.getMessages(RequestTestingSendSyncSupport.java:112)
	at nablarch.test.core.messaging.RequestTestingSendSyncSupport.getResponseMessageByRequestId(RequestTestingSendSyncSupport.java:82)
	at nablarch.test.core.messaging.RequestTestingSendSyncSupport.getResponseMessage(RequestTestingSendSyncSupport.java:64)
	at nablarch.test.core.messaging.RequestTestingMessagingProvider$RequestTestingMessagingContext.createReceivedMessage(RequestTestingMessagingProvider.java:196)
	at nablarch.test.core.messaging.RequestTestingMessagingProvider$RequestTestingMessagingContext.sendSync(RequestTestingMessagingProvider.java:152)
	at nablarch.fw.messaging.MessageSender.sendSyncWithProvider(MessageSender.java:149)
	at nablarch.fw.messaging.MessageSender.sendSync(MessageSender.java:83)
	at com.nablarch.example.SendProjectInsertMessageAction.handle(SendProjectInsertMessageAction.java:64)
	... (6.3 と同じ)
```

- ラップしている側のスタック（`MainForRequestTesting.handle` ← `TestShot.invokeTarget` ← `SendProjectInsertMessageRequestTest.testAbnormalEnd:22`）を見ると、テストクラスから `MainForRequestTesting` を通してアクションを起動していることがわかる。§2 の「diConfig でリポジトリを再初期化して実行」と一致する。
- **根本の例外（Caused by）は 6.3 と同じ `IllegalArgumentException: couldn't find resource`**。別の原因が隠れているわけではない。
- 続けて L457 `-FATAL- MONITOR ... fail_code = [MSG99999] an unexpected exception occurred.`、L490 `-FATAL- nablarch.core.log.app.FailureLogUtil ...` でも同じ例外が出力されている。プロセスの終了コードは 20（予期しない例外）になる。

### 6.5 testNormalEnd も同じ機序

- L601〜：`couldn't find resource [SendProjectInsertMessageRequestTest/testNormalEnd] in [src/test/java/com/nablarch/example]`（スタックは 6.3 と同じ）
- L649 / L755：`ExecutionException` と `Caused by:`（6.4 と同じ構造）
- L792 / L825：FATAL（MONITOR / FailureLogUtil）

### 6.6 surefire の結果

```
L871 [ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 3.101 s <<< FAILURE! -- in com.nablarch.example.SendProjectInsertMessageRequestTest
L873 java.lang.AssertionError: no=[1] case=[受信側でバリデーションに失敗した場合] expected status code is [110].but was [20] ;
     following log message(s) expected. but not found. [Log level=[FATAL] messages=[メッセージ受信側でValidationエラーが発生しました。]]
L911 [ERROR]   SendProjectInsertMessageRequestTest.testNormalEnd:16 no=[1] case=[メッセージ送信成功] expected status code is [0].but was [20]
     table=PROJECT_INS_REQ line=1 column=STATUS expected:<[1]> but was:<[2]>
L913 [ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.0:test (default-test) on project nablarch-example-mom-sync-send-batch: There are test failures.
```

- テストの失敗は「Failures」（アサーションの不一致）として報告され、「Errors」にはならない。モックの例外はアクション内で発生し、フレームワークがステータスコード 20 に変換するので、テストから見えるのは「期待したステータスコード（0 / 110）と違う」「期待した FATAL ログが無い」「STATUS が 1 ではなく 2」という**二次的な症状だけ**である。
- 根本原因（`couldn't find resource`）を知るには、surefire のサマリではなく、ログ本文の Stack Trace（6.3）や Caused by（6.4）を見る必要がある。

### 6.7 ログから読み取れる判別ポイント（同種事象の切り分け用）

| 確認箇所 | 本事象での値 | 意味 |
|---|---|---|
| diConfig 読み込み後の上書き WARN に `component name = testDataParser` があるか | **無い**（L174〜177） | 実行時リポジトリの parser は `BasicTestDataParser`（Excel 用） |
| 例外の発生元 | `RequestTestingSendSyncSupport.getMessages` → `TestSupport.getPathOf` | 送信モックが応答電文を読む経路。テスト側の読み込み（setup / expected）では起きていない |
| メッセージの `[resource]` | `<テストクラス名>/<シート名>` | `YamlTestDataParser` なら `<テストクラス名>/` ディレクトリを探す（§4）。`BasicTestDataParser` なら `<テストクラス名>.xlsx` を探す |
| surefire の分類 | Failures（status 20） | Errors ではない。サマリだけでは原因がわからない |

## 7. nablarch-document の記載状況と改善案（YamlTestDataParser）

確認対象: `/home/k10949/workspace/nablarch-document/_build/html/`（2026-10-02 ビルド）。ソースは同リポジトリの `ja/development_tools/testing_framework/` 配下の rst（ブランチ `ntf-yaml-support`）で、該当箇所の内容は HTML と一致することを確認した。
`YamlTestDataParser` / `nablarch-testing-yaml` が出てくるページ: `setup/testdata.html`、`setup/deal_unit_test/http_messaging.html`、`tools/testdata_converter.html`、`implementation/testdata_notation.html`。本件に関わる設定場所のルールは `setup/introduction.html`、`setup/request_unit_test/{mom,batch,http_messaging}.html` にある。

### 7.1 現状の記載

| ページ（rst） | 記載内容 | 本件との関係 |
|---|---|---|
| `setup/testdata.html`（`setup/testdata.rst` L30〜58「テストデータの形式をYAMLに変更する」） | `nablarch-testing-yaml` を依存に追加し、`testDataParser` を `YamlTestDataParser` に差し替える。interpreters は `dateTimeInterpreter`・`compositeInterpreter` の 2 つだけ。`dbInfo` を指定、`testDataReader` は指定しない。設定先は「テスト用のコンポーネント設定ファイル」（introduction へのリンク）。ページ冒頭に「テストの種類によらず共通」とある | **設定先が `unit-test.xml` だけで足りるのか、diConfig にも要るのかが書かれていない。** 本件はここを `unit-test.xml` だけに設定して起きた |
| `setup/introduction.html`（`setup/introduction.rst` L44） | 「テスト用のコンポーネント設定ファイル」は `unit-test.xml`。ただし Nablarch バッチとメッセージングのリクエスト単体テストは diConfig で再初期化するので、**それらの設定ページで**書いている設定は diConfig のファイルに書く | 例外のルールが「バッチ・メッセージングの設定ページ」に限られている。`testdata.html`（共通ページ）の `testDataParser` がどちらに属するかは読み取れない |
| `setup/request_unit_test/mom.html`（`setup/request_unit_test/mom.rst` L17、「同期応答メッセージ送信用のメッセージングプロバイダに差し替える」） | 設定は diConfig のファイルに書く（`unit-test.xml` の上書きは反映されない）。`RequestTestingMessagingProvider` の登録方法を記載 | **プロバイダが実行中に `testDataParser` で応答電文を読むこと、そのため YAML では diConfig 側にも `YamlTestDataParser` が要ることが書かれていない** |
| `setup/request_unit_test/batch.html`（`setup/request_unit_test/batch.rst` L19） | mom と同じ注記（diConfig に書く） | バッチから同期送信する場合も同じ問題が起きるが、記載が無い |
| `setup/request_unit_test/http_messaging.html`（`setup/request_unit_test/http_messaging.rst` L33） | `RequestTestingMessagingClient` の登録。要求電文の検証は実行後（デフォルト設定に戻った後）なので、`format` は `unit-test.xml` 側に登録する、という**逆方向の注意**はある | 応答電文は実行中に diConfig 側の `testDataParser` で読む（`RequestTestingMessagingClient` → `RequestTestingSendSyncSupport`。ソースで確認）。**YAML 時に diConfig 側にも上書きが要ることが書かれていない**（http-messaging-send の #1 で同じ事象が起きた） |
| `setup/deal_unit_test/http_messaging.html`（取引単体テスト） | `messagingTestDataParser` を `YamlTestDataParser` にする設定例と、`fileExtensions` に `sendSyncTestData` を設定しない注意 | 取引単体テスト用の別コンポーネント（`messagingTestDataParser`）の話で、リクエスト単体テストの `testDataParser` とは別物。記載は十分 |

**要点**: `testDataParser` は 1 回のテストの中で 2 つのリポジトリから使われる。

1. テスト側（`unit-test.xml`）：テストショット一覧・準備データ・期待値・期待ログの読み込み
2. アクション実行中（diConfig で再初期化したリポジトリ）：同期送信モック（`RequestTestingMessagingProvider` / `RequestTestingMessagingClient`）による応答電文の読み込み（`RequestTestingSendSyncSupport#getMessages` → `TestSupport#getPathOf` / `getTestDataParser()`、および `BasicTestDataParser` にキャストした `getSendSyncMessage`）

Excel のときはどちらのリポジトリでもデフォルトの `BasicTestDataParser` が使われるので、この 2 か所を意識する必要が無かった。YAML では、2 のリポジトリにも上書きが無いと Excel 用パーサが使われる。ドキュメントはこの点に触れていない。`testdata.html` を素直に読むと `unit-test.xml` だけに設定することになり（本件）、`introduction.html` のルールを広げて読むと diConfig だけに設定することになる（その場合、`unit-test.xml` が diConfig を import していなければ、テスト側の読み込みが Excel 用パーサになって失敗する）。

### 7.2 改善案

優先度は 案1 > 案2 > 案3 > 案4。いずれもドキュメント（rst）の修正案で、本タスクでは nablarch-document は変更していない。

**案1: `setup/testdata.rst`「テストデータの形式をYAMLに変更する」に、diConfig を使うテストでの設定先を追記する**

`testDataReader` の段落の後に、例えば次の「重要」を追加する。

> Nablarchバッチアプリケーションとメッセージングのリクエスト単体テストで、テスト対象が同期応答メッセージ送信またはHTTPメッセージ送信を行う場合、`testDataParser`はテストショット一覧の`diConfig`に指定したコンポーネント設定ファイルにも設定する。テスト対象の実行中は、`diConfig`のファイルからシステムリポジトリが再初期化され、モックアップクラスはそのリポジトリの`testDataParser`で応答電文を読み込む。`unit-test.xml`にだけ設定すると、Excel形式用の`BasicTestDataParser`が使われ、テストの実行時に`couldn't find resource`で失敗する。`unit-test.xml`が`diConfig`のファイルをimportしていれば、`diConfig`のファイル1か所に設定すればよい。importしていない場合は、両方に設定する。`dbInfo`も、`diConfig`のファイルから参照できるように登録する。

- 設定例として、本 Example の構成（`messaging-sync-send-boot.xml` に `dbInfo`・`testDataParser` を置き、`unit-test.xml` はそれを import する）を短く示すとわかりやすい。

**案2: `setup/request_unit_test/mom.rst`・`http_messaging.rst`（必要なら `batch.rst`）のモックの節に、応答電文の読み込みについて追記する**

- mom.rst「同期応答メッセージ送信用のメッセージングプロバイダに差し替える」と http_messaging.rst「モックアップクラスを登録する」に、次の趣旨を 1 段落ずつ追加する。
  - モックは、テスト対象の実行中に、`diConfig` のリポジトリの `testDataParser` でテストクラスのテストデータから応答電文を読み込む。
  - YAML 形式のテストデータでは、`diConfig` のファイルでも `testDataParser` を `YamlTestDataParser` に差し替える（案1 へリンク）。
- http_messaging.rst には `format` は `unit-test.xml` に登録する（実行後の検証で使うため）という逆向きの注意がすでにある。並べて「どの処理がどちらのリポジトリで動くか」を表にすると、取り違えを防げる。

| 処理 | タイミング | 使われるリポジトリ | 例 |
|---|---|---|---|
| テストショット・準備データ・期待値の読み込み | 実行前・実行後 | `unit-test.xml` | `testDataParser` |
| 応答電文の読み込み（モック） | **実行中** | **`diConfig`** | `testDataParser`（YAML なら上書きが要る） |
| 要求電文の検証 | 実行後 | `unit-test.xml` | `filePathSetting` の `format` |

**案3: `setup/introduction.rst` L44 の例外ルールの範囲を明確にする**

- 今の「それらの設定ページで『テスト用のコンポーネント設定ファイル』と書いている設定は、diConfig に指定するファイルに記述する」は、共通ページ（`testdata.rst` など）の設定がどちらに属するか判断できない。
- 「テストデータの読み込みに関わる設定（`testDataParser` など）は `unit-test.xml` で使われるが、テスト対象の実行中にも使われるものは diConfig にも必要」と一文添え、案1 にリンクする。

**案4: トラブルシュートとして症状を載せる（任意）**

- 本件はテスト結果では `expected status code is [0].but was [20]` などの二次的な症状しか出ず（§6.6）、原因に辿り着きにくい。
- `couldn't find resource [<テストクラス名>/<シート名>] in [...]` が `RequestTestingSendSyncSupport` から出ている場合は、「diConfig 側の `testDataParser` が `YamlTestDataParser` になっていない」ことを疑う、という切り分けを「重要」か FAQ に書いておくと、移行時の調査が短くなる（§6.7 の表が使える）。
- （ドキュメント外・参考）フレームワークの改善として、`TestSupport#getPathOf` の例外メッセージに使用中の `testDataParser` のクラス名を含めれば、ログだけで判別できるようになる。

### 7.3 補足（ドキュメントは正しく、Example 側が合っていない点）

- `setup/testdata.rst` は interpreters を `dateTimeInterpreter`・`compositeInterpreter` の 2 つだけにするよう書いている。一方、本 Example の `messaging-sync-send-boot.xml` の `yamlInterpreters` には、今も `nullInterpreter`・`lineSeparatorInterpreter` が残っている（`b31c3ba` で unit-test.xml から移したときの内容のまま）。これは別事象 #5（yamlInterpreters に NullInterpreter が残っている）で対応する。本レポートの範囲では変更しない。

## 付録: エビデンス（調査時点＝修正前の状態）

- 実ファイル（修正前）: `src/test/resources/unit-test.xml`（override あり）, `src/test/resources/messaging-sync-send-boot.xml`（override なし・モック定義・test-data.xml import）, testShots `diConfig: messaging-sync-send-boot.xml`
  - ※修正後（b31c3ba）は override を messaging-sync-send-boot.xml に集約し、unit-test.xml 側は削除。
- ソース（`*-sources.jar`）: `TestSupport`(getResourceName/getPathOf/getPathResourceExisting/getTestDataParser=SystemRepository.get("testDataParser")), `RequestTestingSendSyncSupport`(getMessages L112), `RequestTestingMessagingProvider`(mock), `BasicTestDataParser`/`PoiXlsReader.isResourceExisting`（Excel 探索）, `YamlLoader.isResourceExisting`/`buildContainerPath`（ディレクトリ判定）
- 実機確認（修正前）: YAML あり・Excel なしで `mvn test` が再現性をもって同一エラー。`YamlLoader.isResourceExisting(...)` 単体呼び出しは true。修正後は同条件で BUILD SUCCESS。
