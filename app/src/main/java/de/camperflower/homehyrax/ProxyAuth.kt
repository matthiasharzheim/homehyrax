package de.camperflower.homehyrax

import java.net.Authenticator
import java.net.InetAddress
import java.net.PasswordAuthentication

/**
 * Anmeldung am eigenen lokalen Proxy fuer Verbindungen der App selbst (Downloads aus Web-Apps).
 * Bei https fragt Android (OkHttp im System) beim CONNECT ueber java.net.Authenticator nach - und nennt als
 * Host nicht "127.0.0.1", sondern den per Rueckwaertsaufloesung gefundenen Namen ("localhost"). Deshalb zaehlt
 * die Adresse (Loopback) und der eigene Port, nicht der Name.
 */
object ProxyAuth {

    /** Gilt die Rueckfrage dem eigenen Proxy (Loopback + eigener Port)? Sonst keine Zugangsdaten. */
    fun isOwnProxy(type: Authenticator.RequestorType?, host: String?, site: InetAddress?, port: Int, proxyPort: Long): Boolean {
        if (type != Authenticator.RequestorType.PROXY || proxyPort <= 0 || port.toLong() != proxyPort) return false
        return site?.isLoopbackAddress ?: (host == "127.0.0.1" || host == "localhost")
    }

    /** Authenticator, der nur dem eigenen Proxy antwortet; [secret] null = gerade keine Anmeldung moeglich. */
    fun authenticator(proxyPort: () -> Long, user: String, secret: () -> String?) = object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            if (!isOwnProxy(requestorType, requestingHost, requestingSite, requestingPort, proxyPort())) return null
            val s = secret() ?: return null
            return PasswordAuthentication(user, s.toCharArray())
        }
    }
}
