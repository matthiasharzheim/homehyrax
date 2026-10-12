package de.camperflower.homehyrax

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Gastzugang: Eintraege an einen Gast weitergeben, ohne dass er je den WireGuard-Schluessel sieht,
 * und den Zugang jederzeit wieder entziehen.
 *
 * Besitzer: create() legt beim Schluesseldienst eine Einladung an (Zufallsschluessel K) und verschluesselt
 * damit die Eintraege samt Verbindung. Die Einladung ("HG1:...") enthaelt nur id, Bindungs-Geheimnis und
 * den verschluesselten Inhalt.
 * Gast: accept() bindet die Einladung an einen Geraeteschluessel im Android-Keystore, holt K und speichert
 * die Eintraege; die Verbindung bleibt mit K verschluesselt. Bei jedem Verbinden holt unlock() K neu -
 * hat der Besitzer gesperrt, gibt es keinen Schluessel mehr. Protokoll: keyserver/README.md
 */
object Guest {
    private const val PREFIX = "HG1:"
    private const val SIGNPOST = "https://homehyrax.com/keys.json"
    private const val DEFAULT_SERVER = "https://camperflower.de/keys/v1/"
    /** Nur diese Hosts darf der Wegweiser nennen (ein manipulierter Wegweiser lenkt sonst auf einen fremden Dienst) */
    private val ALLOWED_HOSTS = setOf("camperflower.de", "homehyrax.com")
    private const val SIGNPOST_MAX_AGE_MS = 24 * 3600_000L
    /** nach einem Fehler den Wegweiser hoechstens so oft neu lesen */
    private const val SIGNPOST_RECHECK_MS = 5 * 60_000L
    private const val SIGNPOST_OFF_MAX_AGE_MS = 3600_000L
    /** Vollzugriff-Einladung: muss innerhalb dieser Zeit geoeffnet werden, danach ist die Datei wertlos */
    const val FULL_VALID_S = 7 * 86400L

    fun isInvite(text: String?) = text?.trim()?.startsWith(PREFIX) == true

    /** Zustand fuer die Seite "Ueber": "ok", "off" (abgeschaltet) oder "down" (nicht erreichbar). Netzwerk -> Hintergrund */
    fun health(ctx: Context): Pair<String, String> {
        val base = try { server(ctx) } catch (e: Failure) { return "off" to SIGNPOST }
        val ok = runCatching {
            val c = URL(base + "health").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = false
            try { c.responseCode == 200 && JSONObject(c.inputStream.use { it.readAtMost(4096).decodeToString() }).optBoolean("ok") }
            finally { c.disconnect() }
        }.getOrDefault(false)
        return (if (ok) "ok" else "down") to base
    }

    /**
     * Schluesseldienst abgeschaltet: Vollzugriff dann ohne ihn anbieten (Rueckfallebene). Nur bei ausdruecklichem
     * "service_off" - ein blosser Netzfehler (z. B. per DNS-Sperre erzwingbar) bietet keinen dauerhaften Export an.
     */
    fun unavailable(e: Throwable) = (e as? Failure)?.code == "service_off"

    /** Fehler vom Schluesseldienst (code wie in keyserver/README.md) oder "network" */
    class Failure(val code: String) : Exception(code)

    /** Meldung fuer den Nutzer */
    fun message(ctx: Context, e: Throwable): String = ctx.getString(when ((e as? Failure)?.code) {
        "blocked" -> R.string.guest_err_blocked
        "expired" -> R.string.guest_err_expired
        "unknown" -> R.string.guest_err_unknown
        "already_bound" -> R.string.guest_err_other_device
        "not_bound", "bad_signature", "device" -> R.string.guest_err_device
        "denied" -> R.string.guest_err_format
        "rate_limit" -> R.string.guest_err_busy
        "network" -> R.string.guest_err_network
        "integrity", "integrity_required" -> R.string.guest_err_integrity
        "service_off" -> R.string.guest_err_service_off
        "imported" -> R.string.guest_err_imported
        "format" -> R.string.guest_err_format
        else -> R.string.guest_err_server
    })

