#!/usr/bin/env python3
"""Baut die Webseite: site/src/{en,de}.html + Symbole -> site/public/index.html (englisch, homehyrax.com)
und site/public/de/index.html (deutsch). Aufruf: python site/build.py"""
from pathlib import Path

HIER = Path(__file__).resolve().parent
OUT = HIER / "public"
sprite = (HIER / "src" / "icons.svg").read_text(encoding="utf-8")

for lang, ziel, root in (("en", OUT / "index.html", ""), ("de", OUT / "de" / "index.html", "../")):
    html = (HIER / "src" / f"{lang}.html").read_text(encoding="utf-8")
    html = html.replace("{{sprite}}", sprite).replace("{{root}}", root)
    if "{{" in html:
        raise SystemExit(f"{lang}: offener Platzhalter {html[html.index('{{'):][:30]}")
    ziel.parent.mkdir(parents=True, exist_ok=True)
    ziel.write_text(html, encoding="utf-8", newline="\n")
    print(ziel.relative_to(HIER), len(html) // 1024, "KB")
