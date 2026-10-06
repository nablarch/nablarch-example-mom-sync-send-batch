# 根本原因レポート — Task #3 変換差異（RESPONSE_HEADER の再送要求フラグ `0` 欠落）

- 対象: nablarch-example-mom-sync-send-batch / ブランチ `ntf-yaml-support`
- 事象ID: ①（Task #3 サンプリング差異）
- 調査日: 2026-10-06
- 結論: **コンバータのバグではなく、NTF が定める errorMode レコードの仕様どおりの挙動。欠落した `0` は元の Excel 実行時にも使われていなかった意味を持たないデータであり、YAML は挙動等価。**

---

## 1. 事象（What）

`testAbnormalEnd` の `RESPONSE_HEADER_MESSAGES` で、`case3` / `case4` のデータ行にある **再送要求フラグ（C列）= `0`** が、変換後 YAML では `""`（空）になっていた。

| ケース | Excel データ行 | Excel の値 | YAML の値 |
|---|---|---|---|
| case1 | row37 | `no=1, リクエストID=ProjectInsertMessage`（C列なし） | `["ProjectInsertMessage", "", ""]` ← 一致（元からC無し） |
| case2 | row? | 同上（C列なし） | `["ProjectInsertMessage", "", ""]` ← 一致 |
| **case3** | row148 | `no=1, リクエストID=errorMode:timeout, 再送要求フラグ=0` | `["errorMode:timeout", "", ""]` ← **`0`→`""`** |
| **case4** | row203 | `no=1, リクエストID=errorMode:msgException, 再送要求フラグ=0` | `["errorMode:msgException", "", ""]` ← **`0`→`""`** |

対照として、同じ `0` でも `EXPECTED_REQUEST_HEADER_MESSAGES`（case1〜4、row56/112/167/222、リクエストID列=`ProjectInsertMessage`）は YAML で正しく `["ProjectInsertMessage", "0", ""]` に変換されている。

→ 差異は「**リクエストID列（＝レコードの2列目）が `errorMode:...` であるレコード**」に限って発生し、その他列が落ちている。

## 2. 直接原因（Where / How）

NTF の sendSync メッセージ読み取りクラス
`nablarch.test.core.reader.SendSyncMessageParser`（jar: `nablarch-testing`）の行処理コールバック
`SendSyncMessageParser$1.onReadingValues(List<String> values)` に、errorMode レコード専用の分岐がある。バイトコード（`javap -c`）から再構成した擬似コード:

```java
// 定数: ERROR_MODE_COLUMN_NUMBER = 1 , NO_COLUMN_NUMBER = 0
protected void onReadingValues(List<String> values) {
    if (StringUtil.isNullOrEmpty(values)) { ... return; }

    if (ErrorMode.isErrorMode(values.get(1))) {   // 2列目(=リクエストID列)が errorMode:timeout / errorMode:msgException か
        List<String> only = new ArrayList<>();
        only.add(values.get(1));                   // ★ errorMode マーカー1個だけを残す
        addValue(only);                            //    → 残りの列（再送要求フラグ=0 等）は捨てられる
    } else {
        String id = values.remove(0);              // 通常レコード: no列をidとして取り出し
        addValueWithId(values, id);                //    → 残り全列を保持（0 も保持される）
    }
}
```

- `ErrorMode.isErrorMode(s)` は `s` が厳密に `"errorMode:timeout"` または `"errorMode:msgException"` と一致するかを判定（enum `SendSyncMessageParser$ErrorMode`）。
- errorMode 分岐では **`values.get(1)` の1要素のみ** を `addValue` しており、以降の列（index 2 = 再送要求フラグ `0`）は読み捨てられる。
- 一方 通常分岐は no 列のみ除去して残り全列を保持するため、`EXPECTED_REQUEST_HEADER` の `0` は保持される。

YAML 側で `["errorMode:timeout", "", ""]` と3要素になっているのは、保持値が `[errorMode:timeout]` の1個で、YAML ライタがレコード定義のフィールド数（リクエストID/再送要求フラグ/予備領域=3）に合わせて残りを空文字で埋めるため。

## 3. なぜコンバータで起きるか（変換経路）

コンバータ `nablarch-testing-converter` は、sendSync メッセージの XLS 読み取りを自前実装せず、NTF の同一パーサに委譲している。

