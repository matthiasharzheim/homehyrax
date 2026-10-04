package de.camperflower.homehyrax

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.camperflower.homehyrax.wgbridge.Wgbridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Speichern ging nicht (Keystore). Wird in der Oberflaeche als Hinweis gezeigt. */
class StoreException(msg: String, cause: Throwable?) : Exception(msg, cause)

/** Eine Web-App: eine lokale Adresse, optional mit WireGuard fuer unterwegs. */
data class WebApp(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val tunnelId: String? = null,
    val color: Int = 0xFF1F242B.toInt(),
    val keepScreenOn: Boolean = false,
    /** SHA-256 eines selbstsignierten Zertifikats, dem der Nutzer vertraut (NAS, Router). */
    val trustedCert: String? = null,
    /** Fertiges Symbol (Symbols.all), wenn kein eigenes Bild gesetzt ist. */
    val symbol: String? = null,
    /** "web" = Seite anzeigen, "action" = Schaltflaeche (eine Anfrage senden, z. B. Garagentor),
     *  "camera" = RTSP-Livebild (url = rtsp://host/pfad ohne Zugangsdaten, die stehen in action.user/pass) */
    val kind: String = KIND_WEB,
    val action: ShellyAction? = null,
    /** vor dem Oeffnen/Ausloesen Fingerabdruck/Gesicht/PIN verlangen (Web-App: optional, Schaltflaeche: Standard) */
    val requireAuth: Boolean = false,
    /** Web-App als Desktop-Webseite laden (Desktop-User-Agent, breite Ansicht) */
    val desktop: Boolean = false,
    /** Vollbild (Statusleiste ausgeblendet); aus = Statusleiste bleibt sichtbar */
    val fullscreen: Boolean = true,
    /** "Immer VPN": auch zuhause ueber den Tunnel, nie direkt (nur mit tunnelId). Aus = "Smart VPN". */
    val alwaysTunnel: Boolean = false,
) {
    val isAction: Boolean get() = kind == KIND_ACTION
    val isCamera: Boolean get() = kind == KIND_CAMERA
    val host: String get() = Uri.parse(url).host.orEmpty()
    val port: Int get() = Uri.parse(url).let {
        if (it.port > 0) it.port else when (it.scheme) { "https" -> 443; "rtsp" -> 554; else -> 80 }
    }
}

const val KIND_WEB = "web"
const val KIND_ACTION = "action"
const val KIND_CAMERA = "camera"

/**
 * Schaltflaeche fuer einen Shelly (oder beliebige URL).
 * gen: 0 = eigene URL (steht in WebApp.url), 1 = Shelly Gen1, 2 = Shelly Gen2/Plus/Pro
 * mode: 0 = Impuls (secs), 1 = Ein, 2 = Aus, 3 = Umschalten
 */
data class ShellyAction(
    val gen: Int = 2,
    val ip: String = "",
    val channel: Int = 0,
    val mode: Int = 0,
    val secs: Int = 1,
    val user: String = "admin",
    val pass: String = "",
    /** Anzeigename des gefundenen Geraets, z. B. "Garage - Shelly Plus1" */
    val device: String = "",
) {
    fun url(customUrl: String): String {
        val base = "http://${ip.trim()}"
        return when (gen) {
            1 -> when (mode) {
                0 -> "$base/relay/$channel?turn=on&timer=$secs"
                1 -> "$base/relay/$channel?turn=on"
                2 -> "$base/relay/$channel?turn=off"
                else -> "$base/relay/$channel?turn=toggle"
            }
            2 -> when (mode) {
                0 -> "$base/rpc/Switch.Set?id=$channel&on=true&toggle_after=$secs"
                1 -> "$base/rpc/Switch.Set?id=$channel&on=true"
                2 -> "$base/rpc/Switch.Set?id=$channel&on=false"
                else -> "$base/rpc/Switch.Toggle?id=$channel"
            }
            else -> customUrl
        }
    }

    /** Ohne Passwort (landet sonst in Logs/Absturzberichten). */
    override fun toString() = "ShellyAction(gen=$gen, ip=$ip, channel=$channel, mode=$mode, secs=$secs, user=$user, device=$device)"

    fun toJson(withPass: String?): JSONObject = JSONObject()
        .put("g", gen).put("ip", ip).put("ch", channel).put("m", mode).put("s", secs)
        .put("u", user).put("p", withPass ?: "").put("d", device)

    companion object {
        fun fromJson(o: JSONObject?, pass: (String) -> String): ShellyAction? = o?.let {
            ShellyAction(it.optInt("g", 2), it.optString("ip"), it.optInt("ch"), it.optInt("m"),
                it.optInt("s", 1), it.optString("u", "admin"), pass(it.optString("p")), it.optString("d"))
        }
    }
}

