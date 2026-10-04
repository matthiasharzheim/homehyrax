package de.camperflower.homehyrax

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import de.camperflower.homehyrax.wgbridge.Wgbridge
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Einstellungen an ein anderes Handy weitergeben: als QR-Code (ohne Bilder,
 * passt sonst nicht hinein) oder als Datei (mit Bildern).
 * Format: "HT1:" + base64url(deflate(JSON)). Das Praefix "HT1" stammt vom frueheren
 * App-Namen und bleibt, damit vorhandene Exporte weiter eingelesen werden koennen.
 */
object Share {
    private const val PREFIX = "HT1:"

    fun isPayload(text: String?) = text?.trim()?.startsWith(PREFIX) == true

    fun export(ctx: Context, apps: List<WebApp>, tunnels: List<Tunnel>, withIcons: Boolean): String {
        val allTunnels = ctx.store.tunnels()
        val t = JSONArray()
        tunnels.forEach { tn ->
            // Tailscale: ohne Auth-Key - jedes Handy meldet sich selbst an
            val c = if (tn.isTailscale) runCatching { JSONObject(tn.config).apply { remove("authkey") }.toString() }.getOrDefault("{}") else tn.config
            t.put(JSONObject().put("i", tn.id).put("n", tn.name).put("c", c).put("ty", tn.type))
        }
        val a = JSONArray()
        apps.forEach { app ->
            val o = JSONObject()
                .put("n", app.name).put("u", app.url).put("c", app.color)
                .put("s", app.keepScreenOn).put("dk", app.desktop).put("fs", app.fullscreen).put("at", app.alwaysTunnel)
            app.symbol?.let { o.put("y", it) }
            o.put("au", app.requireAuth)
            if (app.kind != KIND_WEB) {   // Schalter und Kamera: Art + Zugangsdaten
                o.put("kd", app.kind)
                app.action?.let { o.put("ac", it.toJson(it.pass)) }
            }
            app.trustedCert?.let { o.put("k", it) }
            app.tunnelId?.let { id ->
                o.put("t", id)
                allTunnels.firstOrNull { it.id == id }?.let { o.put("tn", it.name) }
            }
            if (withIcons) ctx.store.icon(app.id)?.let { o.put("img", pngBase64(it)) }
            a.put(o)
        }
        val json = JSONObject().put("v", 1).put("t", t).put("a", a).toString()
        return PREFIX + Base64.encodeToString(deflate(json.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    data class Result(val apps: Int, val tunnels: Int, val needTunnel: List<String>)

    /**
     * Vorbereiteter Import (noch nichts gespeichert): wird dem Nutzer zur Bestaetigung gezeigt
     * und erst mit apply() in einem Schritt uebernommen.
     */
    class Plan(
        val tunnels: List<Tunnel>,
        val apps: List<WebApp>,
        val icons: Map<String, Bitmap>,
        /** Anzeige: neue Eintraege, aktualisierte Eintraege, neue Verbindungen (Name + Endpunkt/Server), uebersprungene */
        val newApps: List<String>,
        val changedApps: List<String>,
        val newTunnels: List<String>,
        val skipped: List<String>,
        val needTunnel: List<String>,
    ) {
        val isEmpty: Boolean get() = tunnels.isEmpty() && apps.isEmpty() && icons.isEmpty()
    }

    /** Nur diese Adressarten werden uebernommen (kein javascript:, file:, intent: ...). */
    private val ALLOWED_SCHEMES = setOf("http", "https", "rtsp")
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    /**
     * Liest einen Export und plant die Uebernahme (laeuft im Hintergrund, speichert nichts).
     * Neue Eintraege werden angelegt. Vorhandene Eintraege (gleiche Adresse) bekommen nur Name,
     * Farbe/Symbol/Bild und Anzeige-Optionen; Verbindung, Art, Aktion samt Zugangsdaten, VPN-Stufe
     * und bestaetigte Zertifikate bleiben unveraendert, die Sperre wird nie abgeschwaecht.
     */
    fun plan(ctx: Context, text: String): Plan {
        val raw = Base64.decode(text.trim().removePrefix(PREFIX), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val o = JSONObject(String(inflate(raw)))
        val store = ctx.store
        val haveTunnels = store.tunnels()
        val haveApps = store.apps()

        val idMap = mutableMapOf<String, String>()
        val newTunnels = ArrayList<Tunnel>()
        val t = o.optJSONArray("t") ?: JSONArray()
        for (i in 0 until t.length()) {
            val j = t.optJSONObject(i) ?: continue
            val type = j.optString("ty", TUNNEL_WG).takeIf { it == TUNNEL_WG || it == TUNNEL_TS } ?: continue
            // Tailscale: Auth-Key nie uebernehmen - jedes Handy meldet sich selbst an
            val cfg = if (type == TUNNEL_TS) runCatching { JSONObject(j.getString("c")).apply { remove("authkey") }.toString() }.getOrNull() ?: continue
            else j.optString("c").trim()
            if (type == TUNNEL_WG && (cfg.isEmpty() || runCatching { Wgbridge.validateConfig(cfg) }.getOrDefault("?").isNotEmpty())) continue
            val same = (haveTunnels + newTunnels).firstOrNull { it.type == type && it.config.trim() == cfg }
            idMap[j.optString("i")] = same?.id ?: Tunnel(name = j.optString("n").ifBlank { type }, config = cfg, type = type)
                .also { newTunnels += it }.id
        }

        val a = o.optJSONArray("a") ?: JSONArray()
        val planned = LinkedHashMap<String, WebApp>()   // id -> Eintrag
        val icons = HashMap<String, Bitmap>()
        val newNames = ArrayList<String>()
        val changedNames = ArrayList<String>()
        val skipped = ArrayList<String>()
        val need = ArrayList<String>()
        for (i in 0 until a.length()) {
            val j = a.optJSONObject(i) ?: continue
            val name = j.optString("n").trim().take(80)
            val url = j.optString("u").trim()
            val uri = android.net.Uri.parse(url)
            val kind = j.optString("kd", KIND_WEB).takeIf { it in setOf(KIND_WEB, KIND_ACTION, KIND_CAMERA) } ?: KIND_WEB
            val action = if (kind == KIND_WEB) null else ShellyAction.fromJson(j.optJSONObject("ac")) { it }
            // Ziel pruefen: nur http/https/rtsp, kein Loopback/Link-Local (lokaler Proxy, andere Apps)
            if (name.isEmpty() || uri.scheme?.lowercase() !in ALLOWED_SCHEMES || Hosts.isForbidden(uri.host) ||
                (action != null && action.gen != 0 && Hosts.isForbidden(action.ip))) {
                skipped += name.ifEmpty { url.take(40) }; continue
            }
            val existing = haveApps.firstOrNull { it.url.equals(url, ignoreCase = true) }
                ?: planned.values.firstOrNull { it.url.equals(url, ignoreCase = true) }
            val img = j.optString("img").ifEmpty { null }?.let { decodeIcon(it) }
            val app: WebApp
            if (existing != null) {
                val base = planned[existing.id] ?: existing
                app = base.copy(
                    name = name, color = j.optInt("c", base.color), symbol = j.optString("y").ifEmpty { null },
                    keepScreenOn = j.optBoolean("s"), desktop = j.optBoolean("dk") && base.kind == KIND_WEB,
                    fullscreen = j.optBoolean("fs", true),
                    requireAuth = base.requireAuth || j.optBoolean("au", base.isAction),
                )
                if (app == base && img == null) continue
                if (existing.id !in planned && haveApps.any { it.id == existing.id }) changedNames += name
            } else {
                val srcTunnel = j.optString("t").ifEmpty { null }
                val tunnelName = j.optString("tn").ifEmpty { null }
                val tunnelId = srcTunnel?.let { idMap[it] }
                    ?: tunnelName?.let { n -> haveTunnels.firstOrNull { it.name.equals(n, ignoreCase = true) }?.id }
                app = WebApp(
                    name = name, url = url, tunnelId = tunnelId,
                    color = j.optInt("c", 0xFF1F242B.toInt()), keepScreenOn = j.optBoolean("s"),
                    desktop = j.optBoolean("dk") && kind == KIND_WEB, fullscreen = j.optBoolean("fs", true),
                    alwaysTunnel = tunnelId != null && j.optBoolean("at"),
                    trustedCert = ownPins(j.optString("k"), uri.host.orEmpty()), symbol = j.optString("y").ifEmpty { null },
                    kind = kind, requireAuth = j.optBoolean("au", kind == KIND_ACTION), action = action,
                )
                newNames += name
                if (srcTunnel != null && tunnelId == null) need += app.id
            }
            planned[app.id] = app
            img?.let { icons[app.id] = it }
        }
        return Plan(newTunnels, planned.values.toList(), icons, newNames, changedNames,
            newTunnels.map { "${it.name} (${it.endpoint.ifEmpty { "?" }})" }, skipped, need)
    }

    /** Geplanten Import in einem Schritt speichern (im Hintergrund aufrufen). */
    fun apply(ctx: Context, p: Plan): Result {
        val store = ctx.store
        store.saveTunnels(p.tunnels)
        store.saveApps(p.apps)
        p.icons.forEach { (id, bmp) -> runCatching { store.saveIcon(id, bmp) } }
        return Result(p.apps.size, p.tunnels.size, p.needTunnel)
    }

    /** Zertifikats-Pins aus dem Export nur fuer den eigenen Host der Web-App uebernehmen. */
    private fun ownPins(cert: String?, host: String): String? {
        val h = host.lowercase()
        if (h.isEmpty()) return null
        return cert.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.mapNotNull { e ->
            val (ph, fp) = if ('=' in e) (e.substringBefore('=') to e.substringAfter('=')) else (h to e)
            if (ph == h && SHA256.matches(fp)) "$h=$fp" else null
        }.distinct().joinToString(",").ifEmpty { null }
    }

    /** Bild aus dem Export: erst Groesse pruefen, dann verkleinert dekodieren (max. 432 px). */
    private fun decodeIcon(b64: String): Bitmap? = runCatching {
        if (b64.length > 400_000) return null
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096 || bounds.outHeight > 4096) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 432) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (maxOf(bmp.width, bmp.height) <= 432) bmp
        else {
            val k = 432f / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        }
    }.getOrNull()

    /** QR-Code als Bitmap (schwarz auf weiss, Fehlerkorrektur M). */
    fun qr(text: String, px: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
        val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    /** QR-Code aus einem Bild lesen (z. B. Screenshot der FRITZ!Box-Seite); null = keiner gefunden. */
    fun qrFromImage(ctx: Context, uri: android.net.Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        val src = RGBLuminanceSource(bmp.width, bmp.height, px)
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        listOf(HybridBinarizer(src), GlobalHistogramBinarizer(src)).firstNotNullOfOrNull { bin ->
            runCatching { MultiFormatReader().decode(BinaryBitmap(bin), hints).text }.getOrNull()
        }
    }.getOrNull()

    /**
     * Export-Dateien (enthalten Tunnel-Schluessel im Klartext) nicht im Cache liegen lassen:
     * beim Start alles loeschen, was aelter als 10 min ist (die Ziel-App hat es bis dahin gelesen).
     */
    fun cleanupExports(ctx: Context) {
        val old = System.currentTimeMillis() - 10 * 60_000L
        File(ctx.cacheDir, "share").listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
    }

    /**
     * Datei zum Senden ueber das Android-Teilen-Menue (Messenger, Mail, Nearby Share) anlegen.
     * Der Aufrufer startet das Intent und loescht die Datei, sobald das Teilen zurueckkehrt.
     */
    fun shareFile(ctx: Context, payload: String): Pair<Intent, File> {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, ctx.getString(R.string.share_file_name) + ".homehyrax").apply { writeText(payload) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.share_subject))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, ctx.getString(R.string.share_chooser)) to f
    }

    private fun pngBase64(b: Bitmap): String {
        val small = Bitmap.createScaledBitmap(b, 160, 160, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true).apply { setInput(data); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater(true).apply { setInput(data) }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            if (out.size() > 4_000_000) throw IllegalArgumentException("payload too large")   // Meldung sieht der Nutzer nicht
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }
}

/** Hoechstens n Bytes lesen (statt erst die ganze, evtl. riesige Datei in den Speicher). */
fun java.io.InputStream.readAtMost(n: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var left = n
    while (left > 0) {
        val r = read(buf, 0, minOf(buf.size, left))
        if (r < 0) break
        out.write(buf, 0, r)
        left -= r
    }
    return out.toByteArray()
}
