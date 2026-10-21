# task-3 Completion Check

> ℹ **経緯**: サンプリング比較で `RESPONSE_HEADER_MESSAGES[case3]/[case4]` の差異を検出し、運用ルールに従い修正せず報告・停止した。ユーザー判断により**差異を許容して続行**（errorMode レコードは先頭列のみ使用され挙動等価とする前回分析と同じ扱い）。YAML は変換結果を修正せずそのままコミットした。

## Completion Criteria

| Criterion | Self-check | Evidence | QA | QA Evidence |
|---|---|---|---|---|
| 変換対象の全てのExcelファイルに対応するYAMLファイルが生成されている | OK | 対象 Excel 1件（`SendProjectInsertMessageRequestTest.xlsx`、シート setUpDb / testNormalEnd / testAbnormalEnd）。`mvn -B com.nablarch.framework:nablarch-testing-converter:1.0.0-SNAPSHOT:convert` で「Converted 1 file(s).」BUILD SUCCESS。`SendProjectInsertMessageRequestTest/` 配下に `setUpDb.yaml`（0 byte。元シートが空）/ `testNormalEnd.yaml` / `testAbnormalEnd.yaml` を生成 | OK | 対象 Excel 1件（`.xls` 0件で併存なし）。3シートすべてに対応する YAML あり（setUpDb は空シートで 0 byte） |
| サンプリング比較でExcelとYAMLの内容が一致している | OK（ユーザー許容） | `testAbnormalEnd` の `RESPONSE_HEADER_MESSAGES[case3]` / `[case4]` で再送要求フラグが Excel `'0'` に対し YAML `""`（下記 Sampling Comparison）。ユーザー判断で許容。その他のサンプルは一致 | OK | 全ブロックを機械比較（testNormalEnd 7ブロック・56セル、testAbnormalEnd 29ブロック・232セル）。不一致は許容済みの case3/case4 再送要求フラグ2セルのみ |
| 変換後のYAMLファイルがコミットされている | OK | ユーザー許容後、YAML 3ファイルを無修正でコミット（task-3 YAML コミット） | OK | YAML 3ファイルは `2bbebb3` で追加。xlsx は git 管理下・作業ツリーとも残存 |

## Sampling Comparison（task-3 のみ）

比較方法: `.rn/ntf-yaml-support/scripts/xlsx_dump.py` で Excel のセル値（行・列・値・型）をダンプし、変換後 YAML の該当箇所と突き合わせた。

