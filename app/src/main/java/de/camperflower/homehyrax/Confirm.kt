package de.camperflower.homehyrax

import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Geraeteschluessel je Eintrag: ECDSA P-256 im Android-Keystore (nicht exportierbar, wenn vorhanden in StrongBox).
 * Damit unterschreibt die App Bestaetigungen, die die Seite des Eintrags anfordert (ConfirmBridge). Der Server des
 * Besitzers prueft die Unterschrift mit dem oeffentlichen Schluessel, den der Besitzer dort einmal eintraegt.
 */
object DeviceKey {
    private const val PREFIX = "homehyrax-confirm-"
    private fun alias(appId: String) = PREFIX + appId
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun exists(appId: String): Boolean = runCatching { ks().containsAlias(alias(appId)) }.getOrDefault(false)

    /** Oeffentlicher Schluessel (SubjectPublicKeyInfo, DER), null = keiner angelegt */
    fun publicKey(appId: String): ByteArray? = runCatching { ks().getCertificate(alias(appId))?.publicKey?.encoded }.getOrNull()

    /** Vorhandenen Schluessel liefern oder neu anlegen (kann mit StrongBox ~1 s dauern: nicht im UI-Thread) */
    fun ensure(appId: String): ByteArray = publicKey(appId) ?: create(appId)

    /** Neuen Schluessel anlegen (alter wird ersetzt - der Server muss den neuen eintragen) */
    fun renew(appId: String): ByteArray { delete(appId); return create(appId) }

    fun delete(appId: String) { runCatching { ks().deleteEntry(alias(appId)) } }

    // Bewusst kein Aufraeumen beim Start: bei unlesbarer apps.json (Store legt sie nur beiseite) oder einem Entwurf,
    // dessen Schluessel schon auf dem Server steht, waeren Schluessel unwiderruflich weg. Geloescht wird nur beim
    // Loeschen des Eintrags und beim Ausschalten + Speichern; ein ungespeicherter Entwurf hinterlaesst einen
    // unbenutzten Schluessel (nicht exportierbar, harmlos).

    private fun create(appId: String): ByteArray {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias(appId), KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        fun gen(strongBox: Boolean) = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(spec(strongBox)) }.generateKeyPair()
        val pair = if (Build.VERSION.SDK_INT >= 28) {
            try { gen(true) } catch (_: StrongBoxUnavailableException) { gen(false) }
        } else gen(false)
        return pair.public.encoded
    }

    /** Unterschrift SHA256withECDSA (DER) ueber [data]; null = kein Schluessel */
    fun sign(appId: String, data: ByteArray): ByteArray? {
        val key = ks().getKey(alias(appId), null) as? PrivateKey ?: return null
        return Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() }
    }

    /** Schluessel-ID = erste 32 Hex-Zeichen von SHA-256 ueber den oeffentlichen Schluessel (SPKI) */
    fun keyId(spki: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(spki).joinToString("") { "%02x".format(it) }.take(32)

    /** Kurzer Fingerabdruck zum Vergleichen = Anfang der Schluessel-ID, z. B. "3F2A 9C1B 77D0 4E85" */
    fun fingerprint(spki: ByteArray): String = keyId(spki).take(16).uppercase().chunked(4).joinToString(" ")

    /** PEM-Form zum Kopieren (fuer openssl, PHP, Python ...) */
    fun pem(spki: ByteArray): String =
        "-----BEGIN PUBLIC KEY-----\n" + Base64.encodeToString(spki, Base64.NO_WRAP).chunked(64).joinToString("\n") +
            "\n-----END PUBLIC KEY-----\n"

    /** ECDSA-Unterschrift DER -> r||s (je 32 Byte, IEEE P1363, wie WebCrypto sie erwartet) */
    fun derToP1363(der: ByteArray): ByteArray {
        // SEQUENCE { INTEGER r, INTEGER s }; Laengen bei P-256 immer < 128 (Kurzform), notfalls Langform 0x81
        var i = 0
        fun len(): Int { val b = der[i++].toInt() and 0xff; return if (b < 0x80) b else { var n = 0; repeat(b and 0x7f) { n = (n shl 8) or (der[i++].toInt() and 0xff) }; n } }
        require(der[i++].toInt() == 0x30); len()
        fun int(): ByteArray {
            require(der[i++].toInt() == 0x02)
            val l = len()
            var v = der.copyOfRange(i, i + l); i += l
            while (v.size > 32 && v[0].toInt() == 0) v = v.copyOfRange(1, v.size)
            require(v.size <= 32)
            return ByteArray(32 - v.size) + v
        }
        return int() + int()
    }
}

