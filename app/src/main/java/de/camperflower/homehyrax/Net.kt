package de.camperflower.homehyrax

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Build
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Base64
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import de.camperflower.homehyrax.wgbridge.Wgbridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import kotlin.coroutines.resume

class HomeHyraxApp : Application() {
    lateinit var store: Store

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        CrashLog.install(this)
        Net.init(this)
    }
}

/**
 * Absturzbericht fuer den Nutzer: Kotlin-Ausnahmen (UncaughtExceptionHandler) und
 * Go-Abstuerze (debug.SetCrashOutput) landen in einer Datei und werden beim naechsten
 * Start angezeigt. Nichts wird hochgeladen.
 */
object CrashLog {
    private lateinit var file: java.io.File

    /** Bericht nicht endlos wachsen lassen (Go haengt bei jedem Absturz an). */
    private const val MAX_BYTES = 64_000L

    fun install(ctx: Context) {
        file = java.io.File(ctx.filesDir, "crash.txt")
        trim()
        runCatching { Wgbridge.setCrashFile(file.path) }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                trim()
                file.appendText("${java.util.Date()} · HomeHyrax ${BuildConfig.VERSION_NAME} · Thread ${t.name}\n" +
                    android.util.Log.getStackTraceString(e) + "\n")
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** Bericht vom letzten Absturz (leer = keiner). */
    fun read(): String = runCatching { if (file.exists()) file.readText().takeLast(12_000) else "" }.getOrDefault("")

    fun clear() { runCatching { file.writeText("") } }

    /** Zu grosse Datei auf das juengste Ende kuerzen. */
    private fun trim() {
        runCatching {
            if (file.exists() && file.length() > MAX_BYTES) file.writeText(file.readText().takeLast((MAX_BYTES / 2).toInt()))
        }
    }
}

val Context.store: Store get() = (applicationContext as HomeHyraxApp).store

/**
 * Netzwerk-Logik: lokaler Proxy (Go), WireGuard-Tunnel, Heimnetz-Erkennung.
 *
 * Alle WebViews der App laufen ueber den lokalen Proxy. Er entscheidet pro
 * Ziel: Route gesetzt -> durch den Tunnel, sonst direkt. Kein Android-VPN.
 */
object Net {
    /** Passwort des lokalen Proxys, bei jedem App-Start neu. */
    val proxySecret: String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }

    private lateinit var cm: ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var proxyReady = false
    /** Proxy-Start/-Umstellung nie parallel (zwei Web-Apps gleichzeitig) */
    private val proxyLock = Mutex()
    private val users = mutableMapOf<String, Int>()
    private val stopJobs = mutableMapOf<String, Job>()

