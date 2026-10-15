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
        /** Taste an die Seite geben */
        fun medienAktion(name: String)
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
    private var spielt = false
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
                override fun onPlay() = aktion("play")
                override fun onPause() = aktion("pause")
                override fun onStop() = aktion("pause")
                override fun onSkipToPrevious() = aktion("previoustrack")
                override fun onSkipToNext() = aktion("nexttrack")
                override fun onRewind() = aktion("seekbackward")
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
        stille?.run { runCatching { stop() }; release() }
        stille = null
        c.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    fun metadaten(ctx: Context, t: String, i: String, a: String) {
        titel = t.take(MAX).ifBlank { appName }; interpret = i.take(MAX)
        session?.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, titel)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, interpret)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, a.take(MAX))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, -1)
            .apply { bild?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) } }
            .build())
        zeigen(ctx)
    }

    /** Zustand der Seite: "spielt"/"aufnahme" = laeuft, sonst angehalten. */
    fun zustand(ctx: Context, z: String) {
        spielt = z == "spielt" || z == "aufnahme"
        setzen()
        zeigen(ctx)
    }

    private fun setzen() {
        val s = session ?: return
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_REWIND or
                PlaybackState.ACTION_STOP)
            .setState(if (spielt) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
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

    fun aktion(name: String) { ziel?.get()?.medienAktion(name) }

    fun beenden() { ziel?.get()?.medienBeenden() }

    // ----------------------------------------------------------- Benachrichtigung (Uhr, Sperrbildschirm)

    private const val ID = 4711
    private const val KANAL = "medien"
    const val ACT_STOP = "stop"
    private const val MAX = 200

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
            else -> Medien.aktion(a)
        }
    }
}
