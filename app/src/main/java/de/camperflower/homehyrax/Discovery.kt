package de.camperflower.homehyrax

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL

/** Ein im Heimnetz gefundener Shelly. outputs = Anzahl Schaltkanaele (0 = unbekannt). */
data class FoundShelly(
    val ip: String,
    val gen: Int,
    val name: String,
    val model: String,
    val outputs: Int,
    val auth: Boolean,
)

/**
 * Shelly-Suche: fragt jede Adresse des Heim-WLANs (/24) parallel nach
 * GET /shelly - das beantworten Gen1 und Gen2/Plus/Pro/Gen3 gleichermassen.
 * Laeuft ueber das WLAN (an einem evtl. aktiven anderen VPN vorbei).
 */
object Discovery {

    /** Heim-WLAN + eigene IPv4 oder null, wenn kein WLAN/LAN. */
    private fun wifi(ctx: Context): Pair<Network, Inet4Address>? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = Net.localNetwork() ?: return null
        val ip = cm.getLinkProperties(net)?.linkAddresses
            ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull() ?: return null
        return net to ip
    }

    /** null = gestartet; sonst Grund, warum nicht gesucht werden kann. */
    fun unavailableReason(ctx: Context): String? =
        if (wifi(ctx) == null) ctx.getString(R.string.disc_home_wifi_only) else null

    suspend fun scan(
        ctx: Context,
        onProgress: (done: Int, total: Int) -> Unit,
        onFound: (FoundShelly) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val (net, own) = wifi(ctx) ?: return@withContext
        val b = own.address
        val prefix = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}."
        val self = b[3].toInt() and 255
        val hosts = (1..254).filter { it != self }.map { prefix + it }
        val gate = Semaphore(48)
        var done = 0
        coroutineScope {
            hosts.map { ip ->
                async {
                    gate.withPermit {
                        val s = probe(net, ip)
                        withContext(Dispatchers.Main) {
                            done++
                            onProgress(done, hosts.size)
                            if (s != null) onFound(s)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun get(net: Network, url: String, connectMs: Int = 700): JSONObject? = runCatching {
        val c = net.openConnection(URL(url)) as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = 1500
        c.instanceFollowRedirects = false
        try {
            if (c.responseCode != 200) return null
            // begrenzt lesen: ein Geraet im Netz koennte endlos senden
            JSONObject(c.inputStream.use { it.readAtMost(64_000).decodeToString() })
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun probe(net: Network, ip: String): FoundShelly? {
        val o = get(net, "http://$ip/shelly") ?: return null
        val gen = o.optInt("gen", 0)
        if (gen >= 2) {
            // Gen2+: {"id":"shellyplus1-...","name":"Garage","model":"SNSW-001X16EU","gen":2,"app":"Plus1","auth_en":false}
            val auth = o.optBoolean("auth_en")
            var outputs = 0
            if (!auth) get(net, "http://$ip/rpc/Shelly.GetStatus", 1500)?.let { st ->
                outputs = st.keys().asSequence().count { it.startsWith("switch:") }
            }
            val app = o.optString("app").ifEmpty { o.optString("model") }
            val name = o.optString("name").takeIf { it.isNotEmpty() && it != "null" } ?: o.optString("id", "Shelly")
            return FoundShelly(ip, 2, name, "Shelly $app", outputs, auth)
        }
        val type = o.optString("type")
        if (!type.startsWith("SH")) return null            // kein Shelly
        // Gen1: {"type":"SHSW-1","mac":"...","auth":false,"num_outputs":1}
        val auth = o.optBoolean("auth")
        val name = if (auth) null else get(net, "http://$ip/settings", 1500)?.optString("name")
            ?.takeIf { it.isNotEmpty() && it != "null" }
        return FoundShelly(ip, 1, name ?: "Shelly $type", type, o.optInt("num_outputs", 1), auth)
    }
}
