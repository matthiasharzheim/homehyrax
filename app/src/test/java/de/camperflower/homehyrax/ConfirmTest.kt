package de.camperflower.homehyrax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class ConfirmTest {

    @Test fun derToP1363VerifiesLikeWebCrypto() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val data = ConfirmBridge.canonical("https://192.168.178.20:8790", "abcdefghijklmnop", "Garagentor öffnen?").toByteArray()
        // viele Laeufe: r/s mit fuehrender 0 (33 Byte) und kurze Werte (< 32 Byte) kommen dabei vor
        repeat(300) {
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
            val raw = DeviceKey.derToP1363(der)
            assertEquals(64, raw.size)
            val ok = Signature.getInstance("SHA256withECDSAinP1363Format").run { initVerify(pair.public); update(data); verify(raw) }
            assertTrue(ok)
        }
    }

    @Test fun canonicalIsFixed() {
        assertEquals("HomeHyrax-Confirm-v1\nhttps://192.168.178.20:8790\nabcdefghijklmnop\nTor öffnen?\nZweite Zeile",
            ConfirmBridge.canonical("https://192.168.178.20:8790", "abcdefghijklmnop", "Tor öffnen?\nZweite Zeile"))
    }

    @Test fun textRules() {
        assertTrue(ConfirmBridge.validText("Garagentor öffnen?"))
        assertTrue(ConfirmBridge.validText("Zeile 1\nZeile 2"))
        assertFalse(ConfirmBridge.validText(""))
        assertFalse(ConfirmBridge.validText("   "))
        assertFalse(ConfirmBridge.validText("x".repeat(ConfirmBridge.MAX_TEXT + 1)))
        assertFalse(ConfirmBridge.validText("Tor\r\nauf"))
        assertFalse(ConfirmBridge.validText("Tor\u0000auf"))
        assertFalse(ConfirmBridge.validText("Betrag ‮001‬"))   // Schreibrichtung umgedreht
        assertFalse(ConfirmBridge.validText("a⁧b"))
    }

    @Test fun challengeRules() {
        assertTrue(ConfirmBridge.validChallenge("q8Vt2mZ0x-_Kp4Ls"))          // base64url
        assertTrue(ConfirmBridge.validChallenge("q8Vt2mZ0x+/Kp4Ls=="))        // base64
        assertTrue(ConfirmBridge.validChallenge("0123456789abcdef0123456789abcdef"))
        assertFalse(ConfirmBridge.validChallenge("kurz"))
        assertFalse(ConfirmBridge.validChallenge("mit leerzeichen 12345"))
        assertFalse(ConfirmBridge.validChallenge("zeile\numbruch1234567"))
        assertFalse(ConfirmBridge.validChallenge("a".repeat(513)))
    }

    @Test fun keyIdAndFingerprint() {
        val spki = ByteArray(91) { it.toByte() }
        val id = DeviceKey.keyId(spki)
        assertEquals(32, id.length)
        assertEquals(id.take(16).uppercase().chunked(4).joinToString(" "), DeviceKey.fingerprint(spki))
    }
}