    // ================================================================ Besitzer

    /**
     * Einladung anlegen. apps = freigegebene Eintraege, tunnels = deren Verbindungen. expires = Unix-Sekunden.
     * full = Vollzugriff: der Empfaenger bekommt alles im Klartext (nicht entziehbar), die Einladung selbst
     * gilt aber nur einmal (gebunden an das erste Handy) und hoechstens FULL_VALID_S - eine Datei, die spaeter
     * in einem Chat-Backup oder durch Weiterleiten auftaucht, ist wertlos.
     * validSeconds = Gueltigkeit ab jetzt (null = unbegrenzt; Vollzugriff immer FULL_VALID_S).
     * Liefert zwei Fassungen derselben Einladung: ohne eigene Bilder (passt in einen QR-Code) und mit (Datei).
     */
    fun create(ctx: Context, label: String, apps: List<WebApp>, tunnels: List<Tunnel>, validSeconds: Long?, full: Boolean): Pair<String, String> {
        fun inner(icons: Boolean) = JSONObject().put("mode", if (full) "full" else "guest")
            .put("data", Share.export(ctx, apps, tunnels, withIcons = icons)).toString()
        val valid = if (full) FULL_VALID_S else validSeconds
        val body = JSONObject().put("owner", b64u(ctx.store.ownerToken()))
        // Ablauf relativ zur Server-Uhr (call rechnet ihn mit dem korrigierten Zeitstempel aus)
        if (valid != null) body.put("expiresIn", valid) else body.put("expires", JSONObject.NULL)
        val r = call(ctx, "create", body, sign = null)
        val id = r.getString("id")
        val key = unb64u(r.getString("key"))
        try {
            ctx.store.saveInvite(Invite(id, label, apps.joinToString { it.name }, System.currentTimeMillis(),
                if (r.isNull("expires")) null else r.getLong("expires"), full))
            fun text(icons: Boolean) = PREFIX + b64u(JSONObject().put("v", 1).put("id", id).put("b", r.getString("bind"))
                .put("ct", b64u(seal(key, id, inner(icons).toByteArray()))).toString().toByteArray())
            return text(false) to text(true)
        } finally {
            key.fill(0)
        }
    }

    /** Zustand einer Einladung beim Schluesseldienst */
    data class State(val id: String, val status: String, val bound: Boolean, val device: String?,
                     val lastUsed: Long?, val expires: Long?)

    /** Zustand aller eigenen Einladungen (id -> Zustand); fehlt eine, gibt es sie dort nicht mehr. */
    fun states(ctx: Context): Map<String, State> {
        val r = call(ctx, "info", JSONObject().put("owner", b64u(ctx.store.ownerToken())), sign = null)
        val arr = r.getJSONArray("invites")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.associate { o ->
            o.getString("id") to State(o.getString("id"), o.getString("status"), o.optBoolean("bound"),
                o.optString("device").takeIf { !o.isNull("device") && it.isNotEmpty() },
                if (o.isNull("last_used")) null else o.getLong("last_used"),
                if (o.isNull("expires")) null else o.getLong("expires"))
        }
    }

    /** Sperren (true) oder wieder freigeben (false). */
    fun setBlocked(ctx: Context, id: String, blocked: Boolean) {
        call(ctx, "manage", JSONObject().put("owner", b64u(ctx.store.ownerToken())).put("id", id)
            .put("action", if (blocked) "block" else "activate"), sign = null)
    }

    /** Gueltigkeit aendern (Unix-Sekunden, null = unbegrenzt); verlaengert auch eine abgelaufene Einladung. */
    fun setExpires(ctx: Context, id: String, expires: Long?) {
        call(ctx, "manage", JSONObject().put("owner", b64u(ctx.store.ownerToken())).put("id", id)
            .put("action", "expires").put("expires", expires ?: JSONObject.NULL), sign = null)
        ctx.store.invites().firstOrNull { it.id == id }?.let { ctx.store.saveInvite(it.copy(expires = expires)) }
    }

