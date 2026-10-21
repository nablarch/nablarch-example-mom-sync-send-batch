#!/usr/bin/env python3
"""
xlsx_dump.py — NTF の XLS→YAML 変換結果をサンプリング比較するための補助スクリプト。

目的:
  openpyxl 等のライブラリが無い環境でも .xlsx の各シートのセル値を取り出し、
  変換後 YAML と目視サンプリング比較できるよう、シート単位でセルグリッド
  （行番号・列・値・型[s=文字列/n=数値]）をダンプする。

用途:
  Task #3「ExcelテストデータをYAMLに変換」のサンプリング比較で使用。
  NTF/POI は数値セルを double として読み込むため、整数 1 は "1.0" として
  表現される（コンバータ出力の "1.0" と整合する）。本スクリプトは型も表示し、
  この表現差が「数値セルの忠実な表現」であることを確認できるようにする。

使い方:
  python3 xlsx_dump.py <path-to-xlsx> [sheet_name]
"""
import sys
import re
import zipfile
from xml.etree import ElementTree as ET

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"


def col_letter(cell_ref):
    return re.match(r"([A-Z]+)", cell_ref).group(1)


def load_shared_strings(z):
    strings = []
    try:
        data = z.read("xl/sharedStrings.xml")
    except KeyError:
        return strings
    root = ET.fromstring(data)
    for si in root.findall(f"{NS}si"):
        # ルビ（phonetic, <rPh>）はセルの実値ではないため除去する。
        # これを含めると POI/コンバータが読む実値と乖離する。
        for rph in si.findall(f"{NS}rPh"):
            si.remove(rph)
        # 連結された複数 run (<r><t>) も結合する
        texts = [t.text or "" for t in si.iter(f"{NS}t")]
        strings.append("".join(texts))
    return strings


def sheet_name_map(z):
    wb = ET.fromstring(z.read("xl/workbook.xml"))
    rels = ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))
    rid_to_target = {}
    rns = "{http://schemas.openxmlformats.org/package/2006/relationships}"
    for rel in rels.findall(f"{rns}Relationship"):
        rid_to_target[rel.get("Id")] = rel.get("Target")
    rid_attr = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"
    result = []
    for sh in wb.find(f"{NS}sheets").findall(f"{NS}sheet"):
        name = sh.get("name")
        rid = sh.get(rid_attr)
        target = rid_to_target.get(rid, "")
        if not target.startswith("xl/"):
            target = "xl/" + target.lstrip("/")
        result.append((name, target))
    return result


def dump_sheet(z, name, path, shared):
    root = ET.fromstring(z.read(path))
    print(f"===== SHEET: {name} ({path}) =====")
    sheet_data = root.find(f"{NS}sheetData")
    if sheet_data is None:
        print("  (no sheetData)")
        return
    any_row = False
    for row in sheet_data.findall(f"{NS}row"):
        r = row.get("r")
        cells = []
        for c in row.findall(f"{NS}c"):
            ref = c.get("r")
            t = c.get("t")  # 's' shared, 'str' formula str, 'inlineStr', else numeric
            v = c.find(f"{NS}v")
            if t == "s":
                val = shared[int(v.text)] if v is not None else ""
                typ = "s"
            elif t == "inlineStr":
                isv = c.find(f"{NS}is")
                val = "".join(x.text or "" for x in isv.iter(f"{NS}t")) if isv is not None else ""
                typ = "s"
            elif t == "str":
                val = v.text if v is not None else ""
                typ = "s"
            elif v is not None:
                val = v.text
                typ = "n"
            else:
                continue
            cells.append(f"{col_letter(ref)}={val!r}[{typ}]")
        if cells:
            any_row = True
            print(f"  row {r}: " + " | ".join(cells))
    if not any_row:
        print("  (empty: no non-empty cells)")


def main():
    path = sys.argv[1]
    want = sys.argv[2] if len(sys.argv) > 2 else None
    z = zipfile.ZipFile(path)
    shared = load_shared_strings(z)
    for name, target in sheet_name_map(z):
        if want and name != want:
            continue
        dump_sheet(z, name, target, shared)


if __name__ == "__main__":
    main()
