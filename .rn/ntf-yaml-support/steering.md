# nablarch-example-mom-sync-send-batch NTF YAML 対応 steering

## Overview
- 目的・背景: NTF（Nablarch Testing Framework）のAI対応として、AIが読み書きできないバイナリ形式のExcelテストデータをYAML形式に移行する（背景資料: https://github.com/Fintan-contents/nablarch-system-development-guide/pull/211）。
  - `pom.xml` に `nablarch-testing-yaml:1.0.0-SNAPSHOT`（test 依存）と `nablarch-testing-converter:1.0.0-SNAPSHOT`（`<build><plugins>` のビルドプラグイン）を追加する
  - `YamlTestDataParser`（`testDataParser`）を、`testDataParser` が参照される全フェーズで有効になる設定ファイルに追加する（`interpreters` は `dateTimeInterpreter` → `compositeInterpreter` の2つのみ）
  - 全テスト用ExcelをYAMLへ変換し（サンプリング比較で一致確認）、Excelを削除したうえで全テストがパスする
  - 既存 `ntf-yaml-support` ブランチの履歴（過去の移行コミット）を維持し、その上に develop 一致のベースラインコミット → task-1〜5 を積み上げて**再実施**する
- 対象プロジェクト名: nablarch-example-mom-sync-send-batch
- 対象Excel（develop 時点）: `src/test/java/com/nablarch/example/SendProjectInsertMessageRequestTest.xlsx`（1件）
- 指示書: `.rn/ntf-yaml-support/ntf-yaml-support-instructions_mom-sync-send-batch_V1.6.md`（原本: `/home/k10949/instructions/ntf-yaml-support-instructions_mom-sync-send-batch_V1.6.md`）

## Build / Test
README（「実行手順」節）と `pom.xml` を確認した結果:
- `gsp` プロファイルは存在しない。`gsp-dba-maven-plugin` の `generate-ddl` / `execute-ddl` / `load-data` が `generate-sources` フェーズにバインドされており、`mvn generate-resources`（`mvn package` にも含まれる）でDBセットアップが行われる。エンティティは `build-helper-maven-plugin` で `target/generated-sources/entity` をソース追加する構成。
- NTF のメッセージ解決に日本語ロケールが必要なため、`LANG=ja_JP.UTF-8` を指定して実行する。
- 手順:
  ```
  LANG=ja_JP.UTF-8 mvn -B clean generate-resources
  LANG=ja_JP.UTF-8 mvn -B test
  ```
- テストは組込み artemis-server で完結し、MOM 受信側 Example の起動は不要。
- 実行環境: README 記載は Java 17 / Maven 3.9.9 以降。本作業は Apache Maven 3.9.16 / Maven 実行時の Java は Eclipse Temurin 17.0.20（`~/.mavenrc` で `JAVA_HOME` を temurin-17 に設定。`mvn -v` の `Java version` で確認）。シェルの `java` は mise 設定の OpenJDK 21.0.2 だが、ビルド・テスト・変換には使われない。
- 補足: README は `.mvn/jvm.config` に言及しているが、`develop` には存在しない（上記環境では無しで成功）。

## Decisions
- `YamlTestDataParser` は `src/test/resources/messaging-sync-send-boot.xml`（アクション起動 diConfig）に集約する。リクエスト単体テストは diConfig で `SystemRepository` を再初期化し、実行中に応答電文モックが `testDataParser` を参照するため。`unit-test.xml` は同ファイルを import しており、セットアップ/検証フェーズにも同じ定義が効く。本番用 `src/main/resources` には入れない。
- converter は test 依存ではなく `<build><plugins>` のビルドプラグインとして宣言する（XLS→YAML 変換時のみ使用。nablarch-example-batch 準拠）。

- Task #3 の `RESPONSE_HEADER_MESSAGES[case3]/[case4]` 再送要求フラグ差異（Excel `0` → YAML `""`、errorMode レコード）は、報告・停止のうえユーザー判断で許容し、変換結果を修正せずに採用する。

