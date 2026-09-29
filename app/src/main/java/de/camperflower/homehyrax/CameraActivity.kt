package de.camperflower.homehyrax

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Kamera (RTSP-Livebild): zuhause direkt, unterwegs per Tunnel. Der Player spricht keinen
 * HTTP-Proxy, deshalb laeuft der Stream ueber eine lokale Portweiterleitung im Go-Kern
 * (CameraStream -> Wgbridge.forward -> Wegweiser Route/AllowedIPs), RTP immer ueber TCP (interleaved).
 * Stream nur solange sichtbar; beim Zurueckkehren neu aufbauen.
 */
@OptIn(UnstableApi::class)
class CameraActivity : FragmentActivity() {

    private sealed interface Phase {
        data class Connecting(val text: String) : Phase
        data object Ready : Phase
        data class Failed(val text: String) : Phase
    }

    private var phase by mutableStateOf<Phase>(Phase.Connecting("..."))
    private var viaTunnel by mutableStateOf(false)
    private var showBadge by mutableStateOf(false)
    private var muted by mutableStateOf(true)
    private var locked by mutableStateOf(false)
    private var lockError by mutableStateOf<String?>(null)

    private lateinit var app: WebApp
    private var tunnel: Tunnel? = null
    private lateinit var unlocker: Unlocker
    private lateinit var playerView: PlayerView
    private var stream: CameraStream.Handle? = null
    private val player: ExoPlayer? get() = stream?.player
    private var job: Job? = null
    private var snapJob: Job? = null
    private var holdingTunnel = false
    private var fallbackTried = false
    private var asking = false
    private var hiddenAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        unlocker = Unlocker(this)
        val a = intent.data?.lastPathSegment?.let { store.app(it) }
        if (a == null || !a.isCamera) {
            Toast.makeText(this, getString(R.string.cam_gone), Toast.LENGTH_LONG).show()
            finishAndRemoveTask(); return
        }
        app = a
        tunnel = store.tunnel(app.tunnelId)
        setTaskDescription(ActivityManager.TaskDescription(app.name, Shortcuts.roundIcon(this, app)))
        if (app.requireAuth) {
            // gesperrte Kamera: keine Vorschau in "Zuletzt verwendet" (vor Android 13 nur per FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
            else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            // und kein Kachelbild auf der Startseite
            runCatching { store.snapshotFile(app.id).delete() }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        playerView = PlayerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            keepScreenOn = true
        }
        locked = app.requireAuth
        setContent { Screen() }
    }

    /**
     * Kachel/Symbol erneut angetippt, waehrend die Kamera schon laeuft (documentLaunchMode
     * "intoExisting"): bei geaendertem Eintrag (Adresse, VPN, Sperre ...) mit den neuen Werten neu starten.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::app.isInitialized) return
        val fresh = intent.data?.lastPathSegment?.let { store.app(it) }
        when {
            fresh == null || !fresh.isCamera -> { Toast.makeText(this, getString(R.string.cam_gone), Toast.LENGTH_LONG).show(); finishAndRemoveTask() }
            fresh != app -> recreate()
        }
    }

    override fun onStart() {
        super.onStart()
        // zurueck (z. B. aus "Einstellungen"): Eintrag geaendert -> mit den neuen Werten neu starten
        if (hiddenAt > 0 && !asking) {
            val fresh = store.app(app.id)
            if (fresh == null || !fresh.isCamera) { finishAndRemoveTask(); return }
            if (fresh != app) { recreate(); return }
        }
        // nach mehr als 1 min im Hintergrund erneut sperren
        if (app.requireAuth && !locked && hiddenAt > 0 && SystemClock.elapsedRealtime() - hiddenAt > RELOCK_MS) locked = true
        if (locked) { if (!asking) unlock() } else start()
    }

    override fun onStop() {
        super.onStop()
        if (asking) return   // Android < 9: Sperrbildschirm-Abfrage verdeckt die Activity kurz
        hiddenAt = SystemClock.elapsedRealtime()
        stopStream()
        releaseTunnel()
    }

    override fun onDestroy() {
        if (::playerView.isInitialized) stopStream()
        releaseTunnel()
        super.onDestroy()
    }

    private fun releaseTunnel() {
        tunnel?.let { if (holdingTunnel) { Net.release(it.id); holdingTunnel = false } }
    }

    private fun unlock() {
        lockError = null
        asking = true
        unlocker.ask(app.name, getString(R.string.web_unlock_confirm)) { ok, err ->
            asking = false
            when {
                ok -> { locked = false; start() }
                err == null -> finishAndRemoveTask()   // abgebrochen
                else -> lockError = err
            }
        }
    }

    // ---------------------------------------------------------------- Ablauf

    private fun start() {
        fallbackTried = false
        job?.cancel()
        job = lifecycleScope.launch {
            phase = Phase.Connecting(getString(R.string.cam_connecting))
            val t = tunnel
            val where = Net.where(app)
            if (t == null || where.home) {
                Net.route(app, null); viaTunnel = false; play()
            } else if (!useTunnel(t) && where.skipped && Net.reachableDirect(app)) {
                // unbekanntes WLAN, Tunnel geht nicht -> doch direkt (neuer Router o. ae.)
                Net.route(app, null); viaTunnel = false; play()
            }
        }
    }

    /** Tunnel aufbauen und abspielen; false = Tunnel ging nicht (Fehler wird angezeigt), kein Direkt-Rueckfall bei verdeckter Activity. */
    private suspend fun useTunnel(t: Tunnel): Boolean {
        phase = Phase.Connecting(getString(R.string.common_away_connecting, t.name))
        if (!holdingTunnel) { Net.acquire(t.id); holdingTunnel = true }
        val err = Net.connect(t)
        // inzwischen in den Hintergrund gewechselt: Tunnel nicht weiter festhalten, onStart baut neu auf
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { releaseTunnel(); return true }
        if (err != null) { fail(err); return false }
        Net.route(app, t.id)
        viaTunnel = true
        play()
        return true
    }

    private fun play() {
        stopPlayer()
        val h = runCatching { CameraStream.open(this, app) }
            .getOrElse { fail(getString(R.string.cam_no_video, app.name) + "\n" + (it.message ?: "")); return }
        val p = h.player
        p.volume = if (muted) 0f else 1f
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && phase !is Phase.Ready) { phase = Phase.Ready; flashBadge(); startSnapshots() }
            }

            // nicht im Listener selbst freigeben, sondern danach
            override fun onPlayerError(error: PlaybackException) { playerView.post { if (player === p) streamFailed(error) } }
        })
        playerView.player = p
        stream = h
    }

    private fun streamFailed(error: PlaybackException) {
        stopPlayer()
        val t = tunnel
        // Direktversuch gescheitert (z. B. fremdes WLAN mit gleicher IP) -> Tunnel
        if (!viaTunnel && t != null && !fallbackTried) {
            fallbackTried = true
            job?.cancel()
            job = lifecycleScope.launch { useTunnel(t) }
            return
        }
        fail(getString(R.string.cam_no_video, app.name) + "\n" + CameraStream.reason(this, error))
    }

    /** Fuer die grosse Kachel: kurz nach Bildbeginn und dann alle 20 s das aktuelle Bild merken. */
    private fun startSnapshots() {
        snapJob?.cancel()
        snapJob = lifecycleScope.launch {
            delay(1500)
            while (true) { snapshot(); delay(20_000) }
        }
    }

    private fun snapshot() {
        if (app.requireAuth) return   // gesperrte Kamera: kein Bild ausserhalb der Sperre
        val sv = playerView.videoSurfaceView as? SurfaceView ?: return
        val vs = player?.videoSize ?: return
        if (vs.width <= 0 || vs.height <= 0) return
        val w = minOf(960, vs.width)
        val h = (w.toLong() * vs.height / vs.width).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(sv, bmp, { r ->
                if (r == PixelCopy.SUCCESS) lifecycleScope.launch(Dispatchers.IO) { runCatching { store.saveSnapshot(app.id, bmp) } }
            }, Handler(Looper.getMainLooper()))
        }
    }

    private fun stopPlayer() {
        snapJob?.cancel(); snapJob = null
        if (::playerView.isInitialized) playerView.player = null
        stream?.release()
        stream = null
    }

    private fun stopStream() {
        job?.cancel(); job = null
        stopPlayer()
    }

    private fun fail(msg: String) { phase = Phase.Failed(msg) }

    private fun flashBadge() {
        lifecycleScope.launch { showBadge = true; delay(2500); showBadge = false }
    }

    // -------------------------------------------------------------------- UI

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())
            when (val p = phase) {
                is Phase.Connecting -> Cover { Busy(p.text) }
                is Phase.Failed -> Cover { Problem(p.text) }
                Phase.Ready -> {}
            }
            if (locked) Cover { Locked() }

            if (phase == Phase.Ready && !locked) IconButton(
                onClick = { muted = !muted; player?.volume = if (muted) 0f else 1f },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).clip(CircleShape).background(Color(0x99000000)),
            ) {
                Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    stringResource(R.string.cam_mute), tint = Color.White)
            }

            AnimatedVisibility(
                visible = showBadge && phase == Phase.Ready,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().displayCutoutPadding().padding(top = 12.dp),   // nicht unter dem Kamera-Loch
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(0xE6202830)).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (viaTunnel) Icons.Filled.Lock else Icons.Filled.Home, null, tint = Color(0xFFF2B544), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (viaTunnel) stringResource(R.string.web_badge_away, if (tunnel?.isTailscale == true) "Tailscale" else "WireGuard")
                        else stringResource(R.string.web_badge_home), color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }

    @Composable
    private fun Cover(content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF101418))
                // alle Beruehrungen abfangen, sonst erreichen sie das Bild dahinter
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val icon = remember { Shortcuts.roundIcon(this@CameraActivity, app, 256).asImageBitmap() }
            Image(icon, null, Modifier.size(88.dp))
            Spacer(Modifier.height(16.dp))
            Text(app.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(28.dp))
            content()
        }
    }

    @Composable
    private fun Busy(text: String) {
        CircularProgressIndicator(color = Color(0xFFF2B544))
        Spacer(Modifier.height(16.dp))
        Text(text, color = Color(0xFFB8C4CC), textAlign = TextAlign.Center)
    }

    @Composable
    private fun Locked() {
        Icon(Icons.Filled.Lock, null, tint = Color(0xFFF2B544), modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.web_locked), color = Color(0xFFE0E6EA))
        lockError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFFFF8A80), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = { unlock() }) { Text(stringResource(R.string.common_unlock)) }
    }

    @Composable
    private fun Problem(text: String) {
        Icon(Icons.Filled.VideocamOff, null, tint = Color(0xFFFF8A80), modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, color = Color(0xFFE0E6EA), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { start() }) { Text(stringResource(R.string.web_retry)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            startActivity(Intent(this@CameraActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_EDIT, app.id))
        }) { Text(stringResource(R.string.web_settings), color = Color.White) }
    }

    companion object { private const val RELOCK_MS = 60_000L }
}
