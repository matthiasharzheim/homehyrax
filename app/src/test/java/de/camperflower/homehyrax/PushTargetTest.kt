package de.camperflower.homehyrax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Antippen einer Benachrichtigung: welche Adresse der Eintrag laedt und welcher Eintrag am besten passt */
class PushTargetTest {
    private val entry = "https://192.168.178.20:8790/tasks?k=secret"

    @Test fun samePageKeepsEntryParameters() {
        assertEquals(entry, Push.startUrl(entry, "https://192.168.178.20:8790/tasks"))
        assertEquals(entry, Push.startUrl(entry, "https://192.168.178.20:8790/tasks/"))
    }

    @Test fun anchorIsAddedToEntryAddress() {
        assertEquals("$entry#task-12", Push.startUrl(entry, "https://192.168.178.20:8790/tasks#task-12"))
        // eigene Sprungmarke des Eintrags wird ersetzt, nicht verdoppelt
        assertEquals("$entry#task-12", Push.startUrl("$entry#old", "https://192.168.178.20:8790/tasks#task-12"))
    }

    @Test fun otherPageOrOwnParametersStayAsSent() {
        val other = "https://192.168.178.20:8790/secrets"
        assertEquals(other, Push.startUrl(entry, other))
        val own = "https://192.168.178.20:8790/tasks?id=3#x"
        assertEquals(own, Push.startUrl(entry, own))
    }

    @Test fun unparsableAddressStaysAsSent() {
        val odd = "https://192.168.178.20:8790/tasks#a|b"
        assertEquals(odd, Push.startUrl(entry, odd))
    }

    @Test fun pathScorePrefersMostSpecificEntry() {
        val url = "https://h:1/tasks#task-1"
        val root = Push.pathScore("https://h:1/?k=a", url)
        val tasks = Push.pathScore("https://h:1/tasks?k=b", url)
        assertTrue(tasks > root)
        assertTrue(root > 0)
        assertEquals(0, Push.pathScore("https://h:1/secrets", url))
        // nur ganze Abschnitte: /task passt nicht zu /tasks
        assertEquals(0, Push.pathScore("https://h:1/task", url))
        assertTrue(Push.pathScore("https://h:1/tasks", "https://h:1/tasks/sub") > 0)
    }
}
