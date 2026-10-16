package de.camperflower.homehyrax

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
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
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
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
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import de.camperflower.homehyrax.wgbridge.Wgbridge
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest

/** Zeigt eine Web-App im Vollbild; entscheidet zuhause/unterwegs. */
class WebAppActivity : FragmentActivity(), Medien.Ziel {

    private sealed interface Phase {
        data class Connecting(val text: String) : Phase
        data object Ready : Phase
        data class Failed(val text: String) : Phase
    }

    private var phase by mutableStateOf<Phase>(Phase.Connecting("..."))
    private var viaTunnel by mutableStateOf(false)
    private var showBadge by mutableStateOf(false)
    /** zaehlt jedes "nach unten ziehen": Menue-Knopf fuer ein paar Sekunden zeigen */
    private var menuAnstoss by mutableStateOf(0)
    private lateinit var pull: SwipeRefreshLayout
    /** Finger liegt in einem inneren Scrollbereich der Seite, der nicht ganz oben steht (meldet PULL_JS). */
    @Volatile private var innerScrolled = false

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
            // offene Mikrofon-Rueckfrage nicht hinter der Sperre stehen lassen (Schliessen = ablehnen)
            if (v) { micDialog?.dismiss(); menuAnstoss = 0 }
            if (::web.isInitialized) {
                web.visibility = if (v) View.INVISIBLE else View.VISIBLE
                if (v) web.onPause() else web.onResume()
            }
        }
    private var lockError by mutableStateOf<String?>(null)
    private var startedOnce = false
    private var hiddenAt = 0L

    /** Medien-Bruecke eingerichtet: Ursprung, fuer den die WebView das Objekt anbietet (sonst null) */
    private var medienOrigin: String? = null

    // Mikrofon (getUserMedia): offene Anfrage der Seite, wartet auf Rueckfrage oder Android-Berechtigung
    private var micRequest: PermissionRequest? = null
    private var micDialog: android.app.AlertDialog? = null
    private val askMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val r = micRequest ?: return@registerForActivityResult
        micRequest = null
        if (ok && micAllowed(r)) r.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        else { r.deny(); if (!ok) Toast.makeText(this, getString(R.string.web_mic_no_permission), Toast.LENGTH_LONG).show() }
    }

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
        // ganz oben nach unten ziehen zeigt kurz das Menue (frueher: neu laden - passierte beim Scrollen versehentlich)
        pull = SwipeRefreshLayout(this).apply {
            addView(web)
            setOnRefreshListener {
                isRefreshing = false
                if (!locked) menuAnstoss++
            }
            // nicht nur die Seite selbst, auch innere Scrollbereiche (Dialoge, Seiten wie Home Assistant) zaehlen:
            // sonst faengt das Ziehen dort das Hochscrollen ab
            setOnChildScrollUpCallback { _, _ -> web.canScrollVertically(-1) || innerScrolled }
        }
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
            // Tunnel wurde im Hintergrund abgebaut -> leise neu aufbauen; Gast: jedes Mal beim Schluesseldienst fragen
            if (viaTunnel && (t.isGuest || !Net.isRunning(t.id))) lifecycleScope.launch {
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
        if (::app.isInitialized) Medien.stop(this, app.id)
        releaseTunnel()   // falls ein Verbindungsaufbau erst nach onStop fertig wurde
        micDialog?.dismiss()   // offene Mikrofon-Anfrage ablehnen
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
        // einzige Bruecke zur Seite: ein Wahr/Falsch fuer "Nach unten ziehen" (siehe PULL_JS)
        addJavascriptInterface(PullBridge(), "HomeHyraxPull")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            WebViewCompat.addDocumentStartJavaScript(this, PULL_JS, setOf("*"))
        if (app.media && !app.isGuest && app.kind == KIND_WEB) medienBruecke(this)
        if (app.desktop) {
            // Desktop-Webseite: Desktop-User-Agent (gleiche Chrome-Version), "Mobile"/Android-Kennung weg
            val chrome = Regex("Chrome/[\\d.]+").find(settings.userAgentString)?.value ?: "Chrome/130.0.0.0"
            settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        }
        webChromeClient = Chrome()
        webViewClient = Client()
    }

    /** Nur Mikrofon, nur fuer die eingetragene Adresse; Kamera, MIDI, DRM usw. bleiben abgelehnt. */
    private inner class Chrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE !in request.resources || !micAllowed(request) ||
                micRequest != null || isFinishing || isDestroyed) { request.deny(); return }
            if (app.mic) { grantMic(request); return }
            // Rueckfrage nur auf der sichtbaren Seite (im Hintergrund nur mit schon erteilter Freigabe)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { request.deny(); return }
            micRequest = request
            var answered = false
            micDialog = android.app.AlertDialog.Builder(this@WebAppActivity)
                .setTitle(getString(R.string.web_mic_title))
                .setMessage(getString(R.string.web_mic_message, app.host))
                .setPositiveButton(getString(R.string.web_mic_allow)) { _, _ ->
                    answered = true
                    micRequest = null
                    if (!micAllowed(request)) { request.deny(); return@setPositiveButton }
                    // auf der aktuell gespeicherten Fassung aufsetzen (evtl. inzwischen im Editor geaendert)
                    val cur = store.app(app.id) ?: app
                    if (runCatching { store.saveApp(cur.copy(mic = true)) }.isSuccess) app = app.copy(mic = true)
                    grantMic(request)
                }
                .setNegativeButton(getString(R.string.web_mic_deny), null)
                // Ablehnen, Zurueck oder daneben tippen: nur diesmal ablehnen (naechstes Mal wird wieder gefragt)
                .setOnDismissListener { micDialog = null; if (!answered && micRequest === request) { micRequest = null; request.deny() } }
                .show()
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (micRequest === request) { micRequest = null; micDialog?.dismiss() }
        }
    }

    /**
     * Mikrofon nur fuer selbst angelegte Eintraege (keine Gast-Eintraege), nur fuer genau die eingetragene
     * Adresse (Schema, Host, Port - nicht fuer eingebettete fremde Seiten oder weitere Geraete im Heimnetz)
     * und nur, solange die Seite sichtbar und entsperrt ist.
     */
    private fun micAllowed(r: PermissionRequest): Boolean {
        val o = r.origin ?: return false
        val u = Uri.parse(app.url)
        val port = { x: Uri -> if (x.port > 0) x.port else if (x.scheme.equals("https", true)) 443 else 80 }
        return app.kind == KIND_WEB && !app.isGuest && !locked &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            o.scheme.equals(u.scheme, ignoreCase = true) && o.host.equals(u.host, ignoreCase = true) && port(o) == port(u)
    }

    /** Android-Berechtigung ggf. erst anfragen, dann der Seite das Mikrofon geben. */
    private fun grantMic(r: PermissionRequest) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            r.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)); return
        }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { r.deny(); return }
        micRequest = r
        askMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Medien-Bruecke (Schalter "Medientasten"): window.HomeHyraxMedien.metadaten()/zustand() nur fuer den Ursprung der
     * eingetragenen Adresse - die WebView bietet Kanal und Skript fremden Seiten/iframes gar nicht erst an.
     */
    private fun medienBruecke(w: WebView) {
        val o = ursprung(app.url) ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addWebMessageListener(w, "HomeHyraxMedienKanal", setOf(o)) { _, msg, source, isMain, _ ->
                    if (isMain && ursprung(source.toString()) == o) medienNachricht(msg.data)
                }
                WebViewCompat.addDocumentStartJavaScript(w, MEDIEN_JS, setOf(o))
                medienOrigin = o
            } catch (_: IllegalArgumentException) {
                // Ursprungsregel abgelehnt: ohne Bruecke weiter
            }
        }
    }

    /** Eintrag mit Sperre: nach > 60 s im Hintergrund keine Tasten/Aufnahme mehr, bis wieder entsperrt. */
    private fun hintergrundGesperrt() = app.requireAuth && hiddenAt > 0 &&
        !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
        android.os.SystemClock.elapsedRealtime() - hiddenAt > RELOCK_MS

    private fun medienNachricht(data: String?) {
        val j = runCatching { JSONObject(data.orEmpty()) }.getOrNull() ?: return
        if (!Medien.aktiv(app.id)) {
            // Sitzung nur aus der sichtbaren, entsperrten Seite starten
            if (locked || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            Medien.start(this, app, this)
        }
        j.optJSONArray("m")?.let { Medien.metadaten(this, it.optString(0), it.optString(1), it.optString(2)) }
        if (j.has("z")) Medien.zustand(this, j.optString("z"))
        if (j.has("b")) Medien.bild(this, j.optString("b"))
    }

    /** Taste (Lenkrad, Uhr, Benachrichtigung) an die Seite - nur, solange sie noch auf dem eingetragenen Ursprung ist. */
    override fun medienAktion(name: String) {
        if (name !in MEDIEN_AKTIONEN || locked || hintergrundGesperrt() || !::web.isInitialized) return
        if (medienOrigin == null || ursprung(web.url.orEmpty()) != medienOrigin) return
        web.evaluateJavascript("(function(n){var f=window.homeHyraxMedienAktion||window.fahrerMedienAktion;" +
            "if(typeof f==='function')f(n);})('$name')", null)
    }

    override fun medienBeenden() = finishAndRemoveTask()


    private inner class PullBridge {
        @JavascriptInterface fun inner(scrolled: Boolean) { innerScrolled = scrolled }
    }

    private inner class Client : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            // neue Seite: alter Wert aus einem Dialog der vorigen Seite gilt nicht mehr (sonst haengt die Sperre,
            // wenn die neue Seite kein Skript hat, z. B. Bild oder PDF)
            innerScrolled = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            // Desktop: mobiles Viewport-Tag ueberschreiben -> Seite wird in 1200 px Breite gelayoutet und eingepasst
            if (app.desktop) view.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
            // alte WebViews ohne Dokumentstart-Skript: nach dem Laden nachreichen (PULL_JS laeuft nur einmal)
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(PULL_JS, null)
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
            // Gast-Eintrag: nur die eigene Adresse (nicht weitere Geraete im Heimnetz des Besitzers)
            val sameApp = isWeb && (u.host.equals(app.host, ignoreCase = true) || (!app.isGuest && Hosts.isPrivate(u.host)))
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
            AndroidView(factory = { pull }, modifier = Modifier.fillMaxSize())

            when (val p = phase) {
                is Phase.Connecting -> Cover { Busy(p.text) }
                is Phase.Failed -> Cover { Problem(p.text) }
                Phase.Ready -> {}
            }
            if (locked) Cover { Locked() }
            else Menu(Modifier.align(Alignment.TopEnd))

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
                    Text(verbindungText(), color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }

    /** "Zuhause · direkt" oder "Unterwegs · ueber WireGuard/Tailscale" */
    @androidx.compose.runtime.Composable
    private fun verbindungText(): String =
        if (viaTunnel) stringResource(R.string.web_badge_away, if (tunnel?.isTailscale == true) "Tailscale" else "WireGuard")
        else stringResource(R.string.web_badge_home)

    /** Menue oben rechts: erscheint nach "nach unten ziehen" (laedt nicht mehr neu, das passierte beim Scrollen versehentlich). */
    @androidx.compose.runtime.Composable
    private fun Menu(modifier: Modifier) {
        var open by remember { mutableStateOf(false) }
        var sichtbar by remember { mutableStateOf(false) }
        // erscheint nach "nach unten ziehen", verschwindet nach ein paar Sekunden ohne Tipp (auch nach dem Schliessen)
        LaunchedEffect(menuAnstoss, open) {
            if (open || menuAnstoss == 0) return@LaunchedEffect
            sichtbar = true
            delay(MENU_SICHTBAR_MS)
            sichtbar = false
        }
        Box(modifier.statusBarsPadding().displayCutoutPadding().padding(6.dp)) {
            // waehrend des Verbindens faengt die Ladeanzeige jede Geste ab: dann Knopf immer zeigen (Einstellungen)
            AnimatedVisibility(visible = sichtbar || open || phase is Phase.Connecting, enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0x99202830))) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.web_menu), tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                // Verbindungsart als Info oben (wie die Plakette beim Oeffnen), erst wenn die Seite geladen ist
                if (phase == Phase.Ready) {
                    DropdownMenuItem(text = { Text(verbindungText()) }, onClick = {}, enabled = false,
                        leadingIcon = { Icon(if (viaTunnel) Icons.Filled.Lock else Icons.Filled.Home, null, tint = Color(0xFFF2B544)) })
                    HorizontalDivider()
                }
                // nicht waehrend des Verbindens: sonst liefen zwei start() gleichzeitig
                DropdownMenuItem(text = { Text(stringResource(R.string.web_refresh)) },
                    leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                    enabled = phase !is Phase.Connecting,
                    onClick = { open = false; retry() })
                // Gast-Eintraege lassen sich nicht bearbeiten
                if (!app.isGuest) DropdownMenuItem(text = { Text(stringResource(R.string.web_settings)) },
                    leadingIcon = { Icon(Icons.Filled.Settings, null) },
                    onClick = {
                        open = false
                        startActivity(Intent(this@WebAppActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_EDIT, app.id))
                    })
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
        // Gast-Eintraege lassen sich nicht bearbeiten
        if (!app.isGuest) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                startActivity(Intent(this@WebAppActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_EDIT, app.id))
            }) { Text(stringResource(R.string.web_settings), color = Color.White) }
        }
    }

    companion object {
        private const val RELOCK_MS = 60_000L
        private const val MENU_SICHTBAR_MS = 4_000L
        private val MEDIEN_AKTIONEN = setOf("play", "pause", "playpause", "previoustrack", "nexttrack", "seekbackward")

        /** "https://host[:port]" wie die WebView den Ursprung schreibt (Standard-Port ohne Angabe), sonst null. */
        fun ursprung(url: String): String? {
            val u = Uri.parse(url)
            val s = u.scheme?.lowercase()?.takeIf { it == "https" || it == "http" } ?: return null
            val h = u.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val host = if (':' in h && !h.startsWith("[")) "[$h]" else h
            val p = u.port
            return if (p <= 0 || (s == "https" && p == 443) || (s == "http" && p == 80)) "$s://$host" else "$s://$host:$p"
        }
    }
}

