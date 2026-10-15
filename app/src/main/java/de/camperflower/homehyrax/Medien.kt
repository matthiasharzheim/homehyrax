package de.camperflower.homehyrax

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.lang.ref.WeakReference

/**
 * Medien-Bruecke fuer eine Web-App (Schalter "Medientasten" im Editor): Die WebView kennt die Media Session API
 * nicht, deshalb meldet die App eine eigene Android-Mediensitzung. Lenkradtasten (Bluetooth), Uhr und Auto-Display
 * sehen Titel/Zustand der Seite, Tasten gehen als play/pause/previoustrack/nexttrack/seekbackward an die Seite.
 * Solange die Sitzung laeuft, haelt ein Vordergrund-Dienst (Benachrichtigung mit Tasten) Prozess, Tunnel und
 * Mikrofon auch bei ausgeschaltetem Bildschirm am Leben. Immer nur ein Eintrag gleichzeitig.
 */
object Medien {

    /** Gegenstelle in der WebAppActivity. */
    interface Ziel {
        /** Taste an die Seite geben */
        fun medienAktion(name: String)
        /** "Beenden" in der Benachrichtigung: Seite schliessen */
        fun medienBeenden()
        /** Sitzung wurde von einem anderen Eintrag uebernommen: Tunnel nicht mehr im Hintergrund halten */
        fun medienGestoppt()
    }

    private var session: MediaSession? = null
    private var ziel: WeakReference<Ziel>? = null
    var appId: String? = null; private set
    private var appName = ""
    private var oeffnen: Intent? = null
    var titel = ""; private set
    var interpret = ""; private set
    var spielt = false; private set
    /** Sperrbildschirm zeigt Titel nicht (Eintrag mit Sperre) */
    var privat = false; private set
    private var mitMikrofon = false
    /** Bild fuer Auto-Display/Uhr: Symbol des Eintrags (eigenes Bild oder Logo) */
    private var bild: Bitmap? = null
    private var stille: AudioTrack? = null

    fun aktiv(id: String) = appId == id && session != null

