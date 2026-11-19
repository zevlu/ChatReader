#!/usr/bin/env python3
"""操作員小抄 → A4 PDF（無頭 Chromium，Playwright）。印出頁數；超過 2 頁（雙面一張）結束代碼 1。
用法：~/codriver-dgx/pdf-venv/bin/python tools/dgx/cheatsheet_pdf.py
（第一次：python3 -m venv ~/codriver-dgx/pdf-venv && ~/codriver-dgx/pdf-venv/bin/pip install playwright pypdf
          && ~/codriver-dgx/pdf-venv/bin/python -m playwright install chromium；都在使用者目錄，不用 sudo）"""
import pathlib, re, sys
from playwright.sync_api import sync_playwright
here = pathlib.Path(__file__).resolve().parent
src, out = here / "operator_cheatsheet.html", here / "operator_cheatsheet.pdf"
with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page()
    pg.goto(src.as_uri())
    pg.emulate_media(media="print")
    pg.pdf(path=str(out), format="A4", prefer_css_page_size=True, print_background=True)
    b.close()
n = len(re.findall(rb"/Type\s*/Page[^s]", out.read_bytes()))
print(f"{out}：{n} 頁" + ("（正反一張 ✅）" if n <= 2 else "（超過一張 ❌，要精簡文字）"))
sys.exit(0 if n <= 2 else 1)