    /** Tunnel bleibt nach dem Verlassen der App noch so lange stehen. */
    private const val LINGER_MS = 90_000L

    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { pushInterfaces(); Wgbridge.networkChanged() }
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                scope.launch { pushInterfaces(); Wgbridge.networkChanged() }
            }

            override fun onLost(network: Network) {
                scope.launch { pushInterfaces() }
            }
        })
    }

    /**
     * Tailscale braucht die Netzwerk-Schnittstellen; Go darf sie unter Android
     * nicht selbst lesen (netlink gesperrt). Also von hier melden.
     */
    private fun pushInterfaces() {
        val spec = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().joinToString("\n") { n ->
                val flags = buildString {
                    if (n.isUp) append('u'); if (n.isLoopback) append('l'); if (n.supportsMulticast()) append('m')
                }
                val addrs = n.interfaceAddresses.mapNotNull { a ->
                    a.address?.hostAddress?.substringBefore('%')?.let { "$it/${a.networkPrefixLength}" }
                }.joinToString(",")
                "${n.name}\t${n.index}\t${runCatching { n.mtu }.getOrDefault(1500)}\t$flags\t$addrs"
            }
        }.getOrDefault("")
        Wgbridge.setInterfaces(spec)
        val active = cm.activeNetwork
        Wgbridge.setDefaultInterface(active?.let { cm.getLinkProperties(it)?.interfaceName }.orEmpty())
    }

    /** Geraetename in Tailscale, z. B. "homehyrax-pixel-8" */
    private val tsHostname: String
        get() = ("homehyrax-" + Build.MODEL).lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(60)

    val proxySupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /** false = WebView beantwortet die Proxy-Anmeldung nicht -> Ausweichmodus */
    var proxyAuthWorks = true
        private set

    /** Startet den Go-Proxy und leitet alle WebViews darueber. */
    suspend fun ensureProxy(): Boolean = proxyLock.withLock {
        if (proxyReady) return@withLock true
        if (!proxySupported) return@withLock false
        applyProxy(if (proxyAuthWorks) proxySecret else "")
        proxyReady = true
        true
    }

    /**
     * Ausweichmodus: manche WebView-Versionen reichen die Anmeldung am Proxy
     * nicht an die App weiter (Seite zeigt 407). Dann Proxy ohne Passwort auf
     * neuem Zufallsport. 127.0.0.1 erreicht jede App auf dem Handy - deshalb laesst
     * der Go-Proxy ohne Passwort nur das Ziel der sichtbaren Web-App durch einen
     * Tunnel (siehe webAppVisible) und nur, solange eine sichtbar ist.
     */
    suspend fun disableProxyAuth() = withContext(NonCancellable) {
        // nicht abbrechbar: zwischen startProxy("") und setProxyOverride darf nichts halb stehen bleiben
        proxyLock.withLock {
            if (!proxyAuthWorks) return@withLock
            proxyAuthWorks = false
            applyProxy("")
            synchronized(this@Net) { if (visibleWebApps == 0) Wgbridge.setProxySecret(proxySecret) }
        }
    }

    /** Sichtbare Web-Apps. Der Ausweichmodus (Proxy ohne Passwort) ist nur offen, solange eine sichtbar ist. */
    private var visibleWebApps = 0

    @Synchronized
    fun webAppVisible(visible: Boolean, app: WebApp? = null) {
        visibleWebApps = (visibleWebApps + if (visible) 1 else -1).coerceAtLeast(0)
        // Ausweichmodus: ohne Passwort nur dieses Ziel durch einen Tunnel (ohne app: keins)
        if (visible) Wgbridge.setProxyFallbackTarget(app?.let { hostPort(it) }.orEmpty())
        else if (visibleWebApps == 0) Wgbridge.setProxyFallbackTarget("")
        if (!proxyAuthWorks && proxyReady) Wgbridge.setProxySecret(if (visibleWebApps > 0) "" else proxySecret)
    }

    /** "host:port" der Web-App fuer den Go-Proxy (IPv6 in Klammern). */
    private fun hostPort(app: WebApp): String {
        val h = app.host
        if (h.isEmpty()) return ""
        return (if (':' in h && !h.startsWith("[")) "[$h]" else h) + ":" + app.port
    }

    /** Kommt diese 407 wirklich vom eigenen Proxy (und nicht von einem Geraet im Heimnetz)? */
    fun isOwnProxy407(headers: Map<String, String>?): Boolean =
        headers?.entries?.any { it.key.equals(Wgbridge.ProxyMarkerHeader, ignoreCase = true) && it.value == Wgbridge.proxyMarker() } == true

    /** Port des eigenen lokalen Proxys (0 = noch nicht gestartet). */
    @Volatile var proxyPort = 0L
        private set

    private suspend fun applyProxy(secret: String) {
        if (!proxySupported) return
        val port = withContext(Dispatchers.IO) { Wgbridge.startProxy(secret) }
        proxyPort = port.toLong()
        val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
        suspendCancellableCoroutine { cont ->
            ProxyController.getInstance().setProxyOverride(cfg, { it.run() }) { cont.resume(Unit) }
        }
    }

    /**
     * Erkennungsmerkmal des aktuellen WLANs ohne Standort-Berechtigung (die SSID gibt Android
     * nur mit Standortzugriff heraus): IPv4-Netz + Router-Adresse + DNS-Domaene,
     * z. B. "192.168.178.0/24|192.168.178.1|fritz.box". null = kein WLAN/LAN.
     */
    fun wifiFingerprint(): String? {
        val n = localNetwork() ?: return null
        val lp = cm.getLinkProperties(n) ?: return null
        val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val net = runCatching {
            val b = v4.address.address.copyOf()
            val bits = v4.prefixLength
            for (i in b.indices) {
                val keep = (bits - i * 8).coerceIn(0, 8)
                b[i] = (b[i].toInt() and (0xFF shl (8 - keep))).toByte()
            }
            InetAddress.getByAddress(b).hostAddress + "/" + bits
        }.getOrNull() ?: return null
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress.orEmpty()
        return "$net|$gw|${lp.domains.orEmpty()}"
    }

    /** Liegt die (IP-)Adresse der Web-App im aktuellen WLAN-Adressbereich? */
    private fun inCurrentSubnet(host: String): Boolean {
        // nur IPv4-Literale, ohne DNS-Anfrage (laeuft auf dem Main-Thread)
        val parts = host.split('.').map { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it == null || it !in 0..255 }) return false
        val y = ByteArray(4) { parts[it]!!.toByte() }
        val lp = localNetwork()?.let { cm.getLinkProperties(it) } ?: return false
        return lp.linkAddresses.any { la ->
            val a = la.address as? Inet4Address ?: return@any false
            val bits = la.prefixLength
            val x = a.address
            (0 until 4).all { i ->
                val keep = (bits - i * 8).coerceIn(0, 8)
                val m = (0xFF shl (8 - keep)) and 0xFF
                (x[i].toInt() and m) == (y[i].toInt() and m)
            }
        }
    }

    private val homePrefs by lazy { app.getSharedPreferences("homenets", Context.MODE_PRIVATE) }

    /** WLANs, in denen diese Web-App schon direkt erreichbar war. */
    private fun knownHomes(appId: String): Set<String> = homePrefs.getStringSet(appId, emptySet()) ?: emptySet()

    /** Aktuelles WLAN als "hier ist die Web-App direkt erreichbar" merken (max. 5 je Web-App). */
    fun rememberHome(app: WebApp) {
        val fp = wifiFingerprint() ?: return
        val set = knownHomes(app.id)
        if (fp in set) return
        homePrefs.edit().putStringSet(app.id, (set.toList().takeLast(4) + fp).toSet()).apply()
    }

    /** Ergebnis der Zuhause-Pruefung. skipped = gar nicht angeklopft, weil dieses WLAN nicht als Zuhause bekannt ist. */
    data class Where(val home: Boolean, val skipped: Boolean)

    /**
     * Zuhause oder unterwegs? Ohne WLAN -> unterwegs. In einem WLAN, das fuer diese Web-App
     * schon bekannt ist (oder dessen Adressbereich die Ziel-IP enthaelt) -> kurz anklopfen.
     * In einem anderen WLAN -> sofort Tunnel, sobald die App schon ein Zuhause-WLAN kennt.
     */
    suspend fun where(app: WebApp): Where {
        if (app.tunnelId == null) return Where(true, false)
        if (app.alwaysTunnel) return Where(false, false)   // "Immer VPN": nie direkt, auch kein Direkt-Rueckfall
        val fp = withContext(Dispatchers.IO) { wifiFingerprint() } ?: return Where(false, false)
        val known = knownHomes(app.id)
        if (known.isNotEmpty() && fp !in known && !inCurrentSubnet(app.host)) return Where(false, true)
        return Where(reachableDirect(app), false)
    }

    /**
     * Bin ich zuhause? = das Geraet ist ueber WLAN/LAN direkt erreichbar.
     * Geprueft wird ueber das WLAN selbst (falls ein anderes VPN aktiv ist,
     * wird es so umgangen, sofern es das erlaubt), sonst ueber das Standardnetz.
     */
    suspend fun reachableDirect(app: WebApp, timeoutMs: Int = 800): Boolean = withContext(Dispatchers.IO) {
        if (app.host.isEmpty()) return@withContext false
        val local = localNetwork()
        if (local != null) {
            val ok = runCatching {
                local.socketFactory.createSocket().use { it.connect(InetSocketAddress(app.host, app.port), timeoutMs) }
            }
            if (ok.isSuccess) { rememberHome(app); return@withContext true }
        }
        // Mobilfunk ohne WLAN: nicht erst warten, sofort Tunnel
        if (local == null && app.tunnelId != null) return@withContext false
        runCatching {
            Socket().use { it.connect(InetSocketAddress(app.host, app.port), timeoutMs) }
        }.isSuccess
    }

    @Suppress("DEPRECATION")
    fun localNetwork(): Network? = cm.allNetworks.firstOrNull { n ->
        val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
        !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    /**
     * Tunnel starten (falls noetig) und auf den Handshake warten.
     * @return null = verbunden, sonst eine verstaendliche Fehlermeldung
     */
    suspend fun connect(t: Tunnel): String? = withContext(Dispatchers.IO) {
        if (t.isTailscale) return@withContext connectTailscale(t)
        try {
            Wgbridge.startTunnel(t.id, t.config)
        } catch (e: Exception) {
            return@withContext friendly(e.message)
        }
        if (Wgbridge.waitHandshake(t.id, 7000)) null
        else app.getString(R.string.net_no_reply_home, t.endpoint) + "\n" +
            goMsg(Wgbridge.lastError(t.id)).ifEmpty { app.getString(R.string.net_router_unreachable) }
    }

    private fun connectTailscale(t: Tunnel): String? {
        try {
            pushInterfaces()
            Wgbridge.startTailscale(t.id, app.store.tailscaleDir(t.id).path, t.config, tsHostname)
        } catch (e: Exception) {
            return friendly(e.message)
        }
        if (Wgbridge.waitHandshake(t.id, 15000)) return null
        return goMsg(Wgbridge.lastError(t.id)).ifEmpty { app.getString(R.string.net_ts_not_connecting) }
    }

    /** Tailscale starten (kehrt sofort zurueck); null = ok, sonst Fehlermeldung. */
    suspend fun startTailscale(t: Tunnel): String? = withContext(Dispatchers.IO) {
        runCatching {
            pushInterfaces()
            Wgbridge.startTailscale(t.id, app.store.tailscaleDir(t.id).path, t.config, tsHostname)
        }.exceptionOrNull()?.let { friendly(it.message) }
    }

    /** Tunnel eine Weile offen halten (z. B. waehrend der Anmeldung im Browser). */
    fun holdFor(id: String, ms: Long) {
        acquire(id)
        scope.launch { delay(ms); release(id) }
    }

    /** Tailscale-Zustand (fuer die Tunnel-Karte). */
    data class TsStatus(val state: String, val authUrl: String, val ip: String, val user: String, val routes: String, val error: String)

    fun tailscaleStatus(id: String): TsStatus = runCatching {
        val o = org.json.JSONObject(Wgbridge.tailscaleStatus(id))
        TsStatus(o.optString("state"), o.optString("authURL"), o.optString("ip"), o.optString("user"),
            o.optString("routes"), o.optString("error"))
    }.getOrDefault(TsStatus("Stopped", "", "", "", "", ""))

    private fun friendly(msg: String?): String = if (msg == null) app.getString(R.string.net_unknown_error) else goMsg(msg)

    /**
     * Meldungen aus dem Go-Kern (englisch, sprachneutral) in die App-Sprache uebersetzen.
     * Nur bekannte Texte; alles andere (technische Details) bleibt wie es ist.
     */
    fun goMsg(msg: String): String = when {
        msg.isEmpty() -> msg
        msg == "Tailscale: login required" -> app.getString(R.string.net_ts_login_required)
        msg == "Tailscale: device needs approval in the admin console" -> app.getString(R.string.net_ts_needs_approval)
        msg == "Tailscale settings invalid" -> app.getString(R.string.net_ts_settings_invalid)
        msg == "Tailscale not started" -> app.getString(R.string.net_ts_not_started)
        msg == "invalid key" -> app.getString(R.string.net_invalid_key)
        msg == "tunnel not active" -> app.getString(R.string.net_tunnel_not_active)
        "cannot be resolved" in msg -> app.getString(R.string.net_host_not_found, msg)
        else -> msg
    }

    /** Leitet den Host der Web-App durch den Tunnel (oder wieder direkt). */
    fun route(app: WebApp, tunnelId: String?) {
        Wgbridge.setRoute(app.host, tunnelId ?: "")
    }

    /** Eine Web-App nutzt den Tunnel (Referenzzaehlung, Stopp verzoegert). */
    @Synchronized
    fun acquire(tunnelId: String) {
        stopJobs.remove(tunnelId)?.cancel()
        users[tunnelId] = (users[tunnelId] ?: 0) + 1
    }

    @Synchronized
    fun release(tunnelId: String) {
        val n = ((users[tunnelId] ?: 0) - 1).coerceAtLeast(0)
        users[tunnelId] = n
        if (n <= 0) {
            stopJobs[tunnelId]?.cancel()
            stopJobs[tunnelId] = scope.launch {
                delay(LINGER_MS)
                // nur die Pruefung unter der Sperre; das Stoppen (Tailscale kann dauern) ausserhalb,
                // sonst warten acquire()/release() im Main-Thread darauf (ANR)
                val stop = synchronized(this@Net) { (users[tunnelId] ?: 0) <= 0 }
                if (stop) runCatching { Wgbridge.stopTunnel(tunnelId) }
            }
        }
    }

    fun isRunning(tunnelId: String) = Wgbridge.isRunning(tunnelId)

    /**
     * Tailscale-Test mit Diagnose: verbinden, dann fuer jede Web-App dieses Tunnels pruefen,
     * ob ihre Adresse ueber Tailscale geht, ueber welches Geraet, und ob sie antwortet.
     * null = alles gut, sonst Text mit den Ergebnissen (auch bei Erfolg die Details).
     */
    suspend fun testTailscale(t: Tunnel, apps: List<WebApp>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val wasRunning = Wgbridge.isRunning(t.id)
        try {
            val err = connect(t)
            if (err != null) return@withContext false to err
            val lines = apps.filter { it.tunnelId == t.id && it.host.isNotEmpty() }.map { a ->
                "${a.name}: " + Wgbridge.tailscaleProbe(t.id, "${a.host}:${a.port}", 12000)
            }
            // Diagnosetexte kommen englisch aus dem Go-Kern (tailscale.go TailscaleProbe) - Schluesselwoerter dort
            val hint = if (lines.any { "FAILED" in it && "OK" in it.substringAfter("tunnel to", "") })
                "\n→ " + app.getString(R.string.net_ts_router_no_forward) else ""
            (lines.none { "FAILED" in it || "NOT via" in it }) to (lines.joinToString("\n") + hint)
        } finally {
            if (!wasRunning) stopIfUnused(t.id)   // auch beim Abbrechen (Seite verlassen)
        }
    }

    /** Verbindung testen (Tunnel-Liste): kurz aufbauen, danach wieder abbauen. */
    suspend fun test(t: Tunnel): String? {
        val wasRunning = Wgbridge.isRunning(t.id)
        try {
            return connect(t)
        } finally {
            // auch beim Abbrechen (Seite verlassen) wieder abbauen
            if (!wasRunning) withContext(NonCancellable + Dispatchers.IO) { stopIfUnused(t.id) }
        }
    }

    /** Tunnel stoppen, sofern ihn gerade niemand nutzt (Web-App, Kamera, Schaltflaeche). Stoppen ausserhalb der Sperre. */
    private fun stopIfUnused(tunnelId: String) {
        val unused = synchronized(this) { (users[tunnelId] ?: 0) <= 0 }
        if (unused) runCatching { Wgbridge.stopTunnel(tunnelId) }
    }
}
