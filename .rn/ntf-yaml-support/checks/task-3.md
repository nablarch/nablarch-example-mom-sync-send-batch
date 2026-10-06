# task-3 Completion Check

## Completion Criteria

| Criterion | Self-check | Evidence | QA | QA Evidence |
|---|---|---|---|---|
| 変換対象の全ExcelにYAMLが生成されている | OK | `SendProjectInsertMessageRequestTest.xlsx`（3シート: setUpDb/testNormalEnd/testAbnormalEnd）→ `SendProjectInsertMessageRequestTest/setUpDb.yaml`・`testNormalEnd.yaml`・`testAbnormalEnd.yaml`（3ファイル）。`mvn ...:convert` が `Converted 1 file(s). BUILD SUCCESS` | OK | 3シート↔3YAML 1:1、setUpDb空も整合 |
| サンプリング比較でExcelとYAMLが一致 | OK | 大半は一致。唯一 RESPONSE_HEADER_MESSAGES case3/case4 で errorMode レコードの再送要求フラグ `0` が YAML で `""` となるが、これは NTF 仕様どおりの挙動（下記「差異の扱いと対応経緯」参照）で挙動等価。②解消後の再テストで当該ケース含め全パス | OK | 削除済Excelを git から取り出しセル単位で再検証。差は errorMode レコードの未使用列のみで、根本原因（SendSyncMessageParser 仕様）と実テスト全パスの両面から挙動等価を確認 |
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

### RESPONSE_HEADER case3/case4 の差異と対応経緯

#### 事象
再検証（削除済み Excel を `git show ea87965:...xlsx` で取り出して実施）で、以下の Excel↔YAML 差を検出。

- `RESPONSE_HEADER_MESSAGES[case3]` データ行(Excel row148): Excel = リクエストID=`errorMode:timeout`, **再送要求フラグ=`0`**, 予備領域=空 → YAML `["errorMode:timeout", "", ""]`（再送要求フラグ `0` が `""`）
- `RESPONSE_HEADER_MESSAGES[case4]` データ行(Excel row203): Excel = リクエストID=`errorMode:msgException`, **再送要求フラグ=`0`**, 予備領域=空 → YAML `["errorMode:msgException", "", ""]`（同上）
- 対照: `EXPECTED_REQUEST_HEADER_MESSAGES` の同じ C列=`0`（row56/112/167/222）は YAML でも `"0"`。差はリクエストID列が `errorMode:...` のケースに限定。

#### 前回の対応内容
- 当初の self-check はリクエストID列のみを確認し「一致」と記録していたが、再検証で上記の `0`→`""` 差を確定。
- この時点では原因・影響が未確定だったため、本差を **「差異（NG）」** として記録した。

#### 今回の対応内容
- 根本原因を調査（別紙 `reports/task-3-response-header-diff-root-cause.md`）。結論＝NTF の `SendSyncMessageParser` は errorMode レコード（`errorMode:timeout`/`errorMode:msgException`）を検出すると**マーカー列のみを残し他列を破棄する仕様**であり、コンバータも実行時と同一パーサに委譲するため同じ結果になる。欠落した再送要求フラグ `0` は errorMode レコードでは**元々使用されないデッドデータ**。
- ②解消後（修正コミット b31c3ba）の `mvn clean test` で、case3/case4（errorMode:timeout / msgException）を含む testAbnormalEnd が全パス（BUILD SUCCESS）。
- 以上より本差を **「NTF 仕様どおりの挙動であり挙動等価＝許容（defect ではない）」** と判定（OK）。

#### 判定を変更した理由
- `0`→`""` は**バイト値レベルの差**であって**意味（挙動）レベルの差ではない**ことが、(1) 根本原因（errorMode レコードは当該列を使わない NTF 仕様）と (2) 修正後の実テスト全パス、の両面から裏付けられたため。
- したがって当初の「差異(NG)」から「仕様どおりで許容(OK)」へ評価を更新した。再変換や YAML の手修正は不要（変換は pom の converter 設定と converter 実装のみに依存し、DI 設定変更の影響を受けないため内容は不変）。

## QA Expert Review

| Aspect | Verdict | Evidence / Improvement |
|---|---|---|
| Meaningful tests/verification | OK | 当初 self-check がリクエストID列のみ確認し case3/case4 の `0`→`""` を見落としていたが、再検証で差を確定し、根本原因調査＋修正後の再テスト全パスで挙動等価（NTF仕様どおり）と確認。突合手法・結論とも妥当 |
| Edge case coverage | OK | 空シート・数値/文字列区別・ルビ・4異常ケース・各expectedLog を網羅。errorMode レコードの列破棄も仕様として確認済み。残差なし |

## Expert Reviews (code changes only)

N/A — no code changes in this task.

## Overall Verdict

- Self-check: OK（唯一の差 case3/case4 の再送要求フラグ `0`→`""` は NTF 仕様どおりで挙動等価＝許容。経緯は上記「差異と対応経緯」）
- QA: OK
- Ready for user review: Yes
