# task-3 Completion Check

## Completion Criteria

| Criterion | Self-check | Evidence | QA | QA Evidence |
|---|---|---|---|---|
| 変換対象の全ExcelにYAMLが生成されている | OK | `SendProjectInsertMessageRequestTest.xlsx`（3シート: setUpDb/testNormalEnd/testAbnormalEnd）→ `SendProjectInsertMessageRequestTest/setUpDb.yaml`・`testNormalEnd.yaml`・`testAbnormalEnd.yaml`（3ファイル）。`mvn ...:convert` が `Converted 1 file(s). BUILD SUCCESS` | OK | 3シート↔3YAML 1:1、setUpDb空も整合 |
| サンプリング比較でExcelとYAMLが一致 | **NG（差異あり）** | RESPONSE_HEADER_MESSAGES case3/case4 の再送要求フラグ `0` が YAML で `""` に欠落（下記「差異あり」参照）。他は一致 | **NG** | 削除済Excelを git から取り出し再検証、case3/case4 の C列`0`→`""` 欠落を確認 |
| 変換後YAMLがコミットされている | OK | YAMLのみステージしコミット（Excelは未削除） | OK | ea87965 は YAML+script+doc のみ |

### サンプリング比較詳細

比較方法: `scripts/xlsx_dump.py` で xlsx の各シートをセル値（型 s=文字列 / n=数値、ルビ=phonetic は除去）としてダンプし、生成YAMLと行単位で突合。

**setUpDb シート → setUpDb.yaml**

- シートに非空セルなし（完全に空）→ `setUpDb.yaml` も空。忠実に一致 ✓

**testNormalEnd シート → testNormalEnd.yaml**

- testShots(row4): no=`1`, description=`メッセージ送信成功`, expectedStatusCode=`0`, setUpTable=`1`, expectedTable=`1`, diConfig=`messaging-sync-send-boot.xml`, requestPath=`SendProjectInsertMessageAction`, userId=`batch_user`, expectedMessage/responseMessage=`case1` → YAML一致 ✓
- SETUP_TABLE PROJECT_INS_REQ(row12): PROJECT_NAME=`プロジェクト００１`, PROJECT_TYPE=`development`, CLIENT_ID=`1`[数値]→YAML `"1.0"`, USER_ID=`100`[数値]→`"100.0"`, SALES=`10000`[数値]→`"10000.0"`, STATUS=`0`[文字列]→`"0"` → YAML一致（数値セルは `.0` 付きで忠実）✓
- EXPECTED_TABLE(row21): PROJECT_INS_REQ_ID=`1`, STATUS=`1`, UPDATE_DATE=`${systemTime}` → YAML一致 ✓
- RESPONSE_BODY(row39): リターンコード=`success` → YAML `["success", ""]` 一致 ✓
- EXPECTED_REQUEST_HEADER(row50): リクエストID=`ProjectInsertMessage`, 再送要求フラグ=`0` → YAML `["ProjectInsertMessage", "0", ""]` 一致 ✓
- EXPECTED_REQUEST_BODY(row58): 同じ論理値でもセルが文字列のため CLIENT_ID=`1`→`"1"`, USER_ID=`100`→`"100"`, SALES=`10000`→`"10000"`（`.0` なし）→ YAML一致（文字列セルを忠実に区別）✓

**testAbnormalEnd シート → testAbnormalEnd.yaml**

- testShots 4件(row4-7): no=`1..4`, description=`受信側でバリデーションに失敗した場合`/`受信側で電文フォーマットの解析に失敗した場合`/`メッセージ送信中にタイムアウトエラーが発生した場合`/`メッセージ送受信エラーが発生する場合のテスト`, expectedStatusCode=`110/110/100/100`, expectedLog=`expectedLog1..4`, case=`case1..4` → YAML一致 ✓
- expectedLog1(row22): FATAL / `メッセージ受信側でValidationエラーが発生しました。` → YAML一致 ✓
- expectedLog2(row78): FATAL / `メッセージ受信側でエラーが発生しました。詳細：invalid layout.` → YAML一致 ✓
- expectedLog3,4(row133,188): FATAL / `メッセージの送信に失敗しました。` → YAML一致 ✓
- RESPONSE_BODY case2(row101): `fatal`, `invalid layout.` → YAML `["fatal", "invalid layout."]` 一致 ✓
- SETUP_TABLE/EXPECTED_TABLE 各group(1-4): PROJECT_INS_REQ の全値が testNormalEnd と同一構造で一致（EXPECTED STATUS=`2`）✓

数値→`"n.0"` および `"0"`→`"0"` 等はコンバータ/NTF(POI) が数値セルを double として読む忠実な表現であり defect ではない（web/batch と同じ扱い）。

### 【差異あり — 要ユーザー判断・修正せず停止】

QAレビュー（および再検証）により、以下の **Excel↔YAML データ差異** を検出した。再検証は削除済み Excel を `git show ea87965:...xlsx` で取り出して実施。

- `RESPONSE_HEADER_MESSAGES[case3]` データ行(Excel row148): Excel = リクエストID=`errorMode:timeout`, **再送要求フラグ=`0`**, 予備領域=空。
  - YAML(testAbnormalEnd.yaml) = `["errorMode:timeout", "", ""]` ← 2番目が `0` ではなく `""`（**再送要求フラグ `0` が欠落**）
- `RESPONSE_HEADER_MESSAGES[case4]` データ行(Excel row203): Excel = リクエストID=`errorMode:msgException`, **再送要求フラグ=`0`**, 予備領域=空。
  - YAML = `["errorMode:msgException", "", ""]` ← 同様に **再送要求フラグ `0` が欠落**

対照: `EXPECTED_REQUEST_HEADER_MESSAGES` の同じ C列=`0`（row56/112/167/222）は YAML で正しく `"0"` に変換されている。差異は RESPONSE_HEADER の case3/case4（リクエストID列が `errorMode:...` 指示値のケース）に限定して発生。

※ 当初の self-check はリクエストID列のみを見て「一致」と誤記録していた（本セクションで訂正）。本差異はコンバータ側の挙動であり、**ルールに従い修正は行わずユーザーに報告して停止する**。

**根本原因調査（別紙）**: `reports/task-3-response-header-diff-root-cause.md` 参照。結論＝NTF の `SendSyncMessageParser` が errorMode レコードはマーカー列のみ残し他列を破棄する**仕様どおりの挙動**（コンバータは実行時と同一パーサに委譲）。欠落した `0` は errorMode レコードでは元々未使用のデッドデータで、**挙動等価**（バイト値差であって意味差ではない）。

## QA Expert Review

| Aspect | Verdict | Evidence / Improvement |
|---|---|---|
| Meaningful tests/verification | NG | 突合手法は妥当だが、当初 self-check が RESPONSE_HEADER case3/case4 の再送要求フラグ `0`→`""` 欠落を見落として「一致」と誤記録。再検証で差異を確定 |
| Edge case coverage | NG | リクエストID列が `errorMode:...` 指示値のケースで再送要求フラグ`0`が欠落する差異が未検出だった。空シート・数値/文字列区別・ルビ・4異常ケース・各expectedLog 等のエッジはカバー良好 |

## Expert Reviews (code changes only)

N/A — no code changes in this task.

## Overall Verdict

- Self-check: NG（当初OKは誤り。RESPONSE_HEADER case3/case4 の再送要求フラグ `0`→`""` 欠落あり）
- QA: NG（差異あり）
- Ready for user review: **停止（差異のため修正せず報告）**
