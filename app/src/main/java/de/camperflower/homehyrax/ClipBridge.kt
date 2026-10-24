package de.camperflower.homehyrax

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONObject

/**
 * Zwischenablage fuer die Seite einer Web-App: `window.HomeHyrax.leseZwischenablage()` liefert ein Promise auf einen
 * JSON-Text. Die WebView gibt `navigator.clipboard.read` nicht frei, deshalb liest die App selbst.
 * Bild: {"ok":true,"art":"bild","mime":…,"daten":Base64} (erstes Bild, max. 20 MB) · Text: {"ok":true,"art":"text","text":…}
 * Fehler: {"ok":false,"fehler":"leer|kein_inhalt|zu_gross|verweigert|intern","meldung":…}
 */
object ClipBridge {
    const val MAX_BYTES = 20L * 1024 * 1024
    private const val MAX_TEXT = 1024 * 1024
    private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/heic", "image/heif")
    private val ALIASES = mapOf("image/jpg" to "image/jpeg", "image/pjpeg" to "image/jpeg")

    /** Name des Nachrichtenkanals (nur fuer den Ursprung des Eintrags, nur Hauptseite) */
    const val CHANNEL = "HomeHyraxKanal"

    /**
     * Stellt window.HomeHyrax bereit. Antwort ueber den Kanal = "<Nummer der Anfrage>\n<JSON-Text>" (nicht noch einmal
     * in JSON verpackt: bei 20-MB-Bildern zaehlt jede Kopie).
     */
    const val JS =
        "(function(){if(window.top!==window||!window.$CHANNEL||window.HomeHyrax)return;" +
        "var k=window.$CHANNEL,n=0,w={};" +
        "k.onmessage=function(e){var d=String(e.data),i=d.indexOf('\\n'),id=+d.slice(0,i),f=w[id];" +
        "if(i>0&&typeof f==='function'){delete w[id];f(d.slice(i+1));}};" +
        "window.HomeHyrax=Object.freeze({leseZwischenablage:function(){return new Promise(function(r){" +
        "var id=++n;w[id]=r;k.postMessage(JSON.stringify({id:id}));});}});})();"

    fun fehler(code: String, meldung: String? = null): String =
        JSONObject().put("ok", false).put("fehler", code).apply { if (meldung != null) put("meldung", meldung) }.toString()

    /** Antwort an die Seite: Nummer der Anfrage + JSON-Text */
    fun antwort(id: Int, json: String): String = "$id\n$json"

    /** Nummer der Anfrage aus der Nachricht der Seite, null = keine gueltige Anfrage */
    fun anfrage(data: String?): Int? = runCatching { JSONObject(data ?: return null).getInt("id") }.getOrNull()

    /** Als sensibel markiert (Passwort-Manager, Android 13+): nie an eine Seite geben */
    fun sensitive(clip: ClipData?): Boolean =
        Build.VERSION.SDK_INT >= 33 && clip?.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true

    /**
     * Inhalt lesen (im Hintergrund aufrufen: Bilder kommen per ContentResolver). [clip] hat der UI-Thread vorher
     * mit getPrimaryClip() geholt; null = nichts in der Zwischenablage.
     */
    fun lesen(ctx: Context, clip: ClipData?): String = try {
        if (clip == null || clip.itemCount == 0) fehler("leer")
        else bild(ctx, clip) ?: text(ctx, clip) ?: fehler("kein_inhalt")
    } catch (e: SecurityException) {
        fehler("verweigert")
    } catch (e: TooLarge) {
        fehler("zu_gross")
    } catch (e: OutOfMemoryError) {
        fehler("zu_gross")
    } catch (e: Exception) {
        fehler("intern", e.javaClass.simpleName)
    }

    private class TooLarge : Exception()

    private fun bild(ctx: Context, clip: ClipData): String? {
        val described = (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it).lowercase() }
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val mime = imageType(ctx, uri, described) ?: continue
            val b64 = Base64.encodeToString(readLimited(ctx, uri), Base64.NO_WRAP)
            // von Hand zusammengesetzt (Base64 und Typ brauchen kein Escaping): spart eine Kopie gegenueber JSONObject
            return "{\"ok\":true,\"art\":\"bild\",\"mime\":\"$mime\",\"daten\":\"$b64\"}"
        }
        return null
    }

    private fun imageType(ctx: Context, uri: Uri, described: List<String>): String? {
        val t = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()?.lowercase()?.let { ALIASES[it] ?: it }
        if (t != null && t in IMAGE_TYPES) return t
        if (t != null && !t.startsWith("image/")) return null
        // Typ unbekannt oder ungewoehnlich: nur, wenn die Zwischenablage genau einen erlaubten Bildtyp angibt
        return described.map { ALIASES[it] ?: it }.filter { it in IMAGE_TYPES }.distinct().singleOrNull()
    }

    private fun readLimited(ctx: Context, uri: Uri): ByteArray {
        // Groesse vorab, wenn bekannt: frueh "zu gross" und Puffer passend anlegen
        val size = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
        if (size != null && size > MAX_BYTES) throw TooLarge()
        val input = ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("no stream")
        input.use { s ->
            val out = java.io.ByteArrayOutputStream(if (size != null && size > 0) size.toInt() else 64 * 1024)
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw TooLarge()
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    private fun text(ctx: Context, clip: ClipData): String? {
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val uri = item.uri
            if (item.text == null && uri == null) continue   // reiner Intent-Eintrag: kein Text
            val t = item.text?.toString()?.takeIf { it.isNotEmpty() }
                ?: item.coerceToText(ctx)?.toString()?.takeIf { it.isNotEmpty() && it != uri?.toString() }
                ?: continue
            if (t.length > MAX_TEXT) throw TooLarge()
            return JSONObject().put("ok", true).put("art", "text").put("text", t).toString()
        }
        return null
    }
}