    /** Sitzung + Vordergrund-Dienst starten (nur aus der sichtbaren Activity, sonst verbietet Android den Dienst). */
    fun start(ctx: Context, app: WebApp, z: Ziel) {
        val c = ctx.applicationContext
        if (appId != app.id) stopNow(c)
        ziel = WeakReference(z)
        appId = app.id; appName = app.name; privat = app.requireAuth
        bild = runCatching { Shortcuts.roundIcon(c, app, 512) }.getOrNull()
        oeffnen = Shortcuts.launchIntent(c, app)
        mitMikrofon = mikrofonErlaubt(c, app)
        if (session == null) {
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
        runCatching { c.startForegroundService(Intent(c, MedienDienst::class.java)) }
    }

    /**
     * Mikrofon wurde fuer den Eintrag erst nach dem Start erlaubt: Dienst mit Mikrofon-Typ neu melden, sonst schaltet
     * Android die Aufnahme im Hintergrund stumm. Nur aus der sichtbaren Activity.
     */
    fun mikrofonNeu(ctx: Context, app: WebApp) {
        val c = ctx.applicationContext
        if (!aktiv(app.id) || mitMikrofon || !mikrofonErlaubt(c, app)) return
        mitMikrofon = true
        runCatching { c.startForegroundService(Intent(c, MedienDienst::class.java)) }
    }

    private fun mikrofonErlaubt(c: Context, app: WebApp) =
        app.mic && c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Sitzung beenden, wenn sie zu diesem Eintrag gehoert (Activity zu). */
    fun stop(ctx: Context, id: String) { if (appId == id) stopNow(ctx.applicationContext) }

    private fun stopNow(c: Context) {
        if (appId == null && session == null) return
        val alt = ziel?.get()
        session?.run { isActive = false; release() }
        session = null; appId = null; ziel = null; spielt = false; bild = null; mitMikrofon = false
        stille?.run { runCatching { stop() }; release() }
        stille = null
        c.getSystemService(NotificationManager::class.java).cancel(MedienDienst.ID)
        // ueber den Dienst beenden: so laeuft sein startForeground sicher vorher (sonst Absturz auf Android 8-11)
        runCatching { c.startService(Intent(c, MedienDienst::class.java).setAction(MedienDienst.ACT_ENDE)) }
            .onFailure { c.stopService(Intent(c, MedienDienst::class.java)) }
        alt?.medienGestoppt()
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
        MedienDienst.aktualisieren(ctx)
    }

    /** Zustand der Seite: "spielt"/"aufnahme" = laeuft, sonst angehalten. */
    fun zustand(ctx: Context, z: String) {
        spielt = z == "spielt" || z == "aufnahme"
        setzen()
        MedienDienst.aktualisieren(ctx)
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

    internal fun token() = session?.sessionToken
    internal fun oeffnenIntent() = oeffnen
    internal fun mikrofon() = mitMikrofon
    internal fun bildFuerBenachrichtigung() = bild

    private const val MAX = 200
}

/** Vordergrund-Dienst der Medien-Bruecke: Benachrichtigung mit Tasten, haelt Prozess und CPU wach. */
class MedienDienst : Service() {

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val a = intent?.action) {
            null -> Unit
            // stopSelf(startId): ein gleich danach folgender Neustart (anderer Eintrag) bleibt erhalten
            ACT_ENDE -> { vordergrund = false; stopSelf(startId); return START_NOT_STICKY }
            ACT_STOP -> { Medien.beenden(); return START_NOT_STICKY }
            else -> { Medien.aktion(a); return START_NOT_STICKY }
        }
        // erst in den Vordergrund (Pflicht nach startForegroundService), dann ggf. gleich wieder beenden
        val n = baue(this)
        val ok = if (Build.VERSION.SDK_INT >= 29) {
            var typ = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (Medien.mikrofon() && Build.VERSION.SDK_INT >= 30) typ = typ or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            runCatching { startForeground(ID, n, typ) }.isSuccess ||
                // Mikrofon-Typ abgelehnt (z. B. Berechtigung entzogen): wenigstens Wiedergabe
                runCatching { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }.isSuccess
        } else runCatching { startForeground(ID, n) }.isSuccess
        if (!ok || Medien.appId == null) { vordergrund = false; stopSelf(startId); return START_NOT_STICKY }
        vordergrund = true
        if (wake == null) {
            // Seite fragt im Hintergrund nach Antworten: CPU wach halten, solange die Bruecke laeuft
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeHyrax:Medien").apply { setReferenceCounted(false); acquire(MAX_WAKE_MS) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        vordergrund = false
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        const val ID = 4711
        const val ACT_ENDE = "ende"
        private const val KANAL = "medien"
        private const val ACT_STOP = "stop"
        /** Sicherung, falls die Activity nie beendet wird */
        private const val MAX_WAKE_MS = 6 * 60 * 60 * 1000L
        /** Benachrichtigung nur aktualisieren, solange der Dienst im Vordergrund laeuft (sonst bliebe sie verwaist) */
        @Volatile private var vordergrund = false

        // MediaStyle mit Mediensitzung ist ab Android 13 von POST_NOTIFICATIONS ausgenommen
        @SuppressLint("NotificationPermission")
        fun aktualisieren(ctx: Context) {
            if (Medien.appId == null || !vordergrund) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            runCatching { nm.notify(ID, baue(ctx.applicationContext)) }
        }

        private fun taste(ctx: Context, icon: Int, text: Int, action: String, req: Int) =
            Notification.Action.Builder(Icon.createWithResource(ctx, icon), ctx.getString(text),
                PendingIntent.getService(ctx, req, Intent(ctx, MedienDienst::class.java).setAction(action),
                    PendingIntent.FLAG_IMMUTABLE)).build()

        private fun baue(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(KANAL) == null)
                nm.createNotificationChannel(NotificationChannel(KANAL, ctx.getString(R.string.media_channel),
                    NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            val b = Notification.Builder(ctx, KANAL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(Medien.titel)
                .setContentText(Medien.interpret)
                .apply { Medien.bildFuerBenachrichtigung()?.let { setLargeIcon(it) } }
                .setOngoing(true)
                // gesperrter Eintrag: auf dem Sperrbildschirm nur "HomeHyrax", kein Titel der Seite
                .setVisibility(if (Medien.privat) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(taste(ctx, android.R.drawable.ic_media_previous, R.string.media_previous, "previoustrack", 1))
                .addAction(if (Medien.spielt) taste(ctx, android.R.drawable.ic_media_pause, R.string.media_pause, "pause", 2)
                    else taste(ctx, android.R.drawable.ic_media_play, R.string.media_play, "play", 2))
                .addAction(taste(ctx, android.R.drawable.ic_media_next, R.string.media_next, "nexttrack", 3))
                .addAction(taste(ctx, android.R.drawable.ic_menu_close_clear_cancel, R.string.media_stop, ACT_STOP, 4))
            Medien.oeffnenIntent()?.let { b.setContentIntent(PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE)) }
            val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
            Medien.token()?.let { style.setMediaSession(it) }
            return b.setStyle(style).build()
        }
    }
}
