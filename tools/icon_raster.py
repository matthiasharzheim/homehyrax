#!/usr/bin/env python3
"""App-Symbol 1:1 aus der Logo-Vorlage des Inhabers (Metallplatte auf gebuerstetem Metall, ohne Schrift):
quadratischer Ausschnitt um das Haus, auf alle Dichten verkleinert.
Schreibt res/mipmap-*/ic_launcher_background.png (108 dp, volle Flaeche; Vordergrund leer) und
intern/store/grafik/icon-512.png.
Aufruf:  python tools/icon_raster.py intern/marketing/homehyrax/logo-metall.jpg
Braucht opencv-python + numpy (nur zum Erzeugen)."""
import os
import sys

import cv2

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import icon  # noqa: E402

voll = cv2.imread(sys.argv[1], cv2.IMREAD_COLOR)
H, W = voll.shape[:2]
g = cv2.cvtColor(voll, cv2.COLOR_BGR2GRAY)
_, maske = cv2.threshold(cv2.GaussianBlur(g, (5, 5), 0), 150, 255, cv2.THRESH_BINARY)
k, _ = cv2.findContours(maske, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
hx, hy, hw, hh = cv2.boundingRect(max(k, key=cv2.contourArea))

# Hausbreite wie im Vektor-Symbol (Ecken sicher in der Launcher-Maske); die Vorlage ist schmaler als dieser
# Ausschnitt -> Ausschnitt auf die Bildbreite begrenzen (Haus dann etwas groesser, Ecken bleiben in der Maske)
anteil = (icon.WR - icon.WL) * icon.SCALE / 1024
S = min(round(hw / anteil), W, H)
cx, cy = hx + hw // 2, hy + hh // 2
x0 = min(max(cx - S // 2, 0), W - S)
y0 = min(max(cy - S // 2, 0), H - S)
bild = voll[y0:y0 + S, x0:x0 + S]

R = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
for name, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
    ordner = os.path.join(R, f"mipmap-{name}")
    os.makedirs(ordner, exist_ok=True)
    cv2.imwrite(os.path.join(ordner, "ic_launcher_background.png"), cv2.resize(bild, (px, px), interpolation=cv2.INTER_AREA))
cv2.imwrite(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "intern", "store", "grafik", "icon-512.png"),
            cv2.resize(bild, (512, 512), interpolation=cv2.INTER_AREA))
print("ok", S, "Hausanteil", round(hw / S, 3))