    /** Einladung beim Schluesseldienst und hier loeschen (dort schon weg = auch gut). */
    fun delete(ctx: Context, id: String) {
        try {
            call(ctx, "manage", JSONObject().put("owner", b64u(ctx.store.ownerToken())).put("id", id)
                .put("action", "delete"), sign = null)
        } catch (e: Failure) {
            if (e.code != "unknown") throw e
        }
        ctx.store.deleteInvite(id)
    }

    // ================================================================ Gast

    /** Geprueft und entschluesselt, noch nicht gespeichert: Vorschau fuer den Empfaenger. */
    class Accepted(val plan: Share.Plan, val device: String, val expires: Long?, val full: Boolean)

    /**
     * Einladung oeffnen: an dieses Geraet binden, Schluessel holen, Inhalt entschluesseln und als
     * Import vorbereiten. Gespeichert wird erst mit Share.apply (nach Bestaetigung). Netzwerk -> Hintergrund.
     */
    fun accept(ctx: Context, text: String): Accepted {
        val outer = runCatching {
            JSONObject(String(unb64u(text.trim().removePrefix(PREFIX))))
        }.getOrElse { throw Failure("format") }
        val id = outer.optString("id")
        if (!Regex("^[A-Za-z0-9_-]{22}$").matches(id)) throw Failure("format")
        if (ctx.store.tunnels().any { it.isGuest && runCatching { JSONObject(it.config).getString("inv") }.getOrNull() == id })
            throw Failure("imported")
        val pub = deviceKey(id)
        val device = try {
            call(ctx, "bind", JSONObject().put("bind", outer.getString("b")).put("pub", b64u(pub)), sign = id)
                .optString("device")
        } catch (e: Failure) {
            // dieselbe Einladung auf diesem Handy noch einmal geoeffnet: in Ordnung
            if (e.code != "bound_here") throw e
            fingerprint(pub)
        }
        val r = call(ctx, "key", JSONObject(), sign = id)
        val key = unb64u(r.getString("key"))
        try {
            val inner = runCatching { JSONObject(String(open(key, id, unb64u(outer.getString("ct"))))) }
                .getOrElse { throw Failure("format") }
            if (inner.optString("mode") == "full") {
                // Vollzugriff: normaler Import. Geraeteschluessel bleibt, damit "Abbrechen" und erneutes Oeffnen
                // auf diesem Handy weiter geht (Server meldet bound_here); anderen Handys hilft die Datei nicht mehr
                return Accepted(Share.plan(ctx, inner.getString("data")), device.ifEmpty { fingerprint(pub) }, null, full = true)
            }
            // eigene Gast-Eintraege frueherer Einladungen nicht als "vorhanden" werten: neue Einladung desselben
            // Besitzers (z. B. nach Ablauf) ersetzt sie, statt an der alten Einladung haengen zu bleiben
            val plan = Share.plan(ctx, inner.getString("data"), ignoreGuest = true)
            val known = ctx.store.apps().filter { !it.isGuest }.map { it.id }.toSet()
            // Verbindungen bleiben mit K verschluesselt; Tailscale wird (noch) nicht geteilt
            val tunnels = plan.tunnels.filter { it.type == TUNNEL_WG }.map { t ->
                Tunnel(t.id, t.name, JSONObject().put("inv", id).put("ct", b64u(seal(key, id, t.config.toByteArray()))).toString(),
                    TUNNEL_GUEST)
            }
            val tunnelIds = tunnels.map { it.id }.toSet()
            // nur neue Eintraege mit Gast-Verbindung; vorhandene eigene Kacheln bleiben unberuehrt
            // mit Gast-Verbindung (sperrbar) oder ganz ohne Tunnel (geht nur im Netzwerk des Besitzers)
            // (ohne Tunnel nur, was der Besitzer so geteilt hat - nicht WireGuard-Eintraege, deren Verbindung hier nicht ging)
            val fresh = plan.apps.filter { it.id !in known && (it.tunnelId in tunnelIds || (it.tunnelId == null && it.id !in plan.needTunnel)) }
            // Gast-Eintrag mit gleicher Adresse, dessen Einladung nicht mehr gilt (abgelaufen, gesperrt, geloescht):
            // der neue Eintrag uebernimmt seine id (Startbildschirm-Symbol bleibt). Noch gueltige - z. B. von einem
            // anderen Besitzer mit gleicher Adresse - bleiben unberuehrt
            val dead = HashMap<String, WebApp>()
            val tunnelsNow = ctx.store.tunnels()
            ctx.store.apps().filter { it.isGuest && fresh.any { f -> f.url.equals(it.url, ignoreCase = true) } }.forEach { old ->
                val t = tunnelsNow.firstOrNull { it.id == old.tunnelId }
                val gone = t == null || runCatching { unlock(ctx, t) }.exceptionOrNull().let {
                    it is Failure && it.code in setOf("blocked", "expired", "unknown", "not_bound", "bad_signature", "device")
                }
                if (gone) dead.putIfAbsent(old.url.lowercase(), old)
            }
            val idFor = fresh.associate { it.id to (dead[it.url.lowercase()]?.id ?: it.id) }
            // Shelly-/Kamera-Passwoerter mit K versiegeln: beim Gast nur bei Nutzung entschluesselt (sealedSecret),
            // Sperren/Ablauf wirken so auch auf Schalter und Kameras im WLAN des Besitzers
            val apps = fresh.map { a ->
                val act = a.action?.let { if (it.pass.isEmpty()) it else it.copy(pass = SEALED + b64u(seal(key, id, it.pass.toByteArray()))) }
                a.copy(id = idFor.getValue(a.id), guest = id, alwaysTunnel = a.tunnelId != null, action = act)
            }
            val icons = plan.icons.filterKeys { it in idFor }.mapKeys { idFor.getValue(it.key) }
            val used = tunnels.filter { t -> apps.any { it.tunnelId == t.id } }
            val guestPlan = Share.Plan(used, apps, icons, apps.map { it.name }, emptyList(), emptyList(),
                plan.skipped + plan.apps.filter { a -> fresh.none { it.id == a.id } }.map { it.name }, emptyList())
            return Accepted(guestPlan, device.ifEmpty { fingerprint(pub) },
                if (r.isNull("expires")) null else r.getLong("expires"), full = false)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Gast-Verbindung zum Verbinden entschluesseln: holt den Schluessel bei jedem Aufruf neu.
     * Der WireGuard-Text lebt nur im zurueckgegebenen Objekt (RAM). Netzwerk -> Hintergrund.
     */
    fun unlock(ctx: Context, t: Tunnel): Tunnel {
        val o = JSONObject(t.config)
        val id = o.getString("inv")
        val r = call(ctx, "key", JSONObject(), sign = id)
        val key = unb64u(r.getString("key"))
        try {
            return Tunnel(t.id, t.name, String(open(key, id, unb64u(o.getString("ct")))), TUNNEL_WG)
        } finally {
            key.fill(0)
        }
    }

    /** Kennzeichen eines mit K versiegelten Passworts (Steuerzeichen: kommt in echten Passwoertern nicht vor) */
    private const val SEALED = "\u0001HG1:"

    /**
     * Gast-Eintrag mit versiegeltem Passwort: Schluessel holen und das Passwort nur fuer diese Nutzung (RAM)
     * entschluesseln. Andere Eintraege kommen unveraendert zurueck. Netzwerk -> Hintergrund.
     */
    fun withSecrets(ctx: Context, app: WebApp): WebApp {
        val a = app.action ?: return app
        val inv = app.guest ?: return app
        if (!a.pass.startsWith(SEALED)) return app
        val r = call(ctx, "key", JSONObject(), sign = inv)
        val key = unb64u(r.getString("key"))
        try {
            return app.copy(action = a.copy(pass = String(open(key, inv, unb64u(a.pass.removePrefix(SEALED))))))
        } finally {
            key.fill(0)
        }
    }

    /** Geraeteschluessel einer geloeschten Gast-Verbindung entfernen. */
    fun forgetDevice(t: Tunnel) {
        runCatching { forgetInvite(ctxFree = null, JSONObject(t.config).getString("inv")) }
    }

    /** Geraeteschluessel (und gemerkten Zustand) einer Einladung entfernen. */
    fun forgetInvite(ctxFree: Context?, inviteId: String) {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias(inviteId)) }
        ctxFree?.getSharedPreferences("guest", Context.MODE_PRIVATE)?.edit()?.remove("state_$inviteId")?.apply()
    }

