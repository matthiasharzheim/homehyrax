package de.camperflower.homehyrax

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import de.camperflower.homehyrax.wgbridge.Wgbridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Dateien aus einer Web-App laden (PDF, Anhaenge, Downloads), damit ein anderer Betrachter sie oeffnen kann.
 * Laeuft ueber denselben lokalen Proxy wie die WebView - also zuhause direkt, unterwegs durch den Tunnel -,
 * mit den Cookies der Seite und den bestaetigten Zertifikaten der Web-App. Die Dateien liegen nur kurz im
 * Cache (Ordner "files", ueber den FileProvider freigegeben).
 */
object WebFiles {
    /** Obergrenze je Datei (Speicher, Datenvolumen unterwegs) */
    const val MAX_BYTES = 200L * 1024 * 1024
    /** so lange bleiben geladene Dateien und Kamerafotos im Cache */
    private const val KEEP_MS = 60 * 60_000L
    private const val MAX_REDIRECTS = 5
    /** Server-Typen, die nur "herunterladen" bedeuten - dann zaehlt die Dateiendung */
    private val GENERIC = setOf("application/octet-stream", "binary/octet-stream", "application/force-download",
        "application/x-download", "application/download", "application/unknown")

    class Loaded(val file: File, val mime: String)
    class TooLarge : IOException()
    class HttpStatus(val code: Int) : IOException("HTTP $code")
    /** Server lieferte statt der Datei eine Webseite (meist Anmeldung abgelaufen) */
    class GotPage : IOException()
    /** Weiterleitung auf eine Adresse, die dieser Eintrag nicht laden darf */
    class Blocked : IOException()

    /**
     * Anmeldung am eigenen Proxy ueber java.net.Authenticator (Antwort auf 407) statt als Kopfzeile: bei https
     * ginge eine gesetzte Kopfzeile sonst auch verschluesselt bis zum Geraet. Nur fuer Loopback und den
     * eigenen Proxy-Port, sonst keine Zugangsdaten (siehe ProxyAuth).
     */
    private val proxyLogin by lazy {
        java.net.Authenticator.setDefault(ProxyAuth.authenticator({ Net.proxyPort }, Wgbridge.ProxyUser) {
            if (Net.proxyAuthWorks) Net.proxySecret else null
        })
    }

    fun dir(ctx: Context, name: String): File = File(ctx.cacheDir, name).apply { mkdirs() }

    /** Geladene Dateien und Fotos nach einer Stunde loeschen (Betrachter hat sie bis dahin gelesen). */
    fun cleanup(ctx: Context) {
        val old = System.currentTimeMillis() - KEEP_MS
        // Ordner erst loeschen, wenn sie alt und leer sind (ein gleichzeitiger Download legt seinen gerade an)
        for (d in listOf("files", "upload")) File(ctx.cacheDir, d).walkBottomUp().filter { it != File(ctx.cacheDir, d) }
            .forEach { if (it.lastModified() < old && (it.isFile || it.list().isNullOrEmpty())) it.delete() }
    }