## Scripts
- `.rn/ntf-yaml-support/scripts/xlsx_dump.py`: Task #3 のサンプリング比較用。外部ライブラリなしで `.xlsx` の各シートのセル値（行・列・値・型）をダンプし、変換後 YAML と比較するために使う（使い方: `python3 xlsx_dump.py <xlsx> [sheet]`）。

## Tasks

### #1: ベースライン化（developと同一状態）し、全PASSを確認する
**Purpose**: 既存の `ntf-yaml-support` ブランチ（履歴維持）の先端の上に、ワーキングツリー全体を `develop` と同一内容へ揃えるコミットを1つ新規に積み、その状態で全テストがパスすることを確認する。
**Prerequisites**: none
**Steps**:
- [x] 既存の `ntf-yaml-support` ブランチで作業する（新規作成なし。先端 `12967ec`）
- [x] 過去の移行コミットを巻き戻さず、先端の上に処理を積む
- [x] `git read-tree -u --reset develop` → `git commit` でベースラインコミット `1188802` を作成（`git diff HEAD develop` 差分なし）
- [x] README を確認し、ビルド・テスト手順を把握する
- [x] steering.md と scripts の初版を作成する
- [x] 本指示書を `.rn/ntf-yaml-support/` 配下へコピーし、`cmp` で原本と同一であることを確認する
- [x] `LANG=ja_JP.UTF-8 mvn -B clean generate-resources`（BUILD SUCCESS）
- [x] `LANG=ja_JP.UTF-8 mvn -B test`（Tests run: 2, Failures: 0, Errors: 0, BUILD SUCCESS）
- [x] 結果を `checks/task-1.md` に記録する
- [x] 変更ファイルをコミットする
- [x] self-check
- [x] QA expert review (Agent: general-purpose / Opus) — OK
- [ ] user review（全task完了後にまとめて実施）
**Completion criteria**:
- 既存の `ntf-yaml-support` ブランチ上で作業しており、コミット履歴が維持されている
- ブランチの既存先端の上に、ワーキングツリー全体が `develop` と同一内容になる新規コミットが1つ積まれている
- `mvn test` が全テストパスで終了している（BUILD SUCCESS）
- 本指示書のコピーが `.rn/ntf-yaml-support/` 配下に原本と同一内容で配置され、コミットされている

### #2: 設定変更 — pom.xml と unit-test.xml にYAML対応を追加
**Purpose**: `pom.xml` に `nablarch-testing-yaml`（test 依存）と `nablarch-testing-converter`（ビルドプラグイン）を追加し、`YamlTestDataParser` を `testDataParser` が参照される全フェーズで有効になる設定ファイルに追加する。
**Prerequisites**: #1
**Steps**:
- [x] `pom.xml` に `nablarch-testing-yaml:1.0.0-SNAPSHOT`（test scope）を追加する
- [x] `pom.xml` の `<build><plugins>` に `nablarch-testing-converter:1.0.0-SNAPSHOT` を追加する（from=xls / to=yaml / input / output / overwrite。batch 準拠）
- [x] 同期送信系（応答電文を持つ）テストかを判定する → 該当（diConfig=`messaging-sync-send-boot.xml`、`RESPONSE_*` あり）
- [x] 判定結果に応じて `YamlTestDataParser` を配置する → `src/test/resources/messaging-sync-send-boot.xml` に集約（`unit-test.xml` が import しているため重複定義なし）
- [x] `mvn dependency:resolve` / `mvn help:describe` で依存・プラグインの解決を確認する（いずれも rc=0、`convert` ゴール解決）
- [x] 変更したファイルをコミットする
- [x] self-check
- [x] QA expert review (Agent: general-purpose / Opus) — OK
- [x] language expert review (Agent: general-purpose / Opus) — OK
- [x] software-engineering expert review (Agent: general-purpose / Opus) — OK
- [ ] user review（全task完了後にまとめて実施）
**Completion criteria**:
- `pom.xml` に `nablarch-testing-yaml:1.0.0-SNAPSHOT` が test スコープで追加されている
- `pom.xml` の `<build><plugins>` に `nablarch-testing-converter:1.0.0-SNAPSHOT` がビルドプラグインとして追加されている
- `YamlTestDataParser` が全フェーズで有効になる設定ファイルに追加され、`interpreters` が `dateTimeInterpreter` → `compositeInterpreter` の2つのみ
- `mvn dependency:resolve` が成功し、converter の `convert` ゴールが `mvn help:describe` で解決できる

