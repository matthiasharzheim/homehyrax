package de.camperflower.homehyrax

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

class ProbeTest {

    /** Lokaler Server, der jede Verbindung mit [handle] bedient; liefert den Port. */
    private fun server(ss: ServerSocket = ServerSocket(0, 5, InetAddress.getLoopbackAddress()), handle: (Socket) -> Unit): Int {
        thread(isDaemon = true) {
            runCatching { while (true) { val c = ss.accept(); thread(isDaemon = true) { runCatching { c.use(handle) } } } }
        }
        return ss.localPort
    }

    private fun client(port: Int) = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 1500 }

    private fun readRequest(c: Socket) {
        val inp = c.getInputStream()
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) return; sb.append(b.toChar()) }
    }

    @Test fun httpAnswer() {
        val port = server { c -> readRequest(c); c.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray()) }
        client(port).use { assertTrue(Probe.httpAnswers(it, "127.0.0.1:$port")) }
    }

    @Test fun closingIsNotAnAnswer() {
        val port = server { c -> readRequest(c) }   // schliesst ohne Antwort (wie der Proxy bei Abriss)
        client(port).use { assertFalse(Probe.httpAnswers(it, "127.0.0.1:$port")) }
        client(port).use { assertFalse(Probe.tlsAnswers(it, "127.0.0.1", port)) }
    }

    @Test fun silentIsNotAnAnswer() {
        val port = server { _ -> Thread.sleep(5000) }
        client(port).use { assertFalse(runCatching { Probe.httpAnswers(it, "127.0.0.1:$port") }.getOrDefault(false)) }
        client(port).use { assertFalse(Probe.tlsAnswers(it, "127.0.0.1", port)) }
    }

    @Test fun proxyConnect() {
        val ok = server { c -> readRequest(c); c.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()) }
        client(ok).use { assertTrue(Probe.connect(it, "192.168.178.20:443", "")) }
        val bad = server { c -> readRequest(c); c.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
        client(bad).use { assertFalse(Probe.connect(it, "192.168.178.20:443", "")) }
        val auth = server { c -> readRequest(c); c.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".toByteArray()) }
        client(auth).use { assertFalse(Probe.connect(it, "192.168.178.20:443", "")) }
    }

    @Test fun tlsServerThatClosesOrSpeaksPlainHttp() {
        val closes = server { _ -> }   // nimmt an und schliesst sofort
        client(closes).use { assertFalse(Probe.tlsAnswers(it, "127.0.0.1", closes)) }
        val plain = server { c -> c.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
        client(plain).use { assertFalse(Probe.tlsAnswers(it, "127.0.0.1", plain)) }
    }

    /** Selbstsignierter TLS-Server (wie viele Geraete im Heimnetz): Zertifikat kommt -> erreichbar, nichts wird vertraut. */
    @Test fun tlsSelfSignedAnswers() {
        val port = tlsServer()
        client(port).use { assertTrue(Probe.tlsAnswers(it, "127.0.0.1", port)) }
    }

    /** Ganze Kette wie in Net.reachableNow: CONNECT ueber einen Proxy, danach TLS auf demselben Socket. */
    @Test fun connectThenTls() {
        val tls = tlsServer()
        val proxy = server { c ->
            readRequest(c)
            Socket(InetAddress.getLoopbackAddress(), tls).use { t ->
                c.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                thread(isDaemon = true) { runCatching { c.getInputStream().copyTo(t.getOutputStream()) } }
                runCatching { t.getInputStream().copyTo(c.getOutputStream()) }
            }
        }
        client(proxy).use {
            assertTrue(Probe.connect(it, "192.168.178.20:443", ""))
            assertTrue(Probe.tlsAnswers(it, "192.168.178.20", 443))
        }
    }

    private fun tlsServer(): Int {
        val ks = File.createTempFile("probe", ".p12").apply { delete(); deleteOnExit() }
        val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (File.separatorChar == '\\') ".exe" else "")
        val p = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "t", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=192.168.178.20", "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.path,
            "-storepass", "probetest", "-keypass", "probetest").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertTrue("keytool", p.waitFor() == 0)
        val store = KeyStore.getInstance("PKCS12").apply { FileInputStream(ks).use { load(it, "probetest".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "probetest".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val ss = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        return server(ss) { c -> runCatching { (c as javax.net.ssl.SSLSocket).startHandshake() } }
    }
}