/**
 * Eine Verbindung fuer unterwegs (Inhalt im Klartext nur im RAM).
 * type "wg": config = wg-quick-Text. type "ts": config = JSON {"control","authkey"} (Tailscale/Headscale).
 */
data class Tunnel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val config: String,
    val type: String = TUNNEL_WG,
) {
    val isTailscale: Boolean get() = type == TUNNEL_TS

    /** Ohne Konfiguration (enthaelt den privaten Schluessel bzw. Auth-Key). */
    override fun toString() = "Tunnel(id=$id, name=$name, type=$type)"

    /** "xyz.myfritz.net:51820" bzw. "Tailscale" / Headscale-Host fuer die Anzeige */
    val endpoint: String
        get() = if (isTailscale) {
            runCatching { JSONObject(config).optString("control") }.getOrNull()?.takeIf { it.isNotBlank() }
                ?.let { "Tailscale · " + (Uri.parse(it).host ?: it) } ?: "Tailscale"
        } else config.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("Endpoint", ignoreCase = true) }
            ?.substringAfter('=')?.trim().orEmpty()
}

const val TUNNEL_WG = "wg"
const val TUNNEL_TS = "ts"

/**
 * Ablage im privaten App-Speicher. WireGuard-Konfigurationen sind mit einem
 * Schluessel aus dem Android-Keystore verschluesselt (AES-GCM, nicht
 * exportierbar); Cloud-Backup ist im Manifest abgeschaltet.
 */
class Store(private val ctx: Context) {
    private val appsFile = File(ctx.filesDir, "apps.json")
    private val tunnelsFile = File(ctx.filesDir, "tunnels.json")
    private val iconDir = File(ctx.filesDir, "icons").apply { mkdirs() }

    /**
     * Geladene Liste. broken = Eintraege, die sich nicht lesen/entschluesseln lassen; sie werden
     * beim naechsten Schreiben unveraendert wieder mitgeschrieben statt still verloren zu gehen.
     */
    private class Loaded<T>(val items: List<T>, val broken: List<JSONObject>)

