#!/usr/bin/env python3
"""Sammelt die Lizenztexte aller Go-Module, die in der Android-Bibliothek landen
(GOOS=android, echte Abhaengigkeiten von wgbridge) -> app/src/main/assets/licenses_go.txt.
Aufruf aus dem Repo-Wurzelverzeichnis (build-go.sh macht das automatisch):  python3 tools/licenses.py
Bricht ab, sobald eine Copyleft-Lizenz (MPL/GPL/LGPL) auftaucht - die App soll frei davon bleiben."""
import json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
WG = os.path.join(HERE, "..", "wgbridge")
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "licenses_go.txt")

env = dict(os.environ, GOOS="android", GOARCH="arm64", CGO_ENABLED="1")
raw = subprocess.run(["go", "list", "-deps", "-json", "."], cwd=WG, env=env, check=True,
                     capture_output=True, text=True, encoding="utf-8").stdout
mods = {}
dec = json.JSONDecoder()
i = 0
while i < len(raw):
    while i < len(raw) and raw[i].isspace():
        i += 1
    if i >= len(raw):
        break
    obj, i = dec.raw_decode(raw, i)
    m = obj.get("Module")
    if m and not m.get("Main") and not obj.get("Standard"):
        mods[m["Path"]] = m

# gomobile linkt golang.org/x/mobile/bind/seq mit ein, "go list" sieht das nicht (nur Build-Tag "tools")
for extra in ["golang.org/x/mobile"]:
    if extra not in mods:
        info = json.loads(subprocess.run(["go", "mod", "download", "-json", extra], cwd=WG, env=env, check=True,
                                         capture_output=True, text=True, encoding="utf-8").stdout)
        mods[extra] = {"Path": extra, "Version": info.get("Version", ""), "Dir": info.get("Dir", "")}

def kind(t):
    if "Apache License" in t: return "Apache-2.0"
    if "Mozilla Public License" in t: return "MPL-2.0"
    if "GNU LESSER GENERAL PUBLIC" in t.upper(): return "LGPL"
    if "GNU GENERAL PUBLIC LICENSE" in t.upper(): return "GPL"
    if "Permission is hereby granted" in t: return "MIT"
    if "Permission to use, copy, modify, and/or distribute" in t or "Permission to use, copy, modify, and distribute" in t: return "ISC"
    if "Redistribution and use" in t: return "BSD-3-Clause" if "Neither the name" in t or "names of its" in t else "BSD-2-Clause"
    return "see text"

def read(d, files):
    return "\n\n".join(open(os.path.join(d, f), encoding="utf-8", errors="replace").read().strip() for f in files)

def listed(d, prefixes):
    return [f for f in sorted(os.listdir(d)) if f.upper().startswith(prefixes) and os.path.isfile(os.path.join(d, f))] if d else []

parts, missing, notices = [], [], []
for path in sorted(mods):
    m = mods[path]
    d = m.get("Dir") or ""
    files = listed(d, ("LICENSE", "LICENCE", "COPYING"))
    if not files:
        missing.append(path); continue
    text = read(d, files)
    lic = kind(text)
    # Apache-2.0 Abschnitt 4(d): NOTICE-Dateien muessen mit weitergegeben werden
    nfiles = listed(d, ("NOTICE",))
    if nfiles:
        notices.append(path)
        text += "\n\n" + read(d, nfiles)
    parts.append(f"=== {path} {m.get('Version','')}|{lic}\n{text}\n")
# Go selbst (Laufzeit wird einkompiliert)
goroot = subprocess.run(["go", "env", "GOROOT"], cwd=WG, capture_output=True, text=True, encoding="utf-8").stdout.strip()
parts.insert(0, "=== Go (runtime and standard library)|BSD-3-Clause\n" + open(os.path.join(goroot, "LICENSE"), encoding="utf-8").read().strip() + "\n")
bad = [p.split("\n", 1)[0] for p in parts if p.split("\n", 1)[0].endswith(("|MPL-2.0", "|GPL", "|LGPL"))]
if bad:
    sys.exit("Copyleft-Lizenz gefunden, bitte pruefen:\n" + "\n".join(bad))
open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(parts))
print(f"{len(parts)} Lizenzen -> {os.path.relpath(OUT)}", file=sys.stderr)
if missing:
    print("OHNE Lizenzdatei: " + ", ".join(missing), file=sys.stderr)
if notices:
    print("mit NOTICE: " + ", ".join(notices), file=sys.stderr)
