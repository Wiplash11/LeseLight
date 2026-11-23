"""Builds app/src/main/assets/www/index.html from web/leselicht.html."""
import pathlib, re
root = pathlib.Path(__file__).resolve().parent.parent
src = (root / "web" / "leselicht.html").read_text(encoding="utf-8")
src = src.replace("https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.min.js", "lib/pdf.min.js")
src = src.replace('<script src="https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js">', '<script src="lib/pdf.worker.min.js">')
src = src.replace("https://cdnjs.cloudflare.com/ajax/libs/mammoth/1.6.0/mammoth.browser.min.js", "lib/mammoth.browser.min.js")
assert "cdnjs" not in src.split("GlobalWorkerOptions")[0], "unreplaced CDN script"
head = ('<!doctype html><html lang="de"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
        '<style>:root{color-scheme:light}body{margin:0;font:14px system-ui,sans-serif}img{max-width:100%}[hidden]{display:none!important}</style>'
        '<script src="bridge.js"></script></head><body>')
out = root / "app/src/main/assets/www"
out.mkdir(parents=True, exist_ok=True)
(out / "index.html").write_text(head + src + "</body></html>", encoding="utf-8")
(out / "bridge.js").write_text((root / "web/bridge.js").read_text(encoding="utf-8"), encoding="utf-8")
print("index.html", len(head + src))