    /**
     * Zuletzt festgestellter Zustand eines Gastzugangs fuer die Kachel: "blocked", "expired", "gone" oder null (gilt
     * bzw. unbekannt). Wird bei jeder Schluessel-Abfrage aktualisiert (Antippen, Pruefung bei offener Kachel).
     */
    fun state(ctx: Context, inviteId: String): String? =
        ctx.getSharedPreferences("guest", Context.MODE_PRIVATE).getString("state_$inviteId", null)

    private fun rememberState(ctx: Context, inviteId: String, code: String?) {
        val s = when (code) {
            null -> null
            "blocked" -> "blocked"
            "expired" -> "expired"
            "unknown", "service_off", "not_bound", "bad_signature" -> "gone"
            else -> return   // Netzfehler, Bremse o. ae.: Zustand unveraendert lassen
        }
        val prefs = ctx.getSharedPreferences("guest", Context.MODE_PRIVATE)
        if (prefs.getString("state_$inviteId", null) == s) return
        prefs.edit().apply { if (s == null) remove("state_$inviteId") else putString("state_$inviteId", s) }.apply()
    }

    // ================================================================ Schluesseldienst

    private fun alias(inviteId: String) = "homehyrax-guest-$inviteId"

    /** Oeffentlicher Teil des Geraeteschluessels (X.509, EC P-256); wird beim ersten Mal im Keystore erzeugt. */
    private fun deviceKey(inviteId: String): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getCertificate(alias(inviteId))?.let { return it.publicKey.encoded }
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(KeyGenParameterSpec.Builder(alias(inviteId), KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build())
        return gen.generateKeyPair().public.encoded
    }

