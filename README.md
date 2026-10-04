# Website

Source of [homehyrax.com](https://homehyrax.com) (English) and [homehyrax.com/de](https://homehyrax.com/de/) (German).

- Edit `src/en.html` / `src/de.html` (icons in `src/icons.svg`), then run `python site/build.py`
- Output: `public/` – published by `.github/workflows/pages.yml` (GitHub Pages)
- UI labels on the page must match the app's string resources (`app/src/main/res/values*/strings_*.xml`)
