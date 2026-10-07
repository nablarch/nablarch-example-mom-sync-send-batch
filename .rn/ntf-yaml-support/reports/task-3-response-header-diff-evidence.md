# 付随エビデンス — Task #3 RESPONSE_HEADER 変換差異

本ファイルは報告用資料 [`task-3-response-header-diff-report.md`](task-3-response-header-diff-report.md) の裏付けデータ（Excel 原本と変換後 YAML の生データ）をまとめたもの。

- 対象: nablarch-example-mom-sync-send-batch / ブランチ `ntf-yaml-support`
- 対象テスト: `src/test/java/com/nablarch/example/SendProjectInsertMessageRequestTest`（シート/ファイル `testAbnormalEnd`）
- 採取日: 2026-10-06（Excel は削除前のコミット `ea87965` から復元して採取）
- 比較用の実ファイル同梱先: [`task-3-compare/`](task-3-compare/)
  - `SendProjectInsertMessageRequestTest.xlsx`（Excel 原本・削除前）
  - `testAbnormalEnd.yaml` / `testNormalEnd.yaml` / `setUpDb.yaml`（変換後 YAML。`setUpDb.yaml` は元から空）

---

## 1. Excel 原本（削除前）のセル値

`git show ea87965:src/test/java/com/nablarch/example/SendProjectInsertMessageRequestTest.xlsx` で復元し、
`.rn/ntf-yaml-support/scripts/xlsx_dump.py` でシート `testAbnormalEnd` をダンプ（抜粋）。
A列=no、B列=リクエストID、C列=再送要求フラグ。

```
RESPONSE_HEADER_MESSAGES[case1]  row37 : A='1' | B='ProjectInsertMessage'                 （C列なし）
RESPONSE_HEADER_MESSAGES[case2]  row93 : A='1' | B='ProjectInsertMessage'                 （C列なし）
RESPONSE_HEADER_MESSAGES[case3]  row148: A='1' | B='errorMode:timeout'       | C='0'       ★
RESPONSE_HEADER_MESSAGES[case4]  row203: A='1' | B='errorMode:msgException'   | C='0'       ★

EXPECTED_REQUEST_HEADER_MESSAGES[case1] row56 : A='1' | B='ProjectInsertMessage' | C='0'
EXPECTED_REQUEST_HEADER_MESSAGES[case2] row112: A='1' | B='ProjectInsertMessage' | C='0'
EXPECTED_REQUEST_HEADER_MESSAGES[case3] row167: A='1' | B='ProjectInsertMessage' | C='0'
EXPECTED_REQUEST_HEADER_MESSAGES[case4] row222: A='1' | B='ProjectInsertMessage' | C='0'
```

★ = 本差異の対象行（B列が `errorMode:...` で、かつ C列に `0` を持つ行）。

## 2. 変換後 YAML（現状ファイル）の値

`src/test/java/com/nablarch/example/SendProjectInsertMessageRequestTest/testAbnormalEnd.yaml`。
各 `rows` は `[リクエストID, 再送要求フラグ, 予備領域]` の3フィールド。

```
response_header_messages:
  case1 (L195): ["ProjectInsertMessage",    "",  ""]
  case2 (L209): ["ProjectInsertMessage",    "",  ""]
  case3 (L223): ["errorMode:timeout",       "",  ""]   ★ Excel の C='0' が "" に
  case4 (L237): ["errorMode:msgException",  "",  ""]   ★ Excel の C='0' が "" に

expected_request_header_messages:
  case1 (L307): ["ProjectInsertMessage",    "0", ""]
  case2 (L321): ["ProjectInsertMessage",    "0", ""]
  case3 (L335): ["ProjectInsertMessage",    "0", ""]   Excel の C='0' が保持される
  case4 (L349): ["ProjectInsertMessage",    "0", ""]
```

## 3. 差異サマリ（Excel → YAML）

| セクション | ケース | Excel (B / C列) | YAML | C='0' の扱い |
|---|---|---|---|---|
| RESPONSE_HEADER | case1 | `ProjectInsertMessage` / なし | `["ProjectInsertMessage","",""]` | 元から無し（差異なし） |
| RESPONSE_HEADER | case2 | `ProjectInsertMessage` / なし | `["ProjectInsertMessage","",""]` | 元から無し（差異なし） |
| **RESPONSE_HEADER** | **case3** | **`errorMode:timeout` / `0`** | **`["errorMode:timeout","",""]`** | **破棄（0→""）★** |
| **RESPONSE_HEADER** | **case4** | **`errorMode:msgException` / `0`** | **`["errorMode:msgException","",""]`** | **破棄（0→""）★** |
| EXPECTED_REQUEST_HEADER | case1〜4 | `ProjectInsertMessage` / `0` | `["ProjectInsertMessage","0",""]` | 保持 |

→ 差異は「**B列（リクエストID列）が `errorMode:...` の行**」に限って発生し、C列 `0` が落ちている。
   同じ `0` でも B列が通常の `ProjectInsertMessage` である EXPECTED_REQUEST_HEADER では保持される。

## 4. 採取手順（再現用）

```bash
cd /home/k10949/workspace/nablarch-example-mom-sync-send-batch
tmp=$(mktemp -d)
git show ea87965:src/test/java/com/nablarch/example/SendProjectInsertMessageRequestTest.xlsx > "$tmp/orig.xlsx"
python3 -I .rn/ntf-yaml-support/scripts/xlsx_dump.py "$tmp/orig.xlsx" testAbnormalEnd
```
