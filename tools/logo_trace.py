#!/usr/bin/env python3
"""Vektorisiert das Logo aus einer Vorlage (heller Umriss auf dunklem Grund) zu einem einzigen SVG-Pfad
(fillType evenOdd: Haus mit ausgespartem Kopf, Ohr-Loch; Schluessel als Flaeche mit Ring-Loch) im
1024er-Raster und schreibt tools/logo_pfade.py (wird von tools/icon.py genutzt).
Aufruf:  python tools/logo_trace.py <vorlage.jpg> <x0> <y0> <x1> <y1>   (Ausschnitt um das Haus)
Braucht opencv-python + numpy (nur zum Erzeugen, nicht im Build)."""
import os
import sys

import cv2
import numpy as np

src, x0, y0, x1, y1 = sys.argv[1], *map(int, sys.argv[2:6])
UP = 6                                         # hochskalieren -> glatte Kanten
SCHWELLE = 120
SIGMA = 1.6                                    # Glaettung in Vorlagen-Pixeln
MIN_FLAECHE = 0.0004                           # kleinere Flecken (Rauschen) weglassen, Anteil der Hausflaeche

img = cv2.imread(src, cv2.IMREAD_GRAYSCALE)[y0:y1, x0:x1]
img = cv2.GaussianBlur(img, (3, 3), 0)
big = cv2.resize(img, None, fx=UP, fy=UP, interpolation=cv2.INTER_CUBIC)
big = cv2.GaussianBlur(big, (0, 0), UP * 0.8)
_, mask = cv2.threshold(big, SCHWELLE, 255, cv2.THRESH_BINARY)
mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((UP, UP), np.uint8))

konturen, hier = cv2.findContours(mask, cv2.RETR_TREE, cv2.CHAIN_APPROX_NONE)
haus = max(range(len(konturen)), key=lambda i: cv2.contourArea(konturen[i]))
hx, hy, hw, hh = cv2.boundingRect(konturen[haus])
flaeche = cv2.contourArea(konturen[haus])

# Zielraster: Hauswaende 170..854 (wie bisher), Unterkante 870
ZL, ZR, ZU = 170, 854, 870
k = (ZR - ZL) / hw


def punkt(p):
    x, y = p[0]
    return (ZL + (x - hx) * k, ZU - (hy + hh - y) * k)


teile = []
for i, c in enumerate(konturen):
    if cv2.contourArea(c) < flaeche * MIN_FLAECHE:
        continue
    # Kontur entlang ihres Verlaufs glaetten (Vorlage ist klein und leicht wellig), dann vereinfachen
    p = c[:, 0, :].astype(np.float64)
    n = len(p)
    s = SIGMA * UP
    r = int(3 * s)
    kern = np.exp(-0.5 * (np.arange(-r, r + 1) / s) ** 2)
    kern /= kern.sum()
    if n > 2 * r + 1:
        ext = np.concatenate([p[-r:], p, p[:r]])
        p = np.stack([np.convolve(ext[:, j], kern, mode="valid") for j in (0, 1)], axis=1)
    a = cv2.approxPolyDP(p.astype(np.float32).reshape(-1, 1, 2), 0.35 * UP, True)
    pts = [punkt(p) for p in a]
    teile.append("M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in pts) + " Z")

peak = ZU - hh * k
pfad = " ".join(teile)
ziel = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logo_pfade.py")
with open(ziel, "w", encoding="utf-8") as f:
    f.write("# generiert von tools/logo_trace.py aus der Logo-Vorlage - nicht von Hand aendern\n")
    f.write(f"WL, WR, BOTTOM, TOP = {ZL}, {ZR}, {ZU}, {peak:.1f}\n")
    f.write(f"PATH = {pfad!r}\n")
print("ok", len(teile), "Teilpfade,", len(pfad), "Zeichen, Oberkante", round(peak))