- `nablarch.test.core.reader.TestCoreReaderAdapter#readSendSyncMessages(...)`
  → 内部クラス `TestCoreReaderAdapter$SendSyncBodyCollector`
  → フィールド `private final SendSyncMessageParser delegate;`（＝上記 §2 のパーサ）

したがって **XLS→YAML 変換時の読み取りと、テスト実行時の読み取りは完全に同一ロジック**。errorMode レコードの「マーカーのみ残して他列破棄」は両経路で等しく起こる。

## 4. 欠落した `0` に意味はあるか（影響評価）

**意味はない（挙動に影響しない）。**

- `errorMode:timeout` / `errorMode:msgException` は、受信側モックに「正常な応答電文を返す代わりにタイムアウト／メッセージング例外を発生させよ」と指示する**制御マーカー**。該当レコードは実際の電文内容を持たない。
- このため NTF は errorMode レコードについてマーカー以外のフィールド（再送要求フラグ等）を**元々使用しない**。パーサが読み捨てるのはそのため。
- すなわち、Task #1 で Excel のまま全テストがパスしていた時点でも、この `0` は読み込まれず未使用だった。YAML 変換で `0` が消えても、電文の組み立て・比較に用いられる情報は変化しない。

→ `0`→`""` は **データ欠落ではなく、NTF の errorMode セマンティクスに忠実な表現**。変換の正しさの観点では Excel と YAML は**挙動等価**。

## 5. Task #5 のテスト失敗（②）との関係

本差異①は Task #5 の BUILD FAILURE の原因**ではない**。②は `RequestTestingSendSyncSupport`→`TestSupport.getPathOf`→`YamlLoader.isResourceExisting` がリソースをディレクトリ前提で解決する一方、コンバータがファイル（`testAbnormalEnd.yaml`）を出力しているための「リソース未検出」であり、電文値の比較に到達する前段で失敗している。①の値比較が実際に走るのは②が解消された後。したがって①の挙動等価性の**実テストによる確認は②の解消後**となる。

## 6. 結論と推奨

- ①は **コンバータ／`nablarch-testing-yaml` のバグではない**。NTF（`SendSyncMessageParser`）が定める errorMode レコードの仕様をコンバータが忠実に踏襲した結果であり、欠落した `0` は無意味なデッドデータ。
- サンプリング比較の「差異」は**バイト値レベルの差**であって**意味レベルの差ではない**。Task #3 の当初 self-check が「一致」としたのは（意味的には正しいが）リクエストID列しか確認しておらず、差異の有無を列挙的に検証していなかった点が記録手順上の不備だった。
- 推奨対応（ユーザー判断用、いずれも本レポートでは未実施）:
  1. ①は許容（挙動等価）と判断し、Task #3 の判定を「意味的に一致／errorMode列は仕様どおり」に整理する。ただし移行完遂の可否は②（テスト失敗）次第。
  2. 本件の本質的ブロッカーは②。②（sendSync 応答電文の YAML リソース解決がディレクトリ前提）をツール側（`nablarch-testing-converter` / `nablarch-testing-yaml`）の課題として扱うか、本 Example を移行対象から外すかを別途判断する。

## 付録: 調査に用いたエビデンス

- Excel 復元: `git show ea87965:src/test/java/com/nablarch/example/SendProjectInsertMessageRequestTest.xlsx`
- セル値ダンプ: `.rn/ntf-yaml-support/scripts/xlsx_dump.py`（ルビ除去・型付き）
- バイトコード確認（`javap -p -c -constants`）:
  - `nablarch.test.core.reader.SendSyncMessageParser`（`ERROR_MODE_COLUMN_NUMBER=1`, `NO_COLUMN_NUMBER=0`, `ERROR_MODE_TIMEOUT/MSG_EXCEPTION`）
  - `nablarch.test.core.reader.SendSyncMessageParser$ErrorMode`（enum TIMEOUT / MSG_EXCEPTION, `isErrorMode`）
  - `nablarch.test.core.reader.SendSyncMessageParser$1#onReadingValues`（errorMode 分岐で `addValue([get(1)])`）
  - `nablarch.test.core.reader.TestCoreReaderAdapter$SendSyncBodyCollector`（`SendSyncMessageParser delegate` へ委譲）
- jar: `~/.m2/.../nablarch-testing-6-NEXT-SNAPSHOT.jar`, `nablarch-testing-converter-1.0.0-SNAPSHOT.jar`