/**
 * Medien-Bruecke in der Seite (nur Hauptseite des eingetragenen Ursprungs): window.HomeHyraxMedien.metadaten(titel,
 * interpret, album), .zustand(z) und .bild(dataUrl) schicken kurze JSON-Nachrichten an die App. Tasten ruft die App als
 * window.homeHyraxMedienAktion(name) auf (aeltere Seiten: window.fahrerMedienAktion).
 */
private const val MEDIEN_JS =
    "(function(){if(window.top!==window||window.HomeHyraxMedien)return;" +
    "function s(o){var k=window.HomeHyraxMedienKanal;if(k)k.postMessage(JSON.stringify(o));}" +
    "function t(x){return x==null?'':String(x);}" +
    "window.HomeHyraxMedien={metadaten:function(a,b,c){s({m:[t(a),t(b),t(c)]});}," +
    "zustand:function(z){s({z:t(z)});},bild:function(u){u=t(u);if(u.length<700100)s({b:u});}};})()"

/**
 * Meldet bei jeder Beruehrung, ob der Finger in einem Scrollbereich liegt, der schon nach unten gescrollt ist.
 * Dann ist Hochwischen Scrollen und nicht "neu laden". Laeuft ueber composedPath (auch durch Shadow DOM, z. B.
 * Dialoge in Web Components); in einem iframe zaehlt auch dessen eigenes Dokument, das die App nicht sieht.
 */
private const val PULL_JS =
    "(function(){if(window.__hhPull)return;window.__hhPull=1;" +
    "function sc(n){var o=getComputedStyle(n).overflowY;return o==='auto'||o==='scroll'||o==='overlay';}" +
    "document.addEventListener('touchstart',function(e){var s=false,se=document.scrollingElement," +
    "p=e.composedPath?e.composedPath():[e.target];" +
    "for(var i=0;i<p.length&&!s;i++){var n=p[i];if(!n||n.nodeType!==1)continue;" +
    "if(n===se)break;if(n.scrollTop>0&&sc(n))s=true;}" +
    "if(!s&&window!==top&&se&&se.scrollTop>0)s=true;" +
    "try{HomeHyraxPull.inner(s);}catch(x){}},{capture:true,passive:true});})()"

private const val DESKTOP_VIEWPORT_JS =
    "(function(){var m=document.querySelector('meta[name=viewport]');" +
    "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
    "m.content='width=1200';})()"