    fun uriFor(ctx: Context, f: File): Uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    /**
     * Laedt [url] in eine Cache-Datei. [pins] = bestaetigte Zertifikate je Host (SHA-256 hex);
     * Weiterleitungen werden selbst verfolgt, damit fuer jeden Host dessen eigener Pin gilt und
     * [allowed] auch fuer das Ziel jeder Weiterleitung geprueft wird.
     */
    suspend fun fetch(
        ctx: Context, url: String, userAgent: String?, disposition: String?, mimeHint: String?,
        pins: Map<String, String>, allowed: (String) -> Boolean,
    ): Loaded = withContext(Dispatchers.IO) {
        cleanup(ctx)
        proxyLogin
        if (url.startsWith("data:", ignoreCase = true)) return@withContext fromDataUrl(ctx, url)
        var target = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            if (target.protocol != "http" && target.protocol != "https") throw IOException("scheme")
            if (!allowed(target.host)) throw Blocked()
            val port = Net.proxyPort.toInt()
            val proxy = if (port > 0) Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)) else Proxy.NO_PROXY
            val c = target.openConnection(proxy) as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                // http: Kopfzeile gleich mitschicken (der Proxy entfernt sie vor dem Weiterreichen);
                // https: nur ueber proxyLogin, sonst laege sie im Tunnel beim Geraet
                if (port > 0 && Net.proxyAuthWorks && target.protocol == "http") {
                    val auth = Base64.encodeToString("${Wgbridge.ProxyUser}:${Net.proxySecret}".toByteArray(), Base64.NO_WRAP)
                    c.setRequestProperty("Proxy-Authorization", "Basic $auth")
                }
                userAgent?.let { c.setRequestProperty("User-Agent", it) }
                CookieManager.getInstance().getCookie(target.toString())?.let { c.setRequestProperty("Cookie", it) }
                if (c is HttpsURLConnection) pinned(c, target.host.lowercase(), pins)
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw HttpStatus(code)
                    target = URL(target, loc)
                    return@repeat
                }
                if (code !in 200..299) throw HttpStatus(code)
                val len = c.contentLengthLong
                if (len > MAX_BYTES) throw TooLarge()
                val type = c.contentType?.substringBefore(';')?.trim()?.lowercase()
                // Webseite statt Datei (die WebView haette HTML selbst angezeigt): meist abgelaufene Anmeldung
                if (type == "text/html" && mimeHint?.lowercase()?.startsWith("text/html") != true) throw GotPage()
                val name = fileName(target.toString(), c.getHeaderField("Content-Disposition") ?: disposition,
                    type ?: mimeHint)
                val mime = mimeOf(name, type ?: mimeHint)
                val f = File(dir(ctx, "files").resolve(java.util.UUID.randomUUID().toString()).apply { mkdirs() }, name)
                try {
                    c.inputStream.use { inp ->
                        f.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_BYTES) throw TooLarge()
                                out.write(buf, 0, n)
                            }
                        }
                    }
                } catch (e: Exception) {
                    f.delete(); throw e
                }
                return@withContext Loaded(f, mime)
            } finally {
                c.disconnect()
            }
        }
        throw IOException("redirects")
    }

    /** Selbst signiertes, in der Web-App bestaetigtes Zertifikat auch hier gelten lassen (nur genau dieses). */
    private fun pinned(c: HttpsURLConnection, host: String, pins: Map<String, String>) {
        val pin = pins[host] ?: return
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val system = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isNotEmpty() && sha256(chain[0].encoded) == pin) return
                try { system.checkServerTrusted(chain, authType) }
                catch (e: CertificateException) { throw CertificateException("not pinned", e) }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
        }
        c.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }.socketFactory
        val def = HttpsURLConnection.getDefaultHostnameVerifier()
        // Name im Zertifikat passt bei Router/NAS oft nicht - wie in der WebView zaehlt dann der bestaetigte Pin
        c.hostnameVerifier = HostnameVerifier { h, s ->
            val leaf = runCatching { s.peerCertificates.firstOrNull()?.encoded }.getOrNull()
            (leaf != null && sha256(leaf) == pin) || def.verify(h, s)
        }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** data:-Adresse (z. B. von einer Seite erzeugter Export) direkt in eine Datei schreiben. */
    private fun fromDataUrl(ctx: Context, url: String): Loaded {
        val head = url.substringBefore(',')
        val body = url.substringAfter(',', "")
        val type = head.removePrefix("data:").substringBefore(';').ifBlank { "text/plain" }.lowercase()
        val bytes = if (head.endsWith(";base64", ignoreCase = true)) Base64.decode(body, Base64.DEFAULT)
            else Uri.decode(body).toByteArray()
        if (bytes.size > MAX_BYTES) throw TooLarge()
        val name = fileName("download", null, type)
        val f = File(dir(ctx, "files").resolve(java.util.UUID.randomUUID().toString()).apply { mkdirs() }, name)
        f.writeBytes(bytes)
        return Loaded(f, mimeOf(name, type))
    }

    /** Dateiname aus Content-Disposition/Adresse, ohne Pfadteile und Sonderzeichen. */
    private fun fileName(url: String, disposition: String?, mime: String?): String {
        val raw = utf8Name(disposition) ?: URLUtil.guessFileName(url, disposition, mime)
        val clean = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().trimStart('.')
        return clean.ifBlank { "download" }.takeLast(120)
    }

    /** filename*=UTF-8''... (RFC 5987) kennt URLUtil nicht */
    private fun utf8Name(d: String?): String? =
        d?.let { Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(it) }
            ?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it.trim().trim('"'), "UTF-8") }.getOrNull() }

    /** Typ fuer den Betrachter: allgemeiner Server-Typ -> aus der Dateiendung raten. */
    private fun mimeOf(name: String, type: String?): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return when {
            type.isNullOrBlank() || type in GENERIC ->
                guess ?: "application/octet-stream"
            else -> type
        }
    }

    /**
     * accept-Angabe eines Datei-Felds (".pdf", "image/ *", "application/pdf") in Android-Typen.
     * Leer = alles. Unbekannte Endung -> keine Einschraenkung (lieber zu viel zeigen als gar nichts).
     */
    fun acceptTypes(accept: Array<String>?): List<String> {
        val parts = accept.orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val types = parts.map { p ->
            if (p.startsWith('.')) MimeTypeMap.getSingleton().getMimeTypeFromExtension(p.drop(1)) ?: return emptyList()
            else p
        }
        return if ("*/*" in types) emptyList() else types.distinct()
    }
}