    private fun <T> load(f: File, parse: (JSONObject) -> T): Loaded<T> {
        if (!f.exists()) return Loaded(emptyList(), emptyList())
        val arr = runCatching { JSONArray(f.readText()) }.getOrElse {
            // ganze Datei unlesbar: beiseitelegen, bevor sie beim naechsten Speichern ueberschrieben wird
            runCatching { f.copyTo(File(f.path + ".bad"), overwrite = true) }
            return Loaded(emptyList(), emptyList())
        }
        val items = ArrayList<T>()
        val broken = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching { parse(o) }.onSuccess { items += it }.onFailure { broken += o }
        }
        return Loaded(items, broken)
    }

    // ---- Web-Apps ----
    fun apps(): List<WebApp> = loadApps().items

    /**
     * Web-Apps samt Passwoertern, die sich nicht entschluesseln lassen (id -> verschluesselter Wert).
     * Die werden beim Schreiben unveraendert behalten, solange kein neues Passwort eingegeben wird.
     */
    private class LoadedApps(val items: List<WebApp>, val broken: List<JSONObject>, val lockedPass: Map<String, String>)

    private fun loadApps(): LoadedApps {
        val locked = HashMap<String, String>()
        val l = load(appsFile) { o -> parseApp(o, locked) }
        return LoadedApps(l.items, l.broken, locked)
    }

    private fun parseApp(o: JSONObject, locked: MutableMap<String, String>) = run {
        val id = o.getString("id")
        WebApp(
            id = id,
            name = o.getString("name"),
            url = o.getString("url"),
            tunnelId = o.optString("tunnel").ifEmpty { null },
            color = o.optInt("color", 0xFF1F242B.toInt()),
            keepScreenOn = o.optBoolean("screenOn"),
            desktop = o.optBoolean("desk"),
            fullscreen = o.optBoolean("full", true),
            alwaysTunnel = o.optBoolean("always"),
            trustedCert = o.optString("cert").ifEmpty { null },
            symbol = o.optString("sym").ifEmpty { null },
            kind = o.optString("kind", KIND_WEB),
            action = ShellyAction.fromJson(o.optJSONObject("act")) { p ->
                if (p.isEmpty()) "" else runCatching { decrypt(p) }.getOrElse { locked[id] = p; "" }
            },
            // Sperre: Schaltflaechen "auth", Web-Apps/Kameras "wlock" - getrennte Schluessel, weil
            // aeltere Datenstaende "auth" auch bei Web-Apps enthalten koennen
            requireAuth = if (o.optString("kind", KIND_WEB) == KIND_ACTION) o.optBoolean("auth", true) else o.optBoolean("wlock", false),
        )
    }

    fun app(id: String) = apps().firstOrNull { it.id == id }

    /** Speichern ohne Umsortieren: vorhandener Eintrag bleibt an seiner Stelle, neue kommen ans Ende. */
    fun saveApp(app: WebApp) {
        val l = loadApps()
        val list = l.items
        writeApps(if (list.any { it.id == app.id }) list.map { if (it.id == app.id) app else it } else list + app, l)
    }

    /** Mehrere Eintraege in einem Schreibvorgang speichern (Import): vorhandene ersetzen, neue ans Ende. */
    fun saveApps(apps: List<WebApp>) {
        if (apps.isEmpty()) return
        val l = loadApps()
        val byId = apps.associateBy { it.id }
        val known = l.items.map { it.id }.toSet()
        writeApps(l.items.map { byId[it.id] ?: it } + apps.filter { it.id !in known }, l)
    }

    /** Reihenfolge der Kacheln (Drag & Drop) uebernehmen. */
    fun setOrder(ids: List<String>) {
        val l = loadApps()
        val pos = ids.withIndex().associate { it.value to it.index }
        writeApps(l.items.sortedBy { pos[it.id] ?: Int.MAX_VALUE }, l)
    }

    fun deleteApp(id: String) {
        val l = loadApps()
        writeApps(l.items.filter { it.id != id }, l, dropBroken = id)
        iconFile(id).delete()
        snapshotFile(id).delete()
    }

    // ---- Kamera: letztes Bild fuer die Kachel (nur lokal, wird nicht geteilt) ----
    private val snapDir = File(ctx.filesDir, "snaps")

    fun snapshotFile(appId: String) = File(snapDir, "$appId.jpg")

    fun saveSnapshot(appId: String, bmp: Bitmap) {
        snapDir.mkdirs()
        val f = snapshotFile(appId)
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }

    /** Letztes Kamerabild + Zeitpunkt (ms), null = noch keins. */
    fun snapshot(appId: String): Pair<Bitmap, Long>? = snapshotFile(appId).takeIf { it.exists() }?.let { f ->
        BitmapFactory.decodeFile(f.path)?.let { it to f.lastModified() }
    }

    private fun writeApps(list: List<WebApp>, loaded: LoadedApps, dropBroken: String? = null) {
        val broken = loaded.broken.filter { dropBroken == null || it.optString("id") != dropBroken }
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("url", it.url)
                put("tunnel", it.tunnelId ?: ""); put("color", it.color)
                put("screenOn", it.keepScreenOn); put("cert", it.trustedCert ?: "")
                put("desk", it.desktop); put("full", it.fullscreen); put("always", it.alwaysTunnel)
                put("sym", it.symbol ?: "")
                put("kind", it.kind)
                if (it.isAction) put("auth", it.requireAuth) else put("wlock", it.requireAuth)
                it.action?.let { a ->
                    // leeres Passwort + alter, nicht entschluesselbarer Wert -> alten Wert behalten
                    val enc = if (a.pass.isNotEmpty()) encrypt(a.pass) else loaded.lockedPass[it.id].orEmpty()
                    put("act", a.toJson(enc))
                }
            })
        }
        broken.forEach { arr.put(it) }
        atomicWrite(appsFile, arr.toString())
    }

    // ---- Icons ----
    fun iconFile(appId: String) = File(iconDir, "$appId.png")

    fun icon(appId: String): Bitmap? =
        iconFile(appId).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun saveIcon(appId: String, bmp: Bitmap?) {
        val f = iconFile(appId)
        if (bmp == null) { f.delete(); return }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- WireGuard-Verbindungen ----
    fun tunnels(): List<Tunnel> = loadTunnels().items

    private fun loadTunnels() = load(tunnelsFile) { o ->
        Tunnel(o.getString("id"), o.getString("name"), decrypt(o.getString("cfg")), o.optString("type", TUNNEL_WG))
    }

    fun tunnel(id: String?) = id?.let { t -> tunnels().firstOrNull { it.id == t } }

    fun saveTunnel(t: Tunnel) {
        val l = loadTunnels()
        val old = l.items
        writeTunnels(if (old.any { it.id == t.id }) old.map { if (it.id == t.id) t else it } else old + t,
            l.broken.filter { it.optString("id") != t.id })
    }

    /** Mehrere Verbindungen in einem Schreibvorgang speichern (Import). */
    fun saveTunnels(ts: List<Tunnel>) {
        if (ts.isEmpty()) return
        val l = loadTunnels()
        val byId = ts.associateBy { it.id }
        val known = l.items.map { it.id }.toSet()
        writeTunnels(l.items.map { byId[it.id] ?: it } + ts.filter { it.id !in known },
            l.broken.filter { it.optString("id") !in byId })
    }

    private fun writeTunnels(list: List<Tunnel>, broken: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(tunnelJson(it)) }
        broken.forEach { arr.put(it) }
        atomicWrite(tunnelsFile, arr.toString())
    }

    private fun tunnelJson(t: Tunnel) =
        JSONObject().put("id", t.id).put("name", t.name).put("cfg", encrypt(t.config)).put("type", t.type)

    /** Zustandsordner einer Tailscale-Verbindung (Geraeteschluessel, Anmeldung). */
    fun tailscaleDir(id: String) = File(ctx.filesDir, "ts/$id")

    fun deleteTunnel(id: String) {
        val l = loadTunnels()
        writeTunnels(l.items.filter { it.id != id }, l.broken.filter { it.optString("id") != id })
        apps().filter { it.tunnelId == id }.forEach { saveApp(it.copy(tunnelId = null)) }
        // Go stoppt den Tunnel (wartet einen laufenden Tailscale-Start ab) und loescht erst dann den
        // Ordner, sonst schriebe der Knoten weiter hinein. Kann kurz blockieren -> nicht im Main-Thread
        val dir = tailscaleDir(id)
        Thread {
            runCatching { Wgbridge.removeTailscale(id, dir.path) }.onFailure { dir.deleteRecursively() }
        }.start()
    }

    /** Erst in .tmp schreiben, alten Stand als .bak behalten, dann ersetzen. */
    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (f.exists()) runCatching { f.copyTo(File(f.path + ".bak"), overwrite = true) }
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    // ---- Keystore ----
    /** Synchronisiert: zwei Threads duerfen den Schluessel nicht gleichzeitig anlegen. */
    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /**
     * Keystore-Fehler (z. B. voruebergehend nicht verfuegbar) einmal wiederholen, danach als
     * StoreException melden - der Aufrufer zeigt einen Hinweis statt abzustuerzen.
     */
    private fun encrypt(plain: String): String {
        var last: Exception? = null
        repeat(2) {
            try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, key())
                val out = c.iv + c.doFinal(plain.toByteArray())
                return Base64.encodeToString(out, Base64.NO_WRAP)
            } catch (e: Exception) {
                last = e
            }
        }
        throw StoreException("keystore", last)
    }

    private fun decrypt(enc: String): String {
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12))
    }

    companion object {
        private const val KEY_ALIAS = "homehyrax-configs"
    }
}