| YAML ファイル | 比較件数 | レコードキー/行 | Excel 値 | YAML 値 | 一致 |
| --- | --- | --- | --- | --- | --- |
| `setUpDb.yaml` | 0（元データ0件） | シート `setUpDb` | 空シート（非空セルなし） | 空ファイル（0 byte） | OK |
| `testNormalEnd.yaml` | 5 | `testShots` no=1（row 4） | description=メッセージ送信成功, expectedStatusCode=0, diConfig=messaging-sync-send-boot.xml, expectedMessage/responseMessage=case1 | 同値 | OK |
| `testNormalEnd.yaml` | | `SETUP_TABLE[1]=PROJECT_INS_REQ`（row 12） | PROJECT_NAME=プロジェクト００１, CLIENT_ID=1（数値セル）, USER_ID=100（数値セル）, SALES=10000（数値セル）, STATUS=0 | PROJECT_NAME="プロジェクト００１", CLIENT_ID="1.0", USER_ID="100.0", SALES="10000.0", STATUS="0" | OK（数値セルは NTF `PoiXlsReader` が `cell.toString()` で読むため Excel 読込時も `1.0` 等となり等価） |
| `testNormalEnd.yaml` | | `EXPECTED_TABLE[1]=PROJECT_INS_REQ`（row 21） | PROJECT_INS_REQ_ID=1, STATUS=1, UPDATE_DATE=${systemTime} | 同値 | OK |
| `testNormalEnd.yaml` | | `RESPONSE_HEADER_MESSAGES[case1]`（row 26–31） | MS932 / CRLF、フィールド3（20/1/9）、データ: リクエストID=ProjectInsertMessage、他空 | directives・fields 同値、rows `["ProjectInsertMessage", "", ""]` | OK |
| `testNormalEnd.yaml` | | `EXPECTED_REQUEST_HEADER_MESSAGES[case1]`（row 50） | ProjectInsertMessage, 0, 空 | `["ProjectInsertMessage", "0", ""]` | OK |
| `testAbnormalEnd.yaml` | 6 | `testShots` no=3（row 6） | description=メッセージ送信中にタイムアウトエラーが発生した場合, expectedStatusCode=100, expectedLog=expectedLog3 | 同値 | OK |
| `testAbnormalEnd.yaml` | | `LIST_MAP=expectedLog3`（row 133） | FATAL / メッセージの送信に失敗しました。 | 同値 | OK |
| `testAbnormalEnd.yaml` | | `EXPECTED_REQUEST_HEADER_MESSAGES[case3]`（row 167） | ProjectInsertMessage, 0, 空 | `["ProjectInsertMessage", "0", ""]` | OK |
| `testAbnormalEnd.yaml` | | `RESPONSE_BODY_MESSAGES[case3]`（row 156） | リターンコード=errorMode:timeout, 詳細=空 | `["errorMode:timeout", ""]` | OK |
| `testAbnormalEnd.yaml` | | `RESPONSE_HEADER_MESSAGES[case3]`（row 148） | リクエストID=errorMode:timeout, **再送要求フラグ=0**, 予備領域=空 | `["errorMode:timeout", "", ""]`（**再送要求フラグ=""**） | **NG** |
| `testAbnormalEnd.yaml` | | `RESPONSE_HEADER_MESSAGES[case4]`（row 203） | リクエストID=errorMode:msgException, **再送要求フラグ=0**, 予備領域=空 | `["errorMode:msgException", "", ""]`（**再送要求フラグ=""**） | **NG** |

差異の所見（調査のみ・修正なし）:
- 差異は先頭列が `errorMode:...` の `RESPONSE_HEADER_MESSAGES` レコード（case3 / case4）に限られる。同じ `0` を持つ `EXPECTED_REQUEST_HEADER_MESSAGES` は正しく `"0"` に変換されている。
- 今回生成した YAML 3ファイルは、ブランチ履歴上の過去成果物（`12967ec` 時点）とバイト単位で同一（`cmp` 一致）。過去の作業では、NTF `SendSyncMessageParser` が errorMode レコードについてマーカー（先頭列）以外を保持しない仕様と分析し、挙動等価として許容した経緯がある（`git show 12967ec:.rn/ntf-yaml-support/reports/task-3-response-header-diff-root-cause.md`）。指示書の運用ルールに従い報告・停止し、ユーザー判断で許容となった。

## .xls/.xlsx 併存チェック（該当時のみ）
- 併存の有無: なし（`find` で `.xls` 0件、`.xlsx` 1件）
- 差分確認結果の保存先: 該当なし
- 未使用側（通常 `.xlsx`）の削除: 該当なし

## QA Expert Review

| Aspect | Verdict | Evidence / Improvement |
|---|---|---|
| Meaningful tests/verification | OK | 自己チェック11サンプルに加え、全288セルを機械比較し「差異は case3/case4 のみ」の結論が全件で成立することを確認 |
| Edge case coverage | OK | 空シート→0 byte、数値セル→`"1.0"`（Excel 読込時と等価）、空セル→`""`、`${systemTime}` 保持、`no:` キーは snakeyaml-engine（YAML 1.2）で文字列として読込。YAML 全メッセージに付く `file-type: "Fixed"` は Excel 読込時の `DataFile` 既定値と等価 |

## Expert Reviews (code changes only)

N/A — no code changes in this task.

## Overall Verdict

- Self-check: OK（`RESPONSE_HEADER_MESSAGES[case3]/[case4]` 再送要求フラグ差異はユーザー判断で許容）
- QA: OK
- Language expert: N/A
- Software-engineering expert: N/A
- Ready for user review: Yes
