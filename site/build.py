#!/usr/bin/env python3
"""Baut die Webseite: site/src/{en,de}.html + Symbole -> site/public/index.html (englisch, homehyrax.com)
und site/public/de/index.html (deutsch), dazu die Landeseite site/src/rollup1.html -> site/public/rollup1/index.html
(Ziel des QR-Codes auf dem HomeHyrax-Roll-Up, Adresse homehyrax.com/rollup1 ist gedruckt und bleibt dauerhaft). Aufruf: python site/build.py"""
from pathlib import Path

HIER = Path(__file__).resolve().parent
OUT = HIER / "public"
sprite = (HIER / "src" / "icons.svg").read_text(encoding="utf-8")
# Play-Link der Landeseite mit Kennung des Roll-Ups (Install Referrer / Kampagnen in der Play Console)
PLAY_ROLLUP = ("https://play.google.com/store/apps/details?id=de.camperflower.homehyrax&amp;hl=de"
            "&amp;referrer=utm_source%3Drollup%26utm_medium%3Dqr%26utm_campaign%3Drollup1")

for name, ziel, root in (("en", OUT / "index.html", ""), ("de", OUT / "de" / "index.html", "../"),
                         ("rollup1", OUT / "rollup1" / "index.html", "../")):
    html = (HIER / "src" / f"{name}.html").read_text(encoding="utf-8")
    html = html.replace("{{sprite}}", sprite).replace("{{root}}", root).replace("{{play}}", PLAY_ROLLUP)
    if "{{" in html:
        raise SystemExit(f"{name}: offener Platzhalter {html[html.index('{{'):][:30]}")
    ziel.parent.mkdir(parents=True, exist_ok=True)
    ziel.write_text(html, encoding="utf-8", newline="\n")
    print(ziel.relative_to(HIER), len(html) // 1024, "KB")
