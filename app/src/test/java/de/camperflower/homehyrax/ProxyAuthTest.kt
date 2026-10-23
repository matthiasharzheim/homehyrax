package de.camperflower.homehyrax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Authenticator
import java.net.Authenticator.RequestorType
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

class ProxyAuthTest {
    private val port = 41234

    /** So fragt OkHttp (Android-HttpURLConnection) beim CONNECT einer https-Adresse ueber den Proxy nach. */
    private fun askLikeOkHttp(): java.net.PasswordAuthentication? {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
        val a = proxy.address() as InetSocketAddress
        return Authenticator.requestPasswordAuthentication(a.hostName, a.address, a.port, "https",
            "HomeHyrax", "Basic", URL("https://192.168.178.20:8790/datei?id=1"), RequestorType.PROXY)
    }

    @Test fun answersOwnProxyForHttpsTunnel() {
        Authenticator.setDefault(ProxyAuth.authenticator({ port.toLong() }, "homehyrax") { "geheim" })
        try {
            val auth = askLikeOkHttp()
            assertEquals("homehyrax", auth?.userName)
            assertEquals("geheim", String(auth!!.password))
        } finally {
            Authenticator.setDefault(null)
        }
    }

    @Test fun noAnswerWithoutSecret() {
        Authenticator.setDefault(ProxyAuth.authenticator({ port.toLong() }, "homehyrax") { null })
        try { assertNull(askLikeOkHttp()) } finally { Authenticator.setDefault(null) }
    }

    @Test fun nameDoesNotMatterAddressDoes() {
        val lo = InetAddress.getByName("127.0.0.1")
        // Rueckwaertsaufloesung liefert "localhost" - frueher die Ursache fuer "Failed to authenticate with proxy"
        assertTrue(ProxyAuth.isOwnProxy(RequestorType.PROXY, "localhost", lo, port, port.toLong()))
        assertTrue(ProxyAuth.isOwnProxy(RequestorType.PROXY, "127.0.0.1", lo, port, port.toLong()))
        assertTrue(ProxyAuth.isOwnProxy(RequestorType.PROXY, "127.0.0.1", null, port, port.toLong()))
    }

    @Test fun refusesEverythingElse() {
        val lo = InetAddress.getByName("127.0.0.1")
        val lan = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 178.toByte(), 20))
        assertFalse(ProxyAuth.isOwnProxy(RequestorType.SERVER, "127.0.0.1", lo, port, port.toLong()))
        assertFalse(ProxyAuth.isOwnProxy(RequestorType.PROXY, "127.0.0.1", lo, port + 1, port.toLong()))
        assertFalse(ProxyAuth.isOwnProxy(RequestorType.PROXY, "localhost", lan, port, port.toLong()))
        assertFalse(ProxyAuth.isOwnProxy(RequestorType.PROXY, "proxy.example", null, port, port.toLong()))
        assertFalse(ProxyAuth.isOwnProxy(RequestorType.PROXY, "127.0.0.1", lo, 0, 0))
    }
}