    private fun signWith(inviteId: String, msg: String): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val priv = ks.getKey(alias(inviteId), null) as? PrivateKey ?: throw Failure("device")
        return Signature.getInstance("SHA256withECDSA").run { initSign(priv); update(msg.toByteArray()); sign() }
    }

    /** Abweichung der Handy-Uhr zur Server-Uhr in Sekunden (nach "clock" vom Server gesetzt) */
    @Volatile private var clockOffset = 0L

    /**
     * Anfrage an den Schluesseldienst. sign = Einladungs-id: mit Zeitstempel, Einmalwert und Signatur des
     * Geraeteschluessels; null = Besitzer-Anfrage (create bekommt nur Zeitstempel und Einmalwert).
     */
    private fun call(ctx: Context, action: String, body: JSONObject, sign: String?, retry: Boolean = true,
                     integrity: Boolean = false): JSONObject {
        val ts = System.currentTimeMillis() / 1000 + clockOffset
        val nonce = b64u(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val req = JSONObject(body.toString())
        if (sign != null || action == "create") req.put("ts", ts).put("nonce", nonce)
        if (req.has("expiresIn")) req.put("expires", ts + req.getLong("expiresIn")).remove("expiresIn")
        if (sign != null) req.put("id", sign).put("sig", b64u(signWith(sign, "HH1|$action|$sign|$ts|$nonce")))
        if (integrity) {
            // Nachweis gilt nur fuer genau diese Anfrage (requestHash ueber denselben Text wie beim Server)
            val subject = sign ?: sha256Hex(unb64u(body.getString("owner")))
            req.put("integrity", integrityToken(ctx, requestHash("$action|$subject|$ts|$nonce")))
        }
        val (status, res) = try {
            post(server(ctx) + action, req.toString())
        } catch (e: IOException) {
            server(ctx, force = true)   // abgeschaltet? dann service_off statt "nicht erreichbar"
            throw Failure("network")
        }
        if (status == 200 && action == "key" && sign != null) rememberState(ctx, sign, null)
        val o = runCatching { JSONObject(res) }.getOrElse {
            server(ctx, force = true)   // Dienst entfernt (z. B. 404-Seite des Hosters)?
            throw Failure("server")
        }
        if (status == 200) return o
        val code = o.optString("error", "server")
        if (code == "clock" && retry && o.has("now")) {
            clockOffset = o.getLong("now") - System.currentTimeMillis() / 1000
            return call(ctx, action, body, sign, retry = false, integrity = integrity)
        }
        // Schluesseldienst verlangt die Echtheitspruefung (nur bei create/bind/key, hoechstens einmal am Tag je Geraet)
        if (code == "integrity_required" && !integrity) return call(ctx, action, body, sign, retry, integrity = true)
        if (action == "key" && sign != null) rememberState(ctx, sign, code)
        throw Failure(code)
    }

    private fun post(url: String, body: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.doOutput = true
            c.instanceFollowRedirects = false   // Umleitungen verlieren POST und koennten woandershin fuehren
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to (stream?.use { it.readAtMost(64_000).decodeToString() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    /**
     * Adresse des Schluesseldienstes aus dem Wegweiser (24 h gemerkt; nicht erreichbar -> letzter bzw. Standard).
     * Rueckfallebene: steht im Wegweiser "status": "off", ist der Dienst abgeschaltet -> Failure("service_off");
     * Gaeste bekommen dann eine klare Meldung, Vollzugriff laesst sich weiter ohne Dienst teilen (Share.export).
     * force = Wegweiser sofort neu lesen (nach einem Fehler: vielleicht wurde der Dienst gerade abgeschaltet).
     */
    private fun server(ctx: Context, force: Boolean = false): String {
        val prefs = ctx.getSharedPreferences("guest", Context.MODE_PRIVATE)
        val cached = prefs.getString("server", null)
        val off = prefs.getBoolean("server_off", false)
        val now = System.currentTimeMillis()
        val age = now - prefs.getLong("server_at", 0)
        // "aus" nur 1 h merken: ein zurueckgestellter Wegweiser wirkt so bald wieder
        if (!force && (cached != null || off) && age < (if (off) SIGNPOST_OFF_MAX_AGE_MS else SIGNPOST_MAX_AGE_MS)) {
            if (off) throw Failure("service_off")
            return cached!!
        }
        // nach Fehlern hoechstens alle 5 min nachsehen - auch wenn der Wegweiser selbst nicht erreichbar ist
        if (force && now - prefs.getLong("server_try", 0) < SIGNPOST_RECHECK_MS)
            return if (off) throw Failure("service_off") else cached ?: DEFAULT_SERVER
        prefs.edit().putLong("server_try", now).apply()
        val sign = runCatching {
            val c = URL(SIGNPOST).openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = false
            try {
                if (c.responseCode != 200) null else JSONObject(c.inputStream.use { it.readAtMost(4096).decodeToString() })
            } finally { c.disconnect() }
        }.getOrNull()
        if (sign != null) {
            val nowOff = sign.optString("status") == "off"
            val url = sign.optString("keys").takeIf { allowed(it) }
            if (nowOff || url != null) prefs.edit().putBoolean("server_off", nowOff).putString("server", url ?: cached)
                .putLong("server_at", System.currentTimeMillis()).apply()
            if (nowOff) throw Failure("service_off")
            if (url != null) return url
        }
        if (off) throw Failure("service_off")
        return cached ?: DEFAULT_SERVER
    }

    private fun allowed(url: String): Boolean {
        val u = runCatching { URL(url) }.getOrNull() ?: return false
        val h = u.host.lowercase()
        return u.protocol == "https" && u.path.endsWith("/") && h in ALLOWED_HOSTS   // nur exakt diese Hosts
    }

    // ================================================================ Echtheitspruefung (Google Play Integrity)

    private var integrityProvider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null
    private var integrityProject = 0L

    /** Cloud-Projektnummer meldet der Schluesseldienst (health) - so laesst sie sich ohne App-Update setzen */
    private fun cloudProject(ctx: Context): Long {
        val text = try {
            val c = URL(server(ctx) + "health").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = false
            try { c.inputStream.use { it.readAtMost(4096).decodeToString() } } finally { c.disconnect() }
        } catch (e: IOException) {
            throw Failure("network")   // nur das Netz weg - nicht "Geraet nicht zugelassen" melden
        }
        return runCatching { JSONObject(text).optString("project").toLongOrNull() }.getOrNull()?.takeIf { it > 0 }
            ?: throw Failure("server")   // Schluesseldienst falsch eingestellt (keine Projektnummer)
    }

    /**
     * Versiegelter Nachweis von Google: echte HomeHyrax aus dem Play Store auf einem echten Geraet, gebunden an hash.
     * Der Anbieter wird einmal vorbereitet und wiederverwendet; schlaegt eine Anfrage fehl, einmal neu vorbereiten.
     */
    @Synchronized
    private fun integrityToken(ctx: Context, hash: String): String {
        val project = cloudProject(ctx)
        fun prepare() = Tasks.await(IntegrityManagerFactory.createStandard(ctx.applicationContext).prepareIntegrityToken(
            StandardIntegrityManager.PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(project).build()), 30, TimeUnit.SECONDS)
            .also { integrityProvider = it; integrityProject = project }
        fun request(p: StandardIntegrityManager.StandardIntegrityTokenProvider) = Tasks.await(p.request(
            StandardIntegrityManager.StandardIntegrityTokenRequest.builder().setRequestHash(hash).build()), 30, TimeUnit.SECONDS).token()
        return try {
            val p = integrityProvider?.takeIf { integrityProject == project } ?: prepare()
            runCatching { request(p) }.getOrElse { request(prepare()) }
        } catch (e: Exception) {
            throw Failure("integrity")   // keine Play-Dienste, nicht aus dem Play Store installiert o. ae.
        }
    }

    /** requestHash wie beim Schluesseldienst: base64url(SHA-256("HH1|<nachricht>")) */
    private fun requestHash(msg: String) = b64u(MessageDigest.getInstance("SHA-256").digest("HH1|$msg".toByteArray()))

    private fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ================================================================ Krypto

    /** AES-256-GCM, Einladungs-id als zusaetzliche Daten (Inhalt einer anderen Einladung passt nicht). Ausgabe: IV + Text */
    private fun seal(key: ByteArray, id: String, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD("HH1|$id".toByteArray())
        return iv + c.doFinal(plain)
    }

    private fun open(key: ByteArray, id: String, data: ByteArray): ByteArray {
        if (data.size < 28) throw Failure("format")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        c.updateAAD("HH1|$id".toByteArray())
        return runCatching { c.doFinal(data, 12, data.size - 12) }.getOrElse { throw Failure("format") }
    }

    /** Kurzer Fingerabdruck des Geraeteschluessels wie beim Schluesseldienst (8 Hex-Zeichen) */
    private fun fingerprint(pub: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(pub).joinToString("") { "%02x".format(it) }.take(8)

    private fun b64u(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun unb64u(s: String): ByteArray =
        runCatching { Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }.getOrElse { throw Failure("format") }
}
