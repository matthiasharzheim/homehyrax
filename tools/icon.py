#!/usr/bin/env python3
"""Erzeugt das HomeHyrax-App-Symbol aus der vektorisierten Logo-Vorlage (tools/logo_pfade.py, erzeugt von
tools/logo_trace.py): Haus mit ausgespartem Klippschliefer-Kopf, Ohr-Schleife und Schluessel (Ring = Auge),
alles ein Pfad mit fillType evenOdd. App-Symbol in gebuerstetem Silber auf dunklem Grund, Einfarbig-Symbol und
Kachel-Logo (Logo.kt) einfarbig.
Aufruf aus dem Repo-Wurzelverzeichnis:  python3 tools/icon.py   (--vorschau [px] gibt ein SVG aus)
Erzeugt res/drawable/ic_launcher_foreground.xml, ic_launcher_background.xml, ic_launcher_monochrome.xml und
Logo.kt."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from logo_pfade import BOTTOM, PATH, TOP, WL, WR  # noqa: E402

HOUSE = PATH
CX, CY = (WL + WR) / 2, (TOP + BOTTOM) / 2
WIDTH = WR - WL                                     # Motivbreite (fuer Shortcuts.drawLogo)

# Skalierung: untere Hausecken liegen sicher innerhalb der Kreismaske (72 dp von 108 dp);
# groesser schneidet auch die Samsung-Maske die Ecken ab (am Geraet gesehen)
SCALE = round((1024 * 72 / 108 / 2 - 6) / (((WR - WL) / 2) ** 2 + (BOTTOM - CY) ** 2) ** 0.5, 3)

# gebuerstetes Silber (Verlauf schraeg ueber das Motiv) und dunkler Grund wie in der Vorlage
SILBER = [(0.0, "#FFF4F6F8"), (0.35, "#FFC4CAD1"), (0.55, "#FFF7F8FA"), (0.8, "#FFA9B1BA"), (1.0, "#FFE3E6EA")]
GRUND = [(0.0, "#FF2B343E"), (1.0, "#FF12171D")]

R = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable") + os.sep


def verlauf_xml(stops, x0, y0, x1, y1):
    items = "\n".join(f'                    <item android:offset="{o}" android:color="{c}" />' for o, c in stops)
    return f"""            <aapt:attr name="android:fillColor">
                <gradient android:type="linear" android:startX="{x0}" android:startY="{y0}"
                    android:endX="{x1}" android:endY="{y1}">
{items}
                </gradient>
            </aapt:attr>"""


def vec(comment, farbe=None):
    # farbe=None -> Silberverlauf; sonst einfarbig
    if farbe:
        pfad = f'        <path android:fillColor="{farbe}" android:fillType="evenOdd" android:pathData="{HOUSE}" />'
    else:
        pfad = (f'        <path android:fillType="evenOdd" android:pathData="{HOUSE}">\n'
                f'{verlauf_xml(SILBER, WL, TOP, WR, BOTTOM)}\n        </path>')
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} (generiert von tools/icon.py) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="1024" android:viewportHeight="1024">
    <group android:pivotX="512" android:pivotY="512" android:scaleX="{SCALE}" android:scaleY="{SCALE}"
        android:translateX="{512 - CX:.0f}" android:translateY="{512 - CY:.0f}">
{pfad}
    </group>
</vector>
"""


def hintergrund():
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Dunkler Grund (generiert von tools/icon.py) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="1024" android:viewportHeight="1024">
        <path android:pathData="M0,0 L1024,0 L1024,1024 L0,1024 Z">
{verlauf_xml(GRUND, 0, 0, 0, 1024)}
        </path>
</vector>
"""


LEER = """<?xml version="1.0" encoding="utf-8"?>
<!-- leerer Vordergrund: das Motiv steckt im Hintergrund-Bild (generiert von tools/icon.py) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="1024" android:viewportHeight="1024">
    <path android:fillColor="#00000000" android:pathData="M0,0 L1,0 L1,1 Z" />
</vector>
"""


def svg_verlauf(id_, stops, x0, y0, x1, y1):
    s = "".join(f'<stop offset="{o}" stop-color="#{c[3:]}"/>' for o, c in stops)
    return f'<linearGradient id="{id_}" gradientUnits="userSpaceOnUse" x1="{x0}" y1="{y0}" x2="{x1}" y2="{y1}">{s}</linearGradient>'


def svg_logo(fill="url(#silber)"):
    """Motiv als SVG-Gruppe im 1024er-Raster (fuer Vorschau und Store-Grafiken), inkl. Verlaufsdefinition."""
    return (f'<defs>{svg_verlauf("silber", SILBER, WL, TOP, WR, BOTTOM)}</defs>'
            f'<path d="{HOUSE}" fill="{fill}" fill-rule="evenodd"/>')


def svg_preview(size=300):
    """Zum Anschauen im Browser (gleiche Geometrie wie das App-Symbol)."""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="{size}" height="{size}">'
            f'<defs>{svg_verlauf("grund", GRUND, 0, 0, 0, 1024)}</defs>'
            f'<rect width="1024" height="1024" rx="230" fill="url(#grund)"/>'
            f'<g transform="translate(512 512) scale({SCALE}) translate(-{CX} -{CY})">{svg_logo()}</g></svg>')


if __name__ == "__main__":
    if "--vorschau" in sys.argv:
        print(svg_preview(int(sys.argv[-1]) if sys.argv[-1].isdigit() else 300))
        sys.exit(0)
    # App-Symbol selbst: Bild aus der Vorlage (tools/icon_raster.py, mipmap ic_launcher_background) -> Vordergrund leer
    open(R + "ic_launcher_foreground.xml", "w", encoding="utf-8").write(LEER)
    open(R + "ic_launcher_monochrome.xml", "w", encoding="utf-8").write(vec("Einfarbig fuer Themen-Symbole", "#FF000000"))
    # gleiches Motiv als Standard-Symbol fuer Web-Apps/Schaltflaechen (Shortcuts.drawLogo), einfarbig
    kt = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "java", "de", "camperflower",
                      "homehyrax", "Logo.kt")
    open(kt, "w", encoding="utf-8").write(f"""package de.camperflower.homehyrax

// generiert von tools/icon.py - nicht von Hand aendern
/** App-Logo im 1024er-Raster: ein Pfad (evenOdd) mit Haus, ausgespartem Klippschliefer-Kopf, Ohr und Schluessel. */
object Logo {{
    const val HOUSE = "{HOUSE}"
    const val WIDTH = {WIDTH}f
    const val CX = {CX}f
    const val CY = {CY}f
}}
""")
    print("ok")
