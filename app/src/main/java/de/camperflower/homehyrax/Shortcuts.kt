package de.camperflower.homehyrax

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.PathParser
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlin.math.min

object Shortcuts {

    fun launchIntent(ctx: Context, app: WebApp): Intent =
        if (app.isAction) Intent(ctx, ActionActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("homehyrax://action/${app.id}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        else if (app.isCamera) Intent(ctx, CameraActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("homehyrax://camera/${app.id}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        else Intent(ctx, WebAppActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("homehyrax://app/${app.id}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

    fun pinSupported(ctx: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)

    /** Fragt den Launcher, ein Symbol auf den Startbildschirm zu legen. */
    fun pin(ctx: Context, app: WebApp): Boolean {
        if (!pinSupported(ctx)) return false
        return runCatching { ShortcutManagerCompat.requestPinShortcut(ctx, info(ctx, app), null) }.getOrDefault(false)
    }

    /** Name/Bild eines bereits abgelegten Symbols aktualisieren. */
    fun update(ctx: Context, app: WebApp) {
        runCatching { ShortcutManagerCompat.updateShortcuts(ctx, listOf(info(ctx, app))) }
    }

    /** IDs der Web-Apps, deren Symbol gerade auf dem Startbildschirm liegt. */
    fun pinnedIds(ctx: Context): Set<String> = runCatching {
        ShortcutManagerCompat.getShortcuts(ctx, ShortcutManagerCompat.FLAG_MATCH_PINNED).map { it.id }.toSet()
    }.getOrDefault(emptySet())

    fun remove(ctx: Context, appId: String) {
        runCatching { ShortcutManagerCompat.disableShortcuts(ctx, listOf(appId), ctx.getString(R.string.shortcut_deleted)) }
    }

    private fun info(ctx: Context, app: WebApp): ShortcutInfoCompat {
        // leerer Name: ShortcutInfo verlangt eine Beschriftung (sonst Absturz)
        val label = app.name.trim().ifEmpty { ctx.getString(R.string.app_name) }
        return ShortcutInfoCompat.Builder(ctx, app.id)
            .setShortLabel(label.take(24))
            .setLongLabel(label)
            .setIcon(IconCompat.createWithAdaptiveBitmap(adaptiveIcon(ctx, app)))
            .setIntent(launchIntent(ctx, app))
            .build()
    }

    /**
     * Adaptive-Icon-Bitmap (108-dp-Raster, 432 px). Der Launcher schneidet
     * ringsum ~1/6 weg (Maske). Ein eigenes Bild fuellt die sichtbare Zone ganz
     * (die Maske schneidet es sauber zu); was darueber hinausgeht, bekommt die
     * Farbe des Bildrands (eine Durchschnittsfarbe ergaebe sichtbare Ringe um helle Logos).
     * Symbol/Logo: weiss auf der App-Farbe.
     */
    fun adaptiveIcon(ctx: Context, app: WebApp): Bitmap =
        render(app, ctx.store.icon(app.id), 432, adaptive = true)

    /** Rundes Icon fuer Liste/Recents. */
    fun roundIcon(ctx: Context, app: WebApp, size: Int = 192): Bitmap =
        render(app, ctx.store.icon(app.id), size, adaptive = false)

    /** Gemeinsamer Zeichner (auch fuer die Editor-Vorschau mit ungespeichertem Bild). */
    fun render(app: WebApp, img: Bitmap?, size: Int, adaptive: Boolean): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val full = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val bg = if (img != null) (if (adaptive) edgeColor(img) else averageColor(img)) else app.color
        // Adaptive: Hintergrund randlos (Launcher maskiert), sonst abgerundete Kachel
        if (adaptive) c.drawColor(bg)
        else c.drawRoundRect(full, size * 0.28f, size * 0.28f, p.apply { color = bg })

        // sichtbarer Bereich: adaptive ~72/108 des Rasters, sonst alles
        val zone = if (adaptive) size * 0.62f else size.toFloat()
        // eigenes Bild: etwas groesser als die sichtbare Zone (72/108 = 0,667), damit kein Rand bleibt
        val imgZone = if (adaptive) size * 0.70f else size.toFloat()
        val sym = Symbols.find(app.symbol)
        when {
            img != null && adaptive -> {
                val o = (size - imgZone) / 2f
                c.drawBitmap(img, null, RectF(o, o, o + imgZone, o + imgZone), p)   // Launcher-Maske rundet
            }
            img != null -> {
                val save = c.saveLayer(full, null)
                p.color = Color.WHITE
                c.drawRoundRect(full, size * 0.28f, size * 0.28f, p)
                p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                c.drawBitmap(img, null, full, p)
                p.xfermode = null
                c.restoreToCount(save)
            }
            sym != null -> Symbols.draw(c, sym.icon, size.toFloat(), zone * (if (adaptive) 0.72f else 0.58f))
            else -> drawLogo(c, size.toFloat(), zone * (if (adaptive) 0.62f else 0.6f))   // Standard = App-Logo
        }
        return out
    }

    /** Haeufigste Farbe am Bildrand (deckende Pixel) - fuellt nahtlos, was ueber das Bild hinausgeht. */
    private fun edgeColor(img: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(img, 32, 32, true)
        val counts = HashMap<Int, Int>()
        for (i in 0 until 32) for (px in intArrayOf(s.getPixel(i, 0), s.getPixel(i, 31), s.getPixel(0, i), s.getPixel(31, i))) {
            if (Color.alpha(px) < 200) continue
            val key = Color.rgb(Color.red(px) and 0xF0, Color.green(px) and 0xF0, Color.blue(px) and 0xF0)   // grob buendeln
            counts[key] = (counts[key] ?: 0) + 1
        }
        val top = counts.maxByOrNull { it.value }?.key ?: return averageColor(img)
        // Mittelwert der Randpixel dieses Buendels = echte Farbe statt gerundeter
        var r = 0; var g = 0; var b = 0; var n = 0
        for (i in 0 until 32) for (px in intArrayOf(s.getPixel(i, 0), s.getPixel(i, 31), s.getPixel(0, i), s.getPixel(31, i))) {
            if (Color.alpha(px) < 200 || Color.rgb(Color.red(px) and 0xF0, Color.green(px) and 0xF0, Color.blue(px) and 0xF0) != top) continue
            r += Color.red(px); g += Color.green(px); b += Color.blue(px); n++
        }
        return Color.rgb(r / n, g / n, b / n)
    }

    /** Durchschnittsfarbe, leicht abgedunkelt - ruhiger Rand um Fotos/Screenshots. */
    private fun averageColor(img: Bitmap): Int {
        val px = Bitmap.createScaledBitmap(img, 1, 1, true).getPixel(0, 0)
        return Color.rgb((Color.red(px) * 0.8f).toInt(), (Color.green(px) * 0.8f).toInt(), (Color.blue(px) * 0.8f).toInt())
    }

    /** App-Logo (Haus mit Klippschliefer-Kopf und Schluessel) einfarbig und mittig; `width` = Breite des Motivs. */
    fun drawLogo(c: Canvas, box: Float, width: Float, color: Int = Color.WHITE) {
        val k = width / Logo.WIDTH
        val m = Matrix().apply { setTranslate(-Logo.CX, -Logo.CY); postScale(k, k); postTranslate(box / 2f, box / 2f) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        // evenOdd: Kopf und Ohr-Schleife als Loecher im Haus, Loch im Schluesselring
        val path = PathParser.createPathFromPathData(Logo.HOUSE).apply { transform(m); fillType = Path.FillType.EVEN_ODD }
        c.drawPath(path, p)
    }

    /** Logo als transparente Bitmap (fuer die Symbolauswahl). */
    fun logoBitmap(px: Int): Bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also {
        drawLogo(Canvas(it), px.toFloat(), px * 0.9f)
    }

    /** Bild aus der Galerie: mittig quadratisch zuschneiden, 432 px. */
    fun loadSquare(ctx: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 432) sample *= 2
        val src = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val side = min(src.width, src.height)
        val crop = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        Bitmap.createScaledBitmap(crop, 432, 432, true)
    }.getOrNull()
}
