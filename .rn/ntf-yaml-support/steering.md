# Goal

対象プロジェクト：nablarch-example-mom-sync-send-batch

NTF（Nablarch Testing Framework）のAI対応として、AIが読み書きできないバイナリ形式のExcelテストデータをYAML形式に移行する。
参照: https://github.com/Fintan-contents/nablarch-system-development-guide/pull/211

対象は本リポジトリに存在するNTFのExcelテストデータ全件（現時点で1件、`.xlsx`）：
- `src/test/java/com/nablarch/example/SendProjectInsertMessageRequestTest.xlsx`

# Acceptance criteria

- `develop` ブランチから作成した `ntf-yaml-support` ブランチで全作業が行われている
- 作業前に既存の全テストがパスすることを確認済みである
- `pom.xml` に `nablarch-testing-yaml:1.0.0-SNAPSHOT` が test スコープの依存として追加されている
- `pom.xml` の `<build><plugins>` に `nablarch-testing-converter:1.0.0-SNAPSHOT` がビルドプラグイン（plugin直下 `<configuration>` に変換パラメータを設定し、CLIで `convert` ゴールを実行）として追加されている（test依存ではない）
- `src/test/resources/unit-test.xml` に `YamlTestDataParser` コンポーネントと `yamlInterpreters` リストが追加されている
- 変換対象の全てのテスト用ExcelファイルがそれぞれYAMLファイルに変換されている（サンプリングでExcelとYAMLの内容が一致することを確認済み）
- 変換後、変換対象だったExcelファイルが全て削除されている
- YAMLテストデータを使った状態で全テストがパスしている
- Excelファイルなしで全テストがパスしていることを確認済みであり、間違えてExcelでパスしていない

# Assumptions

- `nablarch-testing-yaml:1.0.0-SNAPSHOT` と `nablarch-testing-converter:1.0.0-SNAPSHOT` はローカルMavenリポジトリ（`~/.m2`）に存在する（確認済み）
- テスト実行にはH2インメモリ/ファイルDBを使用し、DBセットアップ済みである
- JDK / Maven / H2 などビルド・テスト実行環境は整備済みである
- YAMLファイルの出力先は `src/test/java` 配下（Excelと同じ場所）とし、`nablarch.test.resource-root` の設定変更は不要
- 変換ツール（`nablarch-testing-converter`）の実行手順は `/home/k10949/workspace/nablarch-testing-converter/README.md` に従う

# Build / Test procedure (README 確認結果)

本プロジェクトの README（「実行手順」節）および `pom.xml` を確認した結果、ビルド・テスト手順は以下の通り。

- 本プロジェクトには `gsp` プロファイルは存在しない。`gsp-dba-maven-plugin` は `generate-sources` フェーズに `generate-ddl` / `execute-ddl` / `load-data` ゴールがバインドされており、Mavenライフサイクル実行時に自動的に動作する。エンティティクラスは `src` 配下にコミット済みのため `generate-entity` ゴールは実行されない。
- DBセットアップ（DDL生成・実行・データ投入）: `mvn generate-resources`
- テスト実行: `mvn test`
- NTFはメッセージ解決に日本語ロケールを必要とするため、ビルド・テストは **`LANG=ja_JP.UTF-8`** を指定して実行する（`C.UTF-8` ではメッセージ解決に失敗しテストが落ちる）。
- 本番アプリの起動には MOM 受信側 Example が必要だが、本タスクで対象とする NTF テスト（`SendProjectInsertMessageRequestTest`）は組込み（artemis-server）で完結し、外部ブローカーは不要。

実行例:

```
LANG=ja_JP.UTF-8 mvn generate-resources
LANG=ja_JP.UTF-8 mvn test
```

# Rules

- commit every change; one completion marker per task
- push は行わない（コミットのみ実施し、リモートへの push は最後にユーザーが行う）
- テストクラス（`.java`）は変更しない
- `develop` から新規作成した `ntf-yaml-support` ブランチで作業する
- steering.md は各タスクで更新し、当該タスクのコミットに含める
- ビルド・テストの実行コマンドは上記「Build / Test procedure」に従う
- テストはExcelファイルが存在しない状態で最終パスを確認する
- YAML変換後はサンプリングでExcelとYAMLの内容を比較してから削除する
- サンプリング比較で差異が見つかった場合は修正せずユーザーに報告して停止する

# Tasks

### #1: 現状のビルド・テストで全PASSを確認する

**Purpose**: `develop` から `ntf-yaml-support` ブランチを作成し、変更前の状態で全テストがパスすることを確認する。

**Prerequisites**: none

**Steps**:

- [x] `develop` ブランチの存在を確認し、そこから `ntf-yaml-support` ブランチを新規作成する
- [x] 対象プロジェクトの README を確認し、ビルド・テストの実行手順を把握する
- [x] 隣接プロジェクトの `.rn/` 配下MDを参照し、steering.md の初版を作成する（README で確認したビルド・テスト手順を明記）
- [x] README で確認した手順でDBセットアップ／エンティティ準備を行う（`mvn generate-resources`）
- [x] README で確認した手順でテストを実行し、全テストがパスすることを確認する（`mvn test`）
- [x] 結果を `checks/task-1.md` に記録する（self-check列のみ）
- [x] 変更ファイル（check ファイルおよび更新した steering.md）をコミットする
- [x] self-check (OK/NG per completion criterion, record in checks/task-1.md)
- [x] QA expert review (Agent: general-purpose / Opus 4.8)
- [ ] user review

**Completion criteria**:

- `mvn test` が全テストパスで終了している（BUILD SUCCESS）

### #2: 設定変更 — pom.xml と unit-test.xml にYAML対応を追加