/**
 * Adress-Pruefungen ohne DNS-Anfrage: nur echte IP-Literale werden als IP gewertet
 * (ein Textvergleich wuerde z. B. "10.example.net" als privates Netz ansehen).
 */
object Hosts {
    /** IPv4-/IPv6-Literal als Bytes, sonst null (Namen werden nie aufgeloest). */
    fun ipBytes(host: String?): ByteArray? {
        val h = host?.trim()?.removePrefix("[")?.removeSuffix("]")?.substringBefore('%') ?: return null
        if (h.isEmpty()) return null
        val v4 = h.split('.')
        if (v4.size == 4 && v4.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 })
            return ByteArray(4) { v4[it].toInt().toByte() }
        if (':' !in h || !h.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) return null
        // reines Literal: InetAddress fragt hier kein DNS
        return runCatching { java.net.InetAddress.getByName(h).address }.getOrNull()
    }

    /** Loopback, "irgendeine Adresse" oder Link-Local: als Ziel nicht erlaubt (lokaler Proxy, andere Apps). */
    fun isForbidden(host: String?): Boolean {
        val h = host?.trim()?.lowercase()?.removeSuffix(".").orEmpty()
        if (h.isEmpty()) return true
        if (h == "localhost" || h.endsWith(".localhost")) return true
        val b = ipBytes(h) ?: return false
        val u = b.map { it.toInt() and 0xFF }
        if (b.size == 4) return u[0] == 127 || u[0] == 0 || (u[0] == 169 && u[1] == 254)
        val v4mapped = (0 until 10).all { u[it] == 0 } && u[10] == 0xFF && u[11] == 0xFF
        return when {
            u.all { it == 0 } -> true                                   // ::
            (0 until 15).all { u[it] == 0 } && u[15] == 1 -> true       // ::1
            u[0] == 0xFE && (u[1] and 0xC0) == 0x80 -> true             // fe80::/10
            v4mapped -> isForbidden("${u[12]}.${u[13]}.${u[14]}.${u[15]}")
            else -> false
        }
    }

    /** Heimnetz-Adresse: private IPv4/IPv6-Bereiche, CGNAT (Tailscale) oder eindeutig lokale Namen. */
    fun isPrivate(host: String?): Boolean {
        val h = host?.trim()?.lowercase()?.removeSuffix(".").orEmpty()
        if (h.isEmpty() || isForbidden(h)) return false
        val b = ipBytes(h) ?: return '.' !in h || LOCAL_SUFFIXES.any { h.endsWith(it) }   // "nas", "nas.local"
        val u = b.map { it.toInt() and 0xFF }
        if (b.size == 4) {
            return u[0] == 10 || (u[0] == 172 && u[1] in 16..31) || (u[0] == 192 && u[1] == 168) ||
                (u[0] == 100 && u[1] in 64..127)
        }
        return (u[0] and 0xFE) == 0xFC   // fc00::/7 (ULA)
    }

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".fritz.box")
}
