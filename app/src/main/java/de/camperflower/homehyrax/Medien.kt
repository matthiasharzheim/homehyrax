package de.camperflower.homehyrax

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import java.lang.ref.WeakReference

/**
 * Medien-Bruecke fuer eine Web-App (Schalter "Medientasten" im Editor): Die WebView kennt die Media Session API
 * nicht, deshalb meldet die App eine eigene Android-Mediensitzung. Lenkradtasten (Bluetooth), Uhr und Auto-Display
 * sehen Titel/Zustand der Seite, Tasten gehen als play/pause/previoustrack/nexttrack/seekbackward an die Seite.
 * Bewusst ohne Vordergrund-Dienst (der braeuchte eine eigene Play-Erklaerung): gedacht fuer die sichtbare Seite,
 * z. B. Handy in der Halterung. Immer nur ein Eintrag gleichzeitig.
 */
object Medien {

    /** Gegenstelle in der WebAppActivity. */
    interface Ziel {
        /** Taste an die Seite geben; quelle = woher sie kam (fuer das Protokoll der Seite) */
        fun medienAktion(name: String, quelle: String)
        /** Diagnose an die Seite (window.homeHyraxMedienProtokoll), z. B. unbekannte Tastencodes */
        fun medienProtokoll(text: String)
        /** "Beenden" in der Benachrichtigung: Seite schliessen */
        fun medienBeenden()
    }

    private var session: MediaSession? = null
    private var ziel: WeakReference<Ziel>? = null
    private var appId: String? = null
    private var appName = ""
    private var oeffnen: Intent? = null
    private var titel = ""
    private var interpret = ""
    private var album = ""
    private var bildKennung = 0
    private var spielt = false
    /** Diagnose fuers Menue: letzte angekommene Taste ("taste 87 nexttrack 13:31") */
    @Volatile private var letzteTaste: String? = null
    @Volatile private var letzteTasteFuer: String? = null

    /** letzte Taste nur fuer genau diesen Eintrag (nicht die eines vorher offenen anderen) */
    fun letzteTaste(id: String) = letzteTaste.takeIf { letzteTasteFuer == id }
    /** Sperrbildschirm zeigt Titel nicht (Eintrag mit Sperre) */
    private var privat = false
    /** Bild fuer Auto-Display/Uhr: Symbol des Eintrags (eigenes Bild oder Logo) */
    private var bild: Bitmap? = null
    private var stille: AudioTrack? = null

    fun aktiv(id: String) = appId == id && session != null

