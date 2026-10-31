# Website

Source of [homehyrax.com](https://homehyrax.com) (English) and [homehyrax.com/de](https://homehyrax.com/de/) (German).

- Edit `src/en.html` / `src/de.html` (icons in `src/icons.svg`), then run `python site/build.py`
- Output: `public/` – published by `.github/workflows/pages.yml` (GitHub Pages)
- Landing page `src/rollup1.html` → `public/rollup1/index.html` (homehyrax.com/rollup1, target of the QR code printed on the roll-up banner – keep the path). Its Play link carries a campaign referrer (`PLAY_ROLLUP` in `build.py`)
- Fonts are self-hosted in `public/assets/fonts/` (no requests to Google), licence in `fonts/OFL.txt`
- UI labels on the page must match the app's string resources (`app/src/main/res/values*/strings_*.xml`)
