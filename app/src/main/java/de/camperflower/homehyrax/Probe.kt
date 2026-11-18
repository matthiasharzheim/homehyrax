package de.camperflower.homehyrax

import android.annotation.SuppressLint
import java.io.InputStream
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Bausteine fuer "antwortet das Geraet wirklich?" (Net.reachableNow) - ohne Android, damit sie per JVM-Test laufen.
 * Es werden keine Nutzdaten ausgetauscht: bei http eine HEAD-Anfrage, bei https nur der Beginn des TLS-Handshakes.
 */
object Probe {

    /** CONNECT host:port ueber den eigenen Proxy; true = Proxy meldet 200 (Ziel verbunden, Daten folgen roh). */
    fun connect(s: Socket, hostPort: String, authHeader: String): Boolean {
        s.getOutputStream().apply {
            write("CONNECT $hostPort HTTP/1.1\r\nHost: $hostPort\r\n$authHeader\r\n".toByteArray(Charsets.ISO_8859_1))
            flush()
        }
        val head = readHead(s.getInputStream()) ?: return false
        return head.split(' ').getOrNull(1) == "200"
    }

    /** http: HEAD / und mindestens ein Byte Antwort. Nur Schliessen zaehlt nicht (das macht auch der Proxy bei Abriss). */
    fun httpAnswers(s: Socket, hostPort: String): Boolean {
        s.getOutputStream().apply {
            write("HEAD / HTTP/1.1\r\nHost: $hostPort\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            flush()
        }
        return s.getInputStream().read() >= 0
    }

    /**
     * https: TLS-Handshake beginnen; true, sobald der Server sein Zertifikat schickt. Danach bricht der Handshake ab -
     * der Trust-Manager lehnt JEDES Zertifikat ab (er merkt sich nur, dass eins kam), es wird also nie etwas
     * vertraut und nichts gesendet. Klartext reicht hier nicht: OpenSSL-Server (Python, aiohttp, Node) schliessen
     * auf Klartext nur, ohne ein Byte zu antworten.
     */
    fun tlsAnswers(s: Socket, host: String, port: Int): Boolean {
        var seen = false
        // lehnt ab statt anzunehmen (kein "trust all"); der Kontext ist je Probe neu - nie zwischenspeichern: eine
        // wiederaufgenommene Sitzung kaeme ohne Zertifikat aus und galte dann immer als "nicht erreichbar"
        @SuppressLint("CustomX509TrustManager")
        val rejectAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) =
                throw CertificateException("probe only")
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                seen = !chain.isNullOrEmpty()
                throw CertificateException("probe only")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(rejectAll), null) }
        val ssl = ctx.socketFactory.createSocket(s, host, port, false) as SSLSocket
        ssl.soTimeout = s.soTimeout
        runCatching { ssl.startHandshake() }   // scheitert immer (Absicht) - entscheidend ist, ob ein Zertifikat kam
        runCatching { ssl.close() }
        return seen
    }

    /** Statuszeile + Kopfzeilen bis zur Leerzeile, Byte fuer Byte (danach kommen die Daten des Ziels). */
    private fun readHead(inp: InputStream): String? {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = inp.read()
            if (b < 0 || sb.length > 4096) return null
            sb.append(b.toChar())
        }
        return sb.toString()
    }
}
