package de.camperflower.homehyrax

import android.content.Context

/** Open-Source-Bausteine der App mit Lizenz (Anzeige unter Info -> Lizenzen). */
object Licenses {
    data class Entry(val name: String, val license: String, val text: String)

    /** Android-/Kotlin-Bibliotheken (alle Apache-2.0); Go-Module kommen aus assets/licenses_go.txt (tools/licenses.py). */
    private val android = listOf(
        "AndroidX (Core, Activity, Fragment, Lifecycle, WebKit, Biometric)" to "https://developer.android.com/jetpack/androidx",
        "Jetpack Compose + Material 3 + Material Icons" to "https://developer.android.com/jetpack/compose",
        "AndroidX Media3 (ExoPlayer, RTSP)" to "https://github.com/androidx/media",
        "Kotlin standard library + coroutines" to "https://kotlinlang.org",
        "ZXing (QR-Codes)" to "https://github.com/zxing/zxing",
        "ZXing Android Embedded" to "https://github.com/journeyapps/zxing-android-embedded",
    )

    fun all(ctx: Context): List<Entry> {
        val go = runCatching { ctx.assets.open("licenses_go.txt").bufferedReader().readText() }.getOrDefault("")
        val goEntries = go.split(Regex("(?m)^=== ")).filter { it.isNotBlank() }.map { block ->
            val head = block.substringBefore('\n')
            Entry(head.substringBefore('|').trim(), head.substringAfter('|', "").trim(), block.substringAfter('\n').trim())
        }
        val apache = goEntries.firstOrNull { it.license == "Apache-2.0" && "Apache License" in it.text }?.text.orEmpty()
        return android.map { (n, url) -> Entry(n, "Apache-2.0", "$url\n\n$apache") } + goEntries
    }
}