### #3: ExcelテストデータをYAMLに変換
**Purpose**: `nablarch-testing-converter` で Excel テストデータを YAML に変換してソースツリーに配置し、サンプリングで内容を確認する。
**Prerequisites**: #2
**Steps**:
- [x] 対象 Excel ファイルの所在を確認する（`SendProjectInsertMessageRequestTest.xlsx` 1件）
- [x] 同名の `.xls` / `.xlsx` 併存を確認する（併存なし）
- [x] converter README の手順に従い YAML 変換する（Converted 1 file(s)、YAML 3ファイル）
- [x] 各 YAML ファイルについて Excel とサンプリング比較する → 差異あり: `testAbnormalEnd` の `RESPONSE_HEADER_MESSAGES[case3]/[case4]` 再送要求フラグ Excel `0` → YAML `""`。報告・停止し、**ユーザー判断で許容**
- [x] 比較結果を `checks/task-3.md` に記録する
- [x] 変換した YAML ファイルをコミットする（Excelは削除しない）
- [x] self-check
- [x] QA expert review (Agent: general-purpose / Opus) — OK（全セル機械比較で既知差異以外なし）
- [ ] user review（全task完了後にまとめて実施）
**Completion criteria**:
- 変換対象の全 Excel に対応する YAML が生成されている
- サンプリング比較で Excel と YAML の内容が一致している
- 変換後の YAML がコミットされている

### #4: Excelを削除する
**Purpose**: 変換対象だった Excel を削除してコミットする。
**Prerequisites**: #3
**Steps**:
- [x] 変換対象となった Excel を削除する（`SendProjectInsertMessageRequestTest.xlsx`）
- [x] 残存 Excel 0件を `checks/task-4.md` に記録する
- [x] 削除をコミットする
- [x] self-check
- [x] QA expert review (Agent: general-purpose / Opus) — OK
- [ ] user review（全task完了後にまとめて実施）
**Completion criteria**:
- 変換対象だった全 Excel ファイルが削除されている
- 削除がコミットされている

### #5: YAMLテストデータで全テストをパスさせる
**Purpose**: Excelなしで全テストがパスすることを確認する。
**Prerequisites**: #4
**Steps**:
- [x] `mvn test` が BUILD SUCCESS になることを確認する（`clean generate-resources` → `test`、Tests run: 2, Failures: 0, Errors: 0）
- [x] テストログで YamlTestDataParser が実際に使われていることを確認する（ログ: `.rn/ntf-yaml-support/logs/task-5-mvn-test.log`）
- [x] self-check
- [x] QA expert review (Agent: general-purpose / Opus) — OK
- [x] language expert review (Agent: general-purpose / Opus) — OK
- [x] software-engineering expert review (Agent: general-purpose / Opus) — OK
- [ ] user review（全task完了後にまとめて実施）
**Completion criteria**:
- `mvn test` が BUILD SUCCESS で終了する（全テストパス、Excel ファイルなし）
- テストログで YamlTestDataParser が実際に使われていることが確認できる

## State
- Status: #1〜#5 完了（全レビュー OK、#3 差異はユーザー許容）。まとめての user review 待ち。未 push
- Notes:
  - ベースライン前の先端 `12967ec`（過去の移行作業）は履歴に保持。
  - 未追跡ファイル `.rn/ntf-yaml-support/reports/logs/task-3-conversion.log`（過去作業の残置物）はコミット対象外として残置。
