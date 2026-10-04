#!/usr/bin/env python3
"""Prueft die Uebersetzungen (Englisch = values/, Deutsch = values-de/):
- beide Sprachen haben exakt dieselben Schluessel
- gleiche Platzhalter (%1$s, %2$d ...) je Schluessel
- jede R.string.x im Kotlin-Code existiert
- keine offensichtlichen deutschen Reste in values/ (Umlaute)
Ungenutzte Schluessel werden nur gemeldet. Exit-Code 1 bei Fehlern.
Aufruf aus dem Repo-Wurzelverzeichnis:  python3 tools/check_strings.py"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res")
SRC = os.path.join(ROOT, "app", "src", "main", "java")
PH = re.compile(r"%(\d+\$)?[sdf]")


def load(folder):
    out = {}
    for f in sorted(glob.glob(os.path.join(RES, folder, "strings*.xml"))):
        for e in ET.parse(f).getroot():
            if e.tag == "string":
                out[e.get("name")] = "".join(e.itertext())
    return out


def main():
    en, de = load("values"), load("values-de")
    errors = []
    en_only = sorted(set(en) - set(de) - {"app_name"})
    de_only = sorted(set(de) - set(en))
    if en_only:
        errors.append(f"nur Englisch, Deutsch fehlt: {en_only}")
    if de_only:
        errors.append(f"nur Deutsch, Englisch fehlt: {de_only}")
    for k in sorted(set(en) & set(de)):
        a, b = sorted(PH.findall(en[k])), sorted(PH.findall(de[k]))
        if sorted(PH.findall(en[k].replace("%%", ""))) != sorted(PH.findall(de[k].replace("%%", ""))):
            errors.append(f"Platzhalter verschieden in {k}: EN {a} / DE {b}")
        if re.search("[äöüÄÖÜß]", en[k]):
            errors.append(f"Umlaut im englischen Text {k}: {en[k]!r}")
    code = ""
    for f in glob.glob(os.path.join(SRC, "**", "*.kt"), recursive=True):
        code += open(f, encoding="utf-8").read()
    used = set(re.findall(r"R\.string\.(\w+)", code))
    missing = sorted(used - set(en))
    if missing:
        errors.append(f"im Code benutzt, aber nicht definiert: {missing}")
    unused = sorted(set(en) - used - {"app_name"})
    print(f"{len(en)} Texte (EN) / {len(de)} (DE), {len(used)} im Code benutzt")
    if unused:
        print(f"Hinweis - ungenutzt: {unused}")
    for e in errors:
        print("FEHLER:", e)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