**Purpose**: `pom.xml` に `nablarch-testing-yaml`（test 依存）を追加し、`nablarch-testing-converter` を `<build><plugins>` のビルドプラグインとして追加し、`unit-test.xml` に `yamlInterpreters` リストと `YamlTestDataParser` コンポーネントの設定を追加する。converter はテスト実行時には不要で XLS→YAML 変換時のみ使う Maven プラグインのため、test 依存ではなくビルドプラグインとして宣言する。

**Prerequisites**: #1

**Steps**:

- [x] `pom.xml` に `nablarch-testing-yaml:1.0.0-SNAPSHOT`（test scope）を追加する
- [x] `pom.xml` の `<build><plugins>` に `nablarch-testing-converter:1.0.0-SNAPSHOT` をビルドプラグインとして追加する（`<configuration>` に from=xls / to=yaml / input / output / overwrite を設定）
- [x] `unit-test.xml` に `yamlInterpreters` リストと `YamlTestDataParser` コンポーネントを追加する（隣接プロジェクトを参考）
- [x] `mvn dependency:resolve` で yaml 依存が解決できること、`mvn help:describe` で converter プラグインの `convert` ゴールが解決できることを確認する
- [x] 変更したファイルをコミットする
- [x] self-check (OK/NG per completion criterion, record in checks/task-2.md)
- [ ] QA expert review (Agent: general-purpose / Opus 4.8)
- [ ] language expert review (Agent: general-purpose / Opus 4.8)
- [ ] software-engineering expert review (Agent: general-purpose / Opus 4.8)
- [ ] user review

**Completion criteria**:

- `pom.xml` に `nablarch-testing-yaml:1.0.0-SNAPSHOT` が test スコープで追加されている
- `pom.xml` の `<build><plugins>` に `nablarch-testing-converter:1.0.0-SNAPSHOT` がビルドプラグインとして追加されている
- `unit-test.xml` に `yamlInterpreters` リストと `testDataParser` コンポーネント（`YamlTestDataParser`）が追加されている
- `mvn dependency:resolve` が成功し、converter プラグインの `convert` ゴールが `mvn help:describe` で解決できる

### #3: ExcelテストデータをYAMLに変換

**Purpose**: `nablarch-testing-converter` を使って ExcelのテストデータをYAML形式に変換し、ソースツリー内に配置して、変換内容をサンプリングで確認する。

**Prerequisites**: #2

**Steps**:

- [x] 対象 Excel ファイルの所在を確認する（`SendProjectInsertMessageRequestTest.xlsx` 1件）
- [x] 同名の `.xls` と `.xlsx` が併存していないか確認する（併存なし）
- [x] README の手順に従い、全ての対象ExcelファイルをYAML変換する（`convert` ゴール、Converted 1 file(s)）
- [x] 変換後、各YAMLファイルについてExcelの内容とサンプリングで比較し、一致を確認する（各ファイル3件以上、差異なし）
- [x] サンプリング比較結果を `checks/task-3.md` に記録する
- [x] 変換したYAMLファイルをコミットする（Excelはまだ削除しない）
- [x] self-check (OK/NG per completion criterion, record in checks/task-3.md)
- [ ] QA expert review (Agent: general-purpose / Opus 4.8)
- [ ] user review

**Completion criteria**:

- 変換対象の全てのExcelファイルに対応するYAMLファイルが生成されている
- サンプリング比較（各ファイル最低3件、元データが3件未満の場合は元データ件数分）でExcelとYAMLの内容が一致している
- 変換後のYAMLファイルがコミットされている

### #4: Excelを削除する

**Purpose**: 変換対象だった Excel を削除してコミットする。

**Prerequisites**: #3

**Steps**:

- [x] 変換対象となった Excel ファイルを削除する
- [x] 削除結果（残存Excelが0件であること）を `checks/task-4.md` に記録する
- [x] Excelファイル削除をコミットする
- [x] self-check (OK/NG per completion criterion, record in checks/task-4.md)
- [ ] QA expert review (Agent: general-purpose / Opus 4.8)
- [ ] user review

**Completion criteria**:

- 変換対象だった全 Excel ファイルが削除されている
- 削除がコミットされている

### #5: YAMLテストデータで全テストをパスさせる

**Purpose**: Excelなしで全テストがパスすることを確認する。

**Prerequisites**: #4

**Steps**:

- [ ] `mvn test` が BUILD SUCCESS になることを確認する
- [ ] テストログで YamlTestDataParser が実際に使われていることを確認する
- [ ] self-check (OK/NG per completion criterion, record in checks/task-5.md)
- [ ] QA expert review (Agent: general-purpose / Opus 4.8)
- [ ] language expert review (Agent: general-purpose / Opus 4.8)
- [ ] software-engineering expert review (Agent: general-purpose / Opus 4.8)
- [ ] user review

**Completion criteria**:

- `mvn test` が BUILD SUCCESS で終了する（全テストパス、Excel ファイルなし）
- テストログで YamlTestDataParser が実際に使われていることが確認できる

# Decisions

- converter は Maven ビルドプラグイン（`<build><plugins>`）として宣言する（test 依存ではない）。テスト実行時には不要で XLS→YAML 変換時のみ使うため。web/batch テンプレートに準拠。
- `yamlInterpreters` は batch テンプレートに合わせ、`test-data.xml`（test の `messaging-sync-send-boot.xml` 経由でインポート済み）が定義する名前付きコンポーネントへの `component-ref` で構成する（`QuotationTrimmer` を除外）。

# State

- **Status**: in progress
- **Date**: 2026-10-06
- **Last completed**: #1 現状ビルド・テスト全PASS確認（Tests run: 2, BUILD SUCCESS）
- **Next**: #2 設定変更
- **Notes**: 対象Excelは `SendProjectInsertMessageRequestTest.xlsx` の1件のみ（`.xls`併存なし）。