/**
 * Bruecke "Bestaetigen mit Geraeteschluessel" fuer die Seite eines Eintrags: window.HomeHyraxConfirm mit
 * request({text, challenge}) und publicKey(), beide liefern ein Promise auf ein Objekt. Nur fuer den Ursprung der
 * eingetragenen Adresse und nur die Hauptseite (Nachrichtenkanal mit Ursprungsregel). Beschreibung fuer
 * Seitenbetreiber: docs/geraeteschluessel.md (gleicher Inhalt in englisch: docs/device-key.md).
 */
object ConfirmBridge {
    const val CHANNEL = "HomeHyraxConfirmChannel"
    const val VERSION = "HomeHyrax-Confirm-v1"
    const val MAX_TEXT = 500
    private val CHALLENGE = Regex("^[A-Za-z0-9_+/=.~-]{16,512}$")

    /** Stellt window.HomeHyraxConfirm bereit. Antwort ueber den Kanal = "<Nummer der Anfrage>\n<JSON>". */
    const val JS =
        "(function(){if(window.top!==window||!window.$CHANNEL||window.HomeHyraxConfirm)return;" +
        "var k=window.$CHANNEL,n=0,w={};" +
        "k.onmessage=function(e){var d=String(e.data),i=d.indexOf('\\n'),id=+d.slice(0,i),f=w[id];" +
        "if(i>0&&typeof f==='function'){delete w[id];var r;try{r=JSON.parse(d.slice(i+1));}catch(x){r={ok:false,error:'internal'};}f(r);}};" +
        "function s(m){return new Promise(function(r){var id=++n;w[id]=r;m.id=id;k.postMessage(JSON.stringify(m));});}" +
        "function t(v){return v==null?'':String(v);}" +
        "window.HomeHyraxConfirm=Object.freeze({version:1," +
        "request:function(o){o=o||{};return s({op:'request',text:t(o.text),challenge:t(o.challenge)});}," +
        "publicKey:function(){return s({op:'publicKey'});}});})();"

    /** Anfrage der Seite */
    data class Request(val id: Int, val op: String, val text: String, val challenge: String)

    fun parse(data: String?): Request? = runCatching {
        val o = JSONObject(data ?: return null)
        Request(o.getInt("id"), o.getString("op"), o.optString("text"), o.optString("challenge"))
    }.getOrNull()

    /**
     * Text fuer den Dialog: nicht leer, hoechstens MAX_TEXT Zeichen, keine Steuerzeichen ausser Zeilenumbruch und keine
     * Zeichen, die die Schreibrichtung umdrehen (sonst koennte die Anzeige vom unterschriebenen Text abweichen).
     */
    fun validText(t: String): Boolean = t.isNotBlank() && t.length <= MAX_TEXT && t.none { c ->
        (Character.getType(c) == Character.CONTROL.toInt() && c != '\n') ||
            c in '‪'..'‮' || c in '⁦'..'⁩' || c == '‎' || c == '‏' || c == '؜'
    }

    fun validChallenge(c: String): Boolean = CHALLENGE.matches(c)

    /** Ursprung wie location.origin: schema://host[:port], Kleinschreibung, Standard-Port weggelassen */
    fun origin(u: Uri): String? {
        val scheme = u.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        var host = u.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (':' in host && !host.startsWith("[")) host = "[$host]"
        val def = if (scheme == "https") 443 else 80
        return "$scheme://$host" + (if (u.port > 0 && u.port != def) ":${u.port}" else "")
    }

    /** Feste Zeichenfolge, die unterschrieben wird (UTF-8). Der Text steht zuletzt und darf Zeilenumbrueche enthalten. */
    fun canonical(origin: String, challenge: String, text: String): String = "$VERSION\n$origin\n$challenge\n$text"

    fun error(code: String): String = JSONObject().put("ok", false).put("error", code).toString()

    fun keyInfo(spki: ByteArray): JSONObject = JSONObject().put("ok", true).put("alg", "ES256")
        .put("keyId", DeviceKey.keyId(spki)).put("fingerprint", DeviceKey.fingerprint(spki))
        .put("publicKey", Base64.encodeToString(spki, Base64.NO_WRAP))

    fun signed(spki: ByteArray, origin: String, der: ByteArray): String = keyInfo(spki).put("origin", origin)
        .put("signature", Base64.encodeToString(der, Base64.NO_WRAP))
        .put("signatureP1363", Base64.encodeToString(DeviceKey.derToP1363(der), Base64.NO_WRAP))
        .toString()

    fun reply(id: Int, json: String): String = "$id\n$json"
}
