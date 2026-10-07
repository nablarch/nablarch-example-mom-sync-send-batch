# 報告用資料 — Excel→YAML 変換で RESPONSE_HEADER の再送要求フラグ `0` が消える件

- 対象プロジェクト: nablarch-example-mom-sync-send-batch（ブランチ `ntf-yaml-support`）
- 対象テスト: `SendProjectInsertMessageRequestTest` の `testAbnormalEnd`
- 事象区分: NTF テストデータ Excel→YAML 変換時のサンプリング差異（事象ID ①）
- 報告日: 2026-10-07
- 付随資料: [`task-3-response-header-diff-evidence.md`](task-3-response-header-diff-evidence.md)（Excel/YAML 生データ）、[`task-3-response-header-diff-root-cause.md`](task-3-response-header-diff-root-cause.md)（詳細な調査記録）

---

## 0. 結論（先に要点）

- Excel を YAML に変換したところ、`RESPONSE_HEADER` の **case3 / case4** で「再送要求フラグ = `0`」が YAML では空文字 `""` になった。
- これは **コンバータや `nablarch-testing-yaml` の不具合ではなく、NTF（テストフレームワーク）が元々持つ仕様どおりの挙動**。
- 消えた `0` は **Excel 実行時点でも読み込まれず使われていなかったデータ**であり、テストの挙動には影響しない（**Excel と YAML は挙動等価**）。
- よって **コード修正は不要**。差異は「表現（バイト値）の差」であって「意味の差」ではない、と整理する。

---

## 1. 事象の内容（What）

### 1-1. 発見経緯
Task #3「Excel テストデータの YAML 変換」の変換結果サンプリング比較で、`testAbnormalEnd` の
`RESPONSE_HEADER`（受信側モックが返す応答電文ヘッダ）の一部で、Excel にあった値が YAML から欠落していることを発見した。

> 補足: 本事象①は変換結果の**値の差**であり、エラーログ（スタックトレース）は出力されない。
> 同じ Task 群で発生した BUILD FAILURE（事象②：`couldn't find resource`）は**別事象**で、
> 別資料 [`task-5-sendsync-resource-root-cause.md`](task-5-sendsync-resource-root-cause.md) に整理済み（原因・対応とも②で完結）。

### 1-2. Excel と YAML の比較（核心）
A列=no、B列=リクエストID、C列=再送要求フラグ。詳細な生データは付随エビデンス参照。

| セクション | ケース | Excel の値（B列 / C列） | 変換後 YAML | 判定 |
|---|---|---|---|---|
| RESPONSE_HEADER | case1 | `ProjectInsertMessage` /（C無し） | `["ProjectInsertMessage","",""]` | 一致 |
| RESPONSE_HEADER | case2 | `ProjectInsertMessage` /（C無し） | `["ProjectInsertMessage","",""]` | 一致 |
| **RESPONSE_HEADER** | **case3** | **`errorMode:timeout` / `0`** | **`["errorMode:timeout","",""]`** | **差異（`0`→`""`）** |
| **RESPONSE_HEADER** | **case4** | **`errorMode:msgException` / `0`** | **`["errorMode:msgException","",""]`** | **差異（`0`→`""`）** |
| EXPECTED_REQUEST_HEADER | case1〜4 | `ProjectInsertMessage` / `0` | `["ProjectInsertMessage","0",""]` | 一致（`0`保持） |

**差異の特徴**: B列（リクエストID列）が `errorMode:...` の行に限って、C列 `0` が落ちる。
同じ `0` でも B列が通常の `ProjectInsertMessage` である EXPECTED_REQUEST_HEADER では正しく保持される。
→ 「errorMode 行だけ」で起きる現象、という点が原因特定の決め手。

---

## 2. 原因

### 2-1. 直接原因（Where / How）— なぜ `0` が消えるか

NTF の sendSync 電文読み取りクラスが、**`errorMode:...` 行についてはマーカー（B列）1個だけを残し、残りの列（C列の `0` 等）を読み捨てる**ため。

- **リポジトリ**: `nablarch-testing`（GitHub: `nablarch/nablarch-testing`）
- **クラス**: `nablarch.test.core.reader.SendSyncMessageParser`
- **ファイル**: `src/main/java/nablarch/test/core/reader/SendSyncMessageParser.java`
- **該当行**: `onReadingValues()` の **123〜134行**（errorMode 分岐は **125〜131行**）
  - 関連定義: `enum ErrorMode`=**49行**、`isErrorMode()`=**83行**、`ERROR_MODE_COLUMN_NUMBER=1`=**96行**、`NO_COLUMN_NUMBER=0`=**99行**

```java
// SendSyncMessageParser.java 116〜135行（抜粋）
protected void onReadingValues(List<String> line) {
    if (StringUtil.isNullOrEmpty(line)) { return; }          // 空行
    ArrayList<String> temp = new ArrayList<String>(line);
    if (temp.size() > ERROR_MODE_COLUMN_NUMBER) {            // ERROR_MODE_COLUMN_NUMBER = 1
        String errorMode = temp.get(ERROR_MODE_COLUMN_NUMBER);
        if (ErrorMode.isErrorMode(errorMode)) {              // B列が errorMode:timeout / :msgException か
            ArrayList<String> list = new ArrayList<String>();
            list.add(temp.get(ERROR_MODE_COLUMN_NUMBER));    // ★ errorMode マーカー1個だけ残す
            currentFragment.addValue(list);                  //   → C列(再送要求フラグ=0)等は捨てられる
            return;
        }
    }
    // 通常行: no列(0番)だけ id として除去し、残り全列を保持（0 も保持される）
    currentFragment.addValueWithId(temp, temp.remove(NO_COLUMN_NUMBER));
}
```

