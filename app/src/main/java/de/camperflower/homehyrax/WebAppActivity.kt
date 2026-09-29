package de.camperflower.homehyrax

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import de.camperflower.homehyrax.wgbridge.Wgbridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest

/** Zeigt eine Web-App im Vollbild; entscheidet zuhause/unterwegs. */
class WebAppActivity : FragmentActivity() {

    private sealed interface Phase {
        data class Connecting(val text: String) : Phase
        data object Ready : Phase
        data class Failed(val text: String) : Phase
    }

    private var phase by mutableStateOf<Phase>(Phase.Connecting("..."))
    private var viaTunnel by mutableStateOf(false)
    private var showBadge by mutableStateOf(false)

    private lateinit var app: WebApp
    private var tunnel: Tunnel? = null
    private lateinit var web: WebView
    private var holdingTunnel = false
    /** Tunnel wird gerade aufgebaut oder genutzt (onStart haelt ihn dann wieder fest) */
    private var wantTunnel = false
    private var fallbackTried = false
    private var proxyAuthTries = 0
    private var loadedOnce = false
    private var awaitingLoad = false

    // optionale Sperre (Fingerabdruck/Gesicht/PIN)
    private lateinit var unlocker: Unlocker
    private var lockedState by mutableStateOf(false)
    /** Gesperrt: Seite unsichtbar und angehalten (kein Ton/Video, keine Eingaben dahinter). */
    private var locked: Boolean
        get() = lockedState
        set(v) {
            lockedState = v
            if (::web.isInitialized) {
                web.visibility = if (v) View.INVISIBLE else View.VISIBLE
                if (v) web.onPause() else web.onResume()
            }
        }
    private var lockError by mutableStateOf<String?>(null)
    private var startedOnce = false
    private var hiddenAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        unlocker = Unlocker(this)
        val id = intent.data?.lastPathSegment
        val a = id?.let { store.app(it) }
        if (a == null) {
            Toast.makeText(this, getString(R.string.web_gone), Toast.LENGTH_LONG).show()
            finishAndRemoveTask(); return
        }
        app = a
        tunnel = store.tunnel(app.tunnelId)
        setTaskDescription(ActivityManager.TaskDescription(app.name, Shortcuts.roundIcon(this, app)))
        if (app.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        web = createWebView()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finishAndRemoveTask()
            }
        })

        if (app.requireAuth) {
            // gesperrte Kachel: keine Vorschau in "Zuletzt verwendet" (vor Android 13 nur per FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
            else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        setContent { Screen() }
        if (app.requireAuth) { locked = true; unlock() }
        else { startedOnce = true; lifecycleScope.launch { start() } }
    }

    /**
     * Kachel/Symbol erneut angetippt, waehrend die Web-App schon laeuft (documentLaunchMode
     * "intoExisting"): Wurde der Eintrag inzwischen im Editor geaendert (Adresse, VPN, Sperre ...),
     * mit den neuen Werten neu starten, sonst einfach weiter anzeigen.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::app.isInitialized) return
        val fresh = intent.data?.lastPathSegment?.let { store.app(it) }
        when {
            fresh == null -> { Toast.makeText(this, getString(R.string.web_gone), Toast.LENGTH_LONG).show(); finishAndRemoveTask() }
            fresh != app -> recreate()
        }
    }

    /** Sperre aufheben; beim ersten Mal danach verbinden + laden. */
    private fun unlock() {
        lockError = null
        unlocker.ask(app.name, getString(R.string.web_unlock_confirm)) { ok, err ->
            when {
                ok -> {
                    locked = false
                    Net.webAppVisible(true, app)   // erst entsperrt: Ziel fuer den Proxy-Ausweichmodus freigeben
                    if (!startedOnce) { startedOnce = true; lifecycleScope.launch { start() } }
                }
                err == null -> finishAndRemoveTask()   // abgebrochen
                else -> lockError = err
            }
        }
    }

    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (app.fullscreen) {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                // Statusleiste sichtbar: helle Symbole auf dunklem Rand, Inhalt darunter (systemBarsPadding)
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    // ---------------------------------------------------------------- Ablauf

    private suspend fun start() {
        phase = Phase.Connecting(getString(R.string.web_connecting_to, app.name))
        val proxyOk = runCatching { Net.ensureProxy() }.getOrDefault(false)
        val t = tunnel
        if (t != null && !proxyOk) {
            fail(getString(R.string.web_webview_too_old))
            return
        }
        val where = Net.where(app)
        skippedProbe = where.skipped
        if (t == null || where.home) {
            wantTunnel = false
            Net.route(app, null)
            viaTunnel = false
            load()
        } else {
            useTunnel(t)
        }
    }

    /** true = in diesem WLAN gar nicht erst direkt versucht (unbekanntes WLAN) */
    private var skippedProbe = false

    private suspend fun useTunnel(t: Tunnel) {
        phase = Phase.Connecting(getString(R.string.common_away_connecting, t.name))
        wantTunnel = true
        holdTunnel(t)
        val err = Net.connect(t)
        // inzwischen in den Hintergrund gewechselt: nicht weiter festhalten (onStart holt ihn wieder)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) releaseTunnel()
        if (err != null) {
            // Unbekanntes WLAN uebersprungen, Tunnel geht nicht -> doch direkt versuchen (neuer Router o. ae.)
            if (skippedProbe && Net.reachableDirect(app)) {
                skippedProbe = false
                wantTunnel = false; releaseTunnel()
                Net.route(app, null); viaTunnel = false; load(); return
            }
            fail(err); return
        }
        Net.route(app, t.id)
        viaTunnel = true
        load()
    }

    private fun holdTunnel(t: Tunnel) {
        if (!holdingTunnel) { Net.acquire(t.id); holdingTunnel = true }
    }

    private fun releaseTunnel() {
        tunnel?.let { if (holdingTunnel) { Net.release(it.id); holdingTunnel = false } }
    }

    private fun load() {
        phase = Phase.Connecting(getString(if (viaTunnel) R.string.web_loading_tunnel else R.string.web_loading))
        awaitingLoad = true
        if (loadedOnce) web.reload() else web.loadUrl(app.url)
        loadedOnce = true
    }

    private fun fail(msg: String) { phase = Phase.Failed(msg) }

    private fun retry() {
        fallbackTried = false
        proxyAuthTries = 0
        lifecycleScope.launch { start() }
    }

    override fun onStart() {
        super.onStart()
        // Gegenstueck in onStop (auch bei recreate/finish unten); gesperrt: kein Ziel fuer den Ausweichmodus
        Net.webAppVisible(true, if (locked) null else app)
        // zurueck (z. B. aus "Einstellungen"): Eintrag geaendert -> mit den neuen Werten neu starten
        if (hiddenAt > 0) {
            val fresh = store.app(app.id)
            if (fresh == null) { finishAndRemoveTask(); return }
            if (fresh != app) { recreate(); return }
        }
        // nach mehr als 1 min im Hintergrund erneut sperren
        if (app.requireAuth && !locked && hiddenAt > 0 &&
            android.os.SystemClock.elapsedRealtime() - hiddenAt > RELOCK_MS) {
            locked = true
            unlock()
        }
        // Routen sind global je Host: eine andere Kachel/Kamera kann sie inzwischen umgestellt haben
        if (loadedOnce) Net.route(app, if (viaTunnel) tunnel?.id else null)
        val t = tunnel ?: return
        if ((viaTunnel || wantTunnel) && !holdingTunnel) {
            holdTunnel(t)
            // Tunnel wurde im Hintergrund abgebaut -> leise neu aufbauen
            if (viaTunnel && !Net.isRunning(t.id)) lifecycleScope.launch {
                Net.connect(t)?.let { fail(it) } ?: web.reload()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Net.webAppVisible(false)
        hiddenAt = android.os.SystemClock.elapsedRealtime()
        releaseTunnel()
    }

    override fun onDestroy() {
        releaseTunnel()   // falls ein Verbindungsaufbau erst nach onStop fertig wurde
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    // --------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = WebView(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false
        if (app.desktop) {
            // Desktop-Webseite: Desktop-User-Agent (gleiche Chrome-Version), "Mobile"/Android-Kennung weg
            val chrome = Regex("Chrome/[\\d.]+").find(settings.userAgentString)?.value ?: "Chrome/130.0.0.0"
            settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        }
        webChromeClient = WebChromeClient()
        webViewClient = Client()
    }

    private inner class Client : WebViewClient() {

        override fun onPageFinished(view: WebView, url: String?) {
            // Desktop: mobiles Viewport-Tag ueberschreiben -> Seite wird in 1200 px Breite gelayoutet und eingepasst
            if (app.desktop) view.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
            if (awaitingLoad && phase is Phase.Connecting) {
                awaitingLoad = false
                phase = Phase.Ready
                flashBadge()
            }
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
            // Anmeldung am eigenen lokalen Proxy: automatisch, aber nur fuer 127.0.0.1 und - wenn der
            // WebView den Port mitliefert - nur fuer den eigenen Proxy-Port. Leerer Host zaehlt nicht als lokal.
            val h = host.orEmpty()
            val port = h.substringAfter(':', "")
            val local = h.substringBefore(':') == "127.0.0.1" && (port.isEmpty() || port == Net.proxyPort.toString()) &&
                !Hosts.isForbidden(app.host)
            if (realm == Wgbridge.ProxyRealm && local) {
                if (++proxyAuthTries > 5) { handler.cancel(); fail(getString(R.string.web_proxy_rejects)); return }
                handler.proceed(Wgbridge.ProxyUser, Net.proxySecret)
                return
            }
            askLogin(host ?: app.host, realm, handler)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val fp = fingerprint(error.certificate)
            val host = Uri.parse(error.url).host.orEmpty().lowercase()
            if (fp != null && certPins()[host] == fp) { handler.proceed(); return }
            if (isFinishing || isDestroyed) { handler.cancel(); return }
            // Dauerhaftes Vertrauen nur fuer Geraete im Heimnetz (eigene Zertifikate von Router/NAS),
            // nie fuer Adressen im Internet - dort waere ein falsches Zertifikat ein Angriff
            if (fp == null || !Hosts.isPrivate(host)) {
                handler.cancel()
                if (isAppHost(error.url)) fail(getString(R.string.web_cert_rejected, host, certProblem(error)))
                return
            }
            android.app.AlertDialog.Builder(this@WebAppActivity)
                .setTitle(getString(R.string.web_cert_title))
                .setMessage(getString(R.string.web_cert_message, host) + "\n\n" +
                    getString(R.string.web_cert_details, certProblem(error), fp.chunked(2).joinToString(":").uppercase()))
                .setPositiveButton(getString(R.string.web_cert_trust)) { _, _ ->
                    // auf der aktuell gespeicherten Fassung aufsetzen (evtl. inzwischen im Editor geaendert)
                    val cur = store.app(app.id) ?: app
                    val pins = certPins(cur) + (host to fp)
                    val saved = cur.copy(trustedCert = pins.entries.joinToString(",") { "${it.key}=${it.value}" })
                    // Speichern kann am Keystore scheitern: dann gilt das Vertrauen nur fuer diese Sitzung
                    if (runCatching { store.saveApp(saved) }.isSuccess) app = app.copy(trustedCert = saved.trustedCert)
                    handler.proceed()
                }
                .setNegativeButton(getString(R.string.common_cancel)) { _, _ -> handler.cancel(); fail(getString(R.string.web_cert_cancelled)) }
                .setCancelable(false)
                .show()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            awaitingLoad = false
            val t = tunnel
            // Direktversuch gescheitert (z. B. fremdes WLAN mit gleicher IP) -> Tunnel
            if (!viaTunnel && t != null && !fallbackTried) {
                fallbackTried = true
                lifecycleScope.launch { useTunnel(t) }
                return
            }
            fail(getString(R.string.web_no_answer, app.name) + "\n${error.description}")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            // Ausweichmodus nur bei 407 vom EIGENEN Proxy (Kennung) - eine 407 aus dem Heimnetz
            // darf den lokalen Proxy nicht passwortfrei schalten
            if (request.isForMainFrame && response.statusCode == 407 && Net.proxyAuthWorks &&
                Net.isOwnProxy407(response.responseHeaders)) {
                awaitingLoad = false
                lifecycleScope.launch { Net.disableProxyAuth(); load() }
                return
            }
            // Kopfzeile case-insensitiv pruefen: Go schreibt sie kanonisiert ("X-Homehyrax-Error").
            // TODO: auf die Konstante aus dem Go-Kern (voraussichtlich Wgbridge.ErrorHeader) umstellen
            if (request.isForMainFrame && response.responseHeaders?.keys?.any { it.equals(Wgbridge.ErrorHeader, ignoreCase = true) } == true) {
                awaitingLoad = false
                val t = tunnel
                if (!viaTunnel && t != null && !fallbackTried) {
                    fallbackTried = true
                    lifecycleScope.launch { useTunnel(t) }
                    return
                }
                val body = runCatching { response.data?.bufferedReader()?.readText() }.getOrNull()
                fail((getString(R.string.web_unreachable, app.name) + "\n${body ?: ""}").trim())
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            val isWeb = u.scheme in listOf("http", "https")
            val sameApp = isWeb && (u.host.equals(app.host, ignoreCase = true) || Hosts.isPrivate(u.host))
            if (sameApp || u.scheme in listOf("about", "data", "blob", "javascript")) return false
            // Andere Apps nur auf ausdruecklichen Tipp oeffnen, nie per Skript/Weiterleitung
            if (!request.hasGesture()) return true
            // Links ins Internet im normalen Browser, mailto:/tel:/intent: in der passenden App
            // (sonst Ladefehler -> ungewollter Tunnel-Rueckfall)
            runCatching {
                if (u.scheme == "intent") {
                    val i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                        .addCategory(Intent.CATEGORY_BROWSABLE).setComponent(null)
                    i.selector = null
                    startActivity(i)
                } else startActivity(Intent(Intent.ACTION_VIEW, u))
            }
            return true
        }
    }

    /** Gehoert die Adresse zur Hauptseite (nur dann die Seite durch eine Meldung ersetzen)? */
    private fun isAppHost(url: String?): Boolean =
        Uri.parse(url.orEmpty()).host.equals(app.host, ignoreCase = true)

    /** Art des Zertifikatsproblems fuer die Anzeige. */
    private fun certProblem(e: SslError): String = getString(when {
        e.hasError(SslError.SSL_IDMISMATCH) -> R.string.web_cert_err_mismatch
        e.hasError(SslError.SSL_EXPIRED) || e.hasError(SslError.SSL_NOTYETVALID) || e.hasError(SslError.SSL_DATE_INVALID) -> R.string.web_cert_err_date
        e.hasError(SslError.SSL_UNTRUSTED) -> R.string.web_cert_err_untrusted
        else -> R.string.web_cert_err_other
    })

    private fun askLogin(host: String, realm: String?, handler: HttpAuthHandler) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val user = EditText(this).apply { hint = getString(R.string.web_login_user) }
        val pass = EditText(this).apply {
            hint = getString(R.string.web_login_pass)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0)
            addView(user); addView(pass)
        }
        if (isFinishing || isDestroyed) { handler.cancel(); return }
        var answered = false
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.web_login_title, host))
            .setMessage(realm)
            .setView(box)
            .setPositiveButton(getString(R.string.web_login_ok)) { _, _ ->
                answered = true
                handler.proceed(user.text.toString(), pass.text.toString())
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            // Abbrechen, Zurueck oder daneben tippen: Anfrage beenden, sonst wartet die Seite ewig
            .setOnDismissListener { if (!answered) { answered = true; handler.cancel() } }
            .show()
    }

    /**
     * Bestaetigte Zertifikate je Host ("host=sha256,host2=sha256"). Aeltere Eintraege enthalten nur
     * den Fingerabdruck ohne Host - der gilt fuer den Host der Web-App.
     */
    private fun certPins(a: WebApp = app): Map<String, String> =
        a.trustedCert.orEmpty().split(',').filter { it.isNotBlank() }.associate { e ->
            if ('=' in e) e.substringBefore('=') to e.substringAfter('=') else a.host.lowercase() to e
        }

    private fun fingerprint(cert: SslCertificate?): String? {
        cert ?: return null
        val der: ByteArray? = if (Build.VERSION.SDK_INT >= 29) {
            cert.x509Certificate?.encoded
        } else {
            SslCertificate.saveState(cert).getByteArray("x509-certificate")
        }
        der ?: return null
        return MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
    }

    private fun flashBadge() {
        lifecycleScope.launch { showBadge = true; delay(2500); showBadge = false }
    }

    // -------------------------------------------------------------------- UI

    @androidx.compose.runtime.Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().background(Color(0xFF101418)).then(if (app.fullscreen) Modifier else Modifier.systemBarsPadding())) {
            AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())

            when (val p = phase) {
                is Phase.Connecting -> Cover { Busy(p.text) }
                is Phase.Failed -> Cover { Problem(p.text) }
                Phase.Ready -> {}
            }
            if (locked) Cover { Locked() }

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
                    Text(if (viaTunnel) stringResource(R.string.web_badge_away, if (tunnel?.isTailscale == true) "Tailscale" else "WireGuard") else stringResource(R.string.web_badge_home), color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Cover(content: @androidx.compose.runtime.Composable () -> Unit) {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF101418))
                // alle Beruehrungen abfangen, sonst erreichen sie die Seite dahinter
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val icon = remember { Shortcuts.roundIcon(this@WebAppActivity, app, 256).asImageBitmap() }
            Image(icon, null, Modifier.size(88.dp))
            Spacer(Modifier.height(16.dp))
            Text(app.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(28.dp))
            content()
        }
    }

    @androidx.compose.runtime.Composable
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

    @androidx.compose.runtime.Composable
    private fun Busy(text: String) {
        CircularProgressIndicator(color = Color(0xFFF2B544))
        Spacer(Modifier.height(16.dp))
        Text(text, color = Color(0xFFB8C4CC), textAlign = TextAlign.Center)
    }

    @androidx.compose.runtime.Composable
    private fun Problem(text: String) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80), modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, color = Color(0xFFE0E6EA), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { retry() }) { Text(stringResource(R.string.web_retry)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            startActivity(Intent(this@WebAppActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_EDIT, app.id))
        }) { Text(stringResource(R.string.web_settings), color = Color.White) }
    }

    companion object { private const val RELOCK_MS = 60_000L }
}

private const val DESKTOP_VIEWPORT_JS =
    "(function(){var m=document.querySelector('meta[name=viewport]');" +
    "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
    "m.content='width=1200';})()"
