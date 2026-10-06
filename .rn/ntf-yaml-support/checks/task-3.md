# task-3 Completion Check

## Completion Criteria

| Criterion | Self-check | Evidence | QA | QA Evidence |
|---|---|---|---|---|
| 変換対象の全ExcelにYAMLが生成されている | OK | `SendProjectInsertMessageRequestTest.xlsx`（3シート: setUpDb/testNormalEnd/testAbnormalEnd）→ `SendProjectInsertMessageRequestTest/setUpDb.yaml`・`testNormalEnd.yaml`・`testAbnormalEnd.yaml`（3ファイル）。`mvn ...:convert` が `Converted 1 file(s). BUILD SUCCESS` | | |
| サンプリング比較でExcelとYAMLが一致 | OK | 各YAMLで3件以上をセル単位比較、差異なし（詳細は下記）。数値/文字列セルの区別も忠実、ルビ(phonetic)はセル値でないため非対象 | | |
| 変換後YAMLがコミットされている | OK | YAMLのみステージしコミット（Excelは未削除） | | |

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
- RESPONSE_HEADER case3(row148): `errorMode:timeout` → YAML `["errorMode:timeout", "", ""]` 一致 ✓
- SETUP_TABLE/EXPECTED_TABLE 各group(1-4): PROJECT_INS_REQ の全値が testNormalEnd と同一構造で一致（EXPECTED STATUS=`2`）✓

差異なし。数値→`"n.0"` および `"0"`→`"0"` 等はコンバータ/NTF(POI) が数値セルを double として読む忠実な表現であり defect ではない（web/batch と同じ扱い）。

## QA Expert Review

| Aspect | Verdict | Evidence / Improvement |
|---|---|---|
| Meaningful tests/verification | | |
| Edge case coverage | | |

## Expert Reviews (code changes only)

N/A — no code changes in this task.

## Overall Verdict

- Self-check: OK
- QA: (pending)
- Ready for user review: (pending)