YAML で `["errorMode:timeout", "", ""]` と3要素になるのは、保持値が `[errorMode:timeout]` の1個で、
YAML ライタがレコード定義のフィールド数（リクエストID/再送要求フラグ/予備領域=3）に合わせ残りを空文字で埋めるため。

### 2-2. 根本原因（Why）— なぜそれが正しい挙動なのか

**NTF の errorMode レコード仕様に忠実な結果であり、かつ Excel 時代から同じだったため。**

1. **errorMode は制御マーカー**: `errorMode:timeout` / `errorMode:msgException` は、受信側モックに
   「正常な応答電文の代わりにタイムアウト／メッセージング例外を発生させよ」と指示する**制御マーカー**。
   このレコードは実電文の内容を持たず、マーカー以外のフィールド（再送要求フラグ等）は **NTF が元々使用しない**。
   （`SendSyncMessageParser.java` のメソッド Javadoc 107行「…最初のフィールド以外のフィールドはパースしない」に明記）

2. **Excel/YAML で読み取りロジックが同一**: コンバータは sendSync 電文の読み取りを自前実装せず、
   実行時と**同じ `SendSyncMessageParser` に委譲**している。
   - リポジトリ `nablarch-testing-converter`（GitHub: `nablarch/nablarch-testing-converter`）
   - クラス `nablarch.test.core.reader.TestCoreReaderAdapter`
     （`src/main/java/nablarch/test/core/reader/TestCoreReaderAdapter.java`、クラス **46行**、`readSendSyncMessages()` **235行**）
   - 内部クラス `SendSyncBodyCollector`（**413行**）が、フィールド
     `private final SendSyncMessageParser delegate;`（**416行**、生成 **428行**）へ各行を委譲（`onReadLine` **433行**）。

   読み込み元が Excel か YAML かは引数の `TestDataReader` が異なるだけで、
   errorMode 行の「マーカー以外を捨てる」挙動は**両経路で完全に同一**。
   → したがって **Excel 実行時点でもこの `0` は読み込まれず未使用**だった。

3. **差異は意味差ではなく表現差**: 以上より、YAML で `0` が消えても電文の組み立て・比較に使う情報は一切変わらない。
   「差異」はバイト値レベルの表現差であって、テスト挙動に影響する意味差ではない。

> 補足（記録手順上の反省点）: 本件を当初 self-check が「一致」と判定していたのは、リクエストID列しか確認しておらず
> 列単位の網羅確認をしていなかったため。以後の変換検証では列ごとの差分列挙を手順に含める。

---

## 3. 対応方針とその理由

| # | 対応方針 | 理由 |
|---|---|---|
| 1 | **コード修正なし（差異を許容）** | 直接原因は NTF 標準パーサの仕様挙動、根本原因は errorMode 行の `0` が元々未使用のデッドデータであること。Excel と YAML は挙動等価であり、修正すべき欠陥が存在しない。 |
| 2 | **Task #3 の判定を「意味的に一致（errorMode列は仕様どおり）」に整理** | 「バイト値差」と「意味差」を区別して記録し、以後の誤解を防ぐため。 |
| 3 | **変換検証手順に「列単位の差分列挙」を追加** | 当初 self-check がID列のみ比較していた記録手順上の不備を是正し、同種の見落としを防ぐため。 |

### 補足: 挙動等価の実テスト確認について
本差異①の値比較が実テストで実際に走るのは、同時期に発生していた BUILD FAILURE（事象②）の解消後。
②はその後 `messaging-sync-send-boot.xml` への YAML 対応集約（コミット `b31c3ba`）で解決し、
`mvn clean test` が **BUILD SUCCESS（Tests run: 2, Failures: 0）** となったことで、①が挙動へ影響しないことも併せて確認済み。
（②の詳細は [`task-5-sendsync-resource-root-cause.md`](task-5-sendsync-resource-root-cause.md)）

---

## 付録: 参照情報

- 実ソース（直接原因）: `nablarch-testing` リポジトリ
  `src/main/java/nablarch/test/core/reader/SendSyncMessageParser.java`（49 / 83 / 96 / 99 / 116〜135行）
- 委譲元（変換経路）: `nablarch-testing-converter` リポジトリ
  `src/main/java/nablarch/test/core/reader/TestCoreReaderAdapter.java`（クラス 46行、`readSendSyncMessages()` 235行、内部クラス `SendSyncBodyCollector` 413行、`delegate` フィールド 416/428行）
- 変換後 YAML: `src/test/java/com/nablarch/example/SendProjectInsertMessageRequestTest/testAbnormalEnd.yaml`
- Excel 原本（削除前）: `git show ea87965:src/test/java/com/nablarch/example/SendProjectInsertMessageRequestTest.xlsx`
- ダンプ補助スクリプト: `.rn/ntf-yaml-support/scripts/xlsx_dump.py`
- 生データ対比: [`task-3-response-header-diff-evidence.md`](task-3-response-header-diff-evidence.md)
- **比較用の実ファイル**: [`task-3-compare/`](task-3-compare/)（Excel 原本 `SendProjectInsertMessageRequestTest.xlsx` と変換後 YAML 3ファイルを同梱）