    /** Sitzung starten (aus der sichtbaren Activity, beim ersten Aufruf der Seite). */
    fun start(ctx: Context, app: WebApp, z: Ziel) {
        val c = ctx.applicationContext
        if (appId != app.id) stopNow(c)
        ziel = WeakReference(z)
        appId = app.id; appName = app.name; privat = app.requireAuth
        bild = runCatching { Shortcuts.roundIcon(c, app, 512) }.getOrNull()
        oeffnen = Shortcuts.launchIntent(c, app)
        if (session != null) return
        session = MediaSession(c, "HomeHyraxMedien").apply {
            setCallback(object : MediaSession.Callback() {
                // Steuerung ueber MediaController (Uhr, Benachrichtigung, manche Autos)
                // Play/Pause immer als Umschalter nach dem echten Zustand der Seite (das Auto sieht dauerhaft "spielt")
                override fun onPlay() = aktion(umschalten(), "play")
                override fun onPause() = aktion(umschalten(), "pause")
                override fun onStop() = aktion("pause", "stop")
                override fun onSkipToPrevious() = aktion("previoustrack", "skipToPrevious")
                override fun onSkipToNext() = aktion("nexttrack", "skipToNext")
                override fun onFastForward() = aktion("nexttrack", "fastForward")
                override fun onRewind() = aktion("seekbackward", "rewind")

                // Tasten (Bluetooth/AVRCP, Lenkrad, Kopfhoerer) selbst auswerten: je nach Auto/Handy kommt "Weiter"
                // auch als Vorspulen oder SKIP/STEP_FORWARD, das die Standard-Auswertung still verwirft
                override fun onMediaButtonEvent(i: Intent): Boolean {
                    val k = IntentCompat.getParcelableExtra(i, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        ?: return super.onMediaButtonEvent(i)
                    val name = when (k.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "nexttrack"
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                        KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> "previoustrack"
                        KeyEvent.KEYCODE_MEDIA_REWIND -> "seekbackward"
                        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_HEADSETHOOK -> umschalten()
                        KeyEvent.KEYCODE_MEDIA_STOP -> "pause"
                        else -> null
                    }
                    if (k.action != KeyEvent.ACTION_DOWN || k.repeatCount > 0) return name != null
                    if (name == null) { protokoll("taste ${k.keyCode} unbekannt"); return super.onMediaButtonEvent(i) }
                    aktion(name, "taste ${k.keyCode}")
                    return true
                }
            })
            oeffnen?.let { setSessionActivity(PendingIntent.getActivity(c, 0, it, PendingIntent.FLAG_IMMUTABLE)) }
            isActive = true
        }
        metadaten(c, app.name, "", "")
        setzen()
        stilleStarten()
    }

    /** Sitzung beenden, wenn sie zu diesem Eintrag gehoert (Activity zu). */
    fun stop(ctx: Context, id: String) { if (appId == id) stopNow(ctx.applicationContext) }

    private fun stopNow(c: Context) {
        session?.run { isActive = false; release() }
        session = null; appId = null; ziel = null; spielt = false; bild = null
        titel = ""; interpret = ""; album = ""; bildKennung = 0
        stille?.run { runCatching { stop() }; release() }
        stille = null
        c.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    fun metadaten(ctx: Context, t: String, i: String, a: String) {
        val nt = t.take(MAX).ifBlank { appName }; val ni = i.take(MAX); val na = a.take(MAX)
        // Seite meldet oft (jede Statusaenderung): Gleiches nicht erneut ans Auto/die Uhr schicken
        if (nt == titel && ni == interpret && na == album) return
        titel = nt; interpret = ni; album = na
        senden(ctx)
    }

    /**
     * Bild der Seite fuer Auto-Display/Uhr (statt des Symbols des Eintrags): nur "data:image/png|jpeg|webp;base64,…",
     * hoechstens 512 KB und 2048 px, wird auf 512 px verkleinert. Ungueltiges wird still verworfen.
     */
    fun bild(ctx: Context, dataUrl: String) {
        if (session == null || dataUrl.length > MAX_BILD_ZEICHEN) return
        // Seite schickt dasselbe Bild oft erneut: nicht jedes Mal dekodieren und ans Auto senden
        val kennung = dataUrl.hashCode() * 31 + dataUrl.length
        if (kennung == bildKennung) return
        val m = Regex("^data:image/(png|jpeg|webp);base64,").find(dataUrl) ?: return
        // alles abgefangen (auch OutOfMemoryError): ein kaputtes Bild darf die Seite nicht beenden
        val neu = runCatching {
            val roh = android.util.Base64.decode(dataUrl.substring(m.range.last + 1), android.util.Base64.DEFAULT)
            if (roh.size !in 1..MAX_BILD_BYTES) return@runCatching null
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(roh, 0, roh.size, o)
            if (o.outWidth !in 1..MAX_BILD_PX || o.outHeight !in 1..MAX_BILD_PX) return@runCatching null
            // gleich verkleinert dekodieren (Zweierpotenz, Ergebnis bleibt >= 512 px)
            var probe = 1
            while (maxOf(o.outWidth, o.outHeight) / (probe * 2) >= BILD_PX) probe *= 2
            var bm = android.graphics.BitmapFactory.decodeByteArray(roh, 0, roh.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = probe }) ?: return@runCatching null
            val gross = maxOf(bm.width, bm.height)
            if (gross > BILD_PX) bm = Bitmap.createScaledBitmap(bm,
                maxOf(1, bm.width * BILD_PX / gross), maxOf(1, bm.height * BILD_PX / gross), true)
            bm
        }.getOrNull() ?: return
        bildKennung = kennung
        bild = neu
        senden(ctx)
    }

    private fun senden(ctx: Context) {
        session?.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, titel)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, interpret)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, titel)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, interpret)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, -1)
            .apply {
                bild?.let {
                    putBitmap(MediaMetadata.METADATA_KEY_ART, it)
                    putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                }
            }
            .build())
        zeigen(ctx)
    }

    /** Zustand der Seite: "spielt"/"aufnahme" = laeuft, sonst angehalten. */
    fun zustand(ctx: Context, z: String) {
        val neu = z == "spielt" || z == "aufnahme"
        if (neu == spielt) return
        spielt = neu
        setzen()
        zeigen(ctx)
    }

    private fun setzen() {
        val s = session ?: return
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_STOP)
            // immer "spielt" (es laeuft ja die stumme Wiedergabe): viele Autos bieten Weiter/Zurueck nur dann an
            // und schicken sonst nur Play. Den echten Zustand der Seite kennt die App selbst (umschalten())
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build())
    }

    /**
     * Android leitet Medientasten an die App, die zuletzt Ton abgespielt hat (nicht nach PlaybackState). Die Seite
     * spielt oft nichts (wartet, nimmt auf) - dann bekaeme Spotify/das Navi die Lenkradtasten. Deshalb laeuft,
     * solange die Bruecke an ist, eine stumme Wiedergabe ohne Audiofokus (stoert andere Apps nicht).
     */
    private fun stilleStarten() {
        if (stille != null) return
        stille = runCatching {
            val rate = 8000
            val frames = rate / 2
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build().apply {
                    write(ShortArray(frames), 0, frames)
                    setLoopPoints(0, frames, -1)
                    play()
                }
        }.getOrNull()
    }

    private fun umschalten() = if (spielt) "pause" else "play"

    fun aktion(name: String, quelle: String) {
        letzteTaste = "$quelle $name " + uhrzeit(); letzteTasteFuer = appId
        ziel?.get()?.medienAktion(name, quelle)
    }

    private fun uhrzeit() = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())

    private fun protokoll(text: String) {
        letzteTaste = text + " " + uhrzeit(); letzteTasteFuer = appId
        ziel?.get()?.medienProtokoll(text)
    }

    fun beenden() { ziel?.get()?.medienBeenden() }

    // ----------------------------------------------------------- Benachrichtigung (Uhr, Sperrbildschirm)

    private const val ID = 4711
    private const val KANAL = "medien"
    const val ACT_STOP = "stop"
    private const val MAX = 200
    private const val BILD_PX = 512
    private const val MAX_BILD_PX = 2048
    private const val MAX_BILD_BYTES = 512 * 1024
    /** 512 KB als Base64 plus Kopf */
    private const val MAX_BILD_ZEICHEN = 700_100

    private fun taste(c: Context, icon: Int, text: Int, action: String, req: Int) =
        Notification.Action.Builder(Icon.createWithResource(c, icon), c.getString(text),
            PendingIntent.getBroadcast(c, req, Intent(c, MedienTasten::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE)).build()

    // MediaStyle mit Mediensitzung ist ab Android 13 von POST_NOTIFICATIONS ausgenommen
    @SuppressLint("NotificationPermission")
    private fun zeigen(ctx: Context) {
        val tok = session?.sessionToken ?: return
        val c = ctx.applicationContext
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(KANAL) == null)
            nm.createNotificationChannel(NotificationChannel(KANAL, c.getString(R.string.media_channel),
                NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val b = Notification.Builder(c, KANAL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(titel)
            .setContentText(interpret)
            .apply { bild?.let { setLargeIcon(it) } }
            .setOngoing(true)
            // gesperrter Eintrag: auf dem Sperrbildschirm nur "HomeHyrax", kein Titel der Seite
            .setVisibility(if (privat) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(taste(c, android.R.drawable.ic_media_previous, R.string.media_previous, "previoustrack", 1))
            .addAction(if (spielt) taste(c, android.R.drawable.ic_media_pause, R.string.media_pause, "pause", 2)
                else taste(c, android.R.drawable.ic_media_play, R.string.media_play, "play", 2))
            .addAction(taste(c, android.R.drawable.ic_media_next, R.string.media_next, "nexttrack", 3))
            .addAction(taste(c, android.R.drawable.ic_menu_close_clear_cancel, R.string.media_stop, ACT_STOP, 4))
            .setStyle(Notification.MediaStyle().setMediaSession(tok).setShowActionsInCompactView(0, 1, 2))
        oeffnen?.let { b.setContentIntent(PendingIntent.getActivity(c, 0, it, PendingIntent.FLAG_IMMUTABLE)) }
        runCatching { nm.notify(ID, b.build()) }
    }
}

/** Tasten der Medien-Benachrichtigung. */
class MedienTasten : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val a = intent.action) {
            null -> Unit
            Medien.ACT_STOP -> Medien.beenden()
            else -> Medien.aktion(a, "benachrichtigung")
        }
    }
}
