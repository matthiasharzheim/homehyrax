package de.camperflower.homehyrax

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import de.camperflower.homehyrax.wgbridge.Wgbridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Schaltflaeche (z. B. Garagentor): entsperren -> zuhause direkt / unterwegs
 * per Tunnel (WireGuard/Tailscale) -> genau EINE Anfrage senden -> Ergebnis zeigen, schliessen.
 * Laeuft als kleine Karte ueber dem Startbildschirm.
 */
class ActionActivity : FragmentActivity() {

    private sealed interface State {
        data class Busy(val text: String) : State
        /** Ergebnis: Kopfzeile + Zustandszeilen; waiting = Impuls laeuft noch */
        data class Done(val title: String, val lines: List<String>, val waiting: Boolean = false) : State
        data class Failed(val text: String) : State
    }

    private var state by mutableStateOf<State>(State.Busy("..."))
    private lateinit var app: WebApp
    private var holding: String? = null
    private var started = false
    /** Anfrage ist raus - ob sie ankam, ist evtl. unbekannt (Zeitueberschreitung, Neuerzeugung) */
    private var sent = false
    private var entered = false
    private lateinit var unlocker: Unlocker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        unlocker = Unlocker(this)
        // Aus "Zuletzt verwendet" wieder geoeffnet: nie erneut ausloesen
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            finishAndRemoveTask(); return
        }
        val a = intent.data?.lastPathSegment?.let { store.app(it) }
        if (a == null || !a.isAction) {
            Toast.makeText(this, getString(R.string.act_gone), Toast.LENGTH_LONG).show()
            finishAndRemoveTask(); return
        }
        app = a
        // Doppeltipp / zweites Symbol: dieselbe Schaltflaeche laeuft schon -> nicht noch einmal senden
        entered = Running.enter(app.id, force = savedInstanceState != null)
        if (!entered) { finishAndRemoveTask(); return }
        setContent { Sheet() }
        when {
            savedInstanceState == null -> authenticate()
            // neu erzeugt (Schriftgroesse, Sprache, Prozessende beim Entsperren ...): nicht bei "..." haengen bleiben
            savedInstanceState.getBoolean(KEY_DONE) -> finishAndRemoveTask()
            savedInstanceState.getBoolean(KEY_SENT) -> state = State.Failed(getString(R.string.act_maybe_done))
            else -> authenticate()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SENT, sent)
        outState.putBoolean(KEY_DONE, state is State.Done)
    }

    // --------------------------------------------------------- Entsperren

    private fun authenticate() {
        if (!app.requireAuth) { fire(); return }
        state = State.Busy(getString(R.string.act_unlocking))
        unlocker.ask(app.name, getString(R.string.act_unlock_confirm)) { ok, err ->
            when {
                ok -> fire()
                err == null -> finishAndRemoveTask()   // abgebrochen: still schliessen, nichts ausgeloest
                else -> state = State.Failed(err)
            }
        }
    }

    // ------------------------------------------------------------ Ausloesen

    private fun fire() {
        if (started) return
        started = true
        sent = false
        lifecycleScope.launch {
            val act = app.action
            val url = act?.url(app.url) ?: app.url
            val target = app.copy(url = url)
            val t = store.tunnel(app.tunnelId)
            state = State.Busy(getString(R.string.act_connecting))
            val where = if (t != null) Net.where(target) else null
            if (t != null && where?.home == false) {
                state = State.Busy(getString(R.string.common_away_connecting, t.name))
                // "Erneut" ruft fire() wieder auf: nur einmal festhalten, onDestroy gibt einmal frei
                if (holding == null) { Net.acquire(t.id); holding = t.id }
                val err = Net.connect(t)
                if (err != null && !(where.skipped && Net.reachableDirect(target))) { fail(err); return@launch }
                Net.route(target, if (err == null) t.id else null)
            } else {
                Net.route(target, null)
            }
            state = State.Busy(getString(R.string.act_sending))
            sent = true
            val res = withContext(Dispatchers.IO) {
                runCatching { Wgbridge.request("GET", url, act?.user.orEmpty(), act?.pass.orEmpty(), authMode(act), 8000) }
            }
            val out = res.getOrElse { e ->
                // Zeitueberschreitung o. ae.: evtl. hat das Geraet trotzdem geschaltet -> Zustand nachfragen,
                // sonst ausdruecklich darauf hinweisen, bevor jemand "Erneut" tippt
                val msg = (getString(R.string.act_no_answer, target.host) + "\n${e.message ?: ""}").trim()
                val now = if (act != null && act.gen != 0) readShelly(act).first else null
                fail(msg + "\n" + (if (now != null) getString(R.string.act_state_now, stateWord(now)) else getString(R.string.act_maybe_done)))
                return@launch
            }
            val status = out.substringBefore('\n').toIntOrNull() ?: 0
            if (status != 200) sent = false   // Geraet hat klar abgelehnt: nichts ausgefuehrt
            when (status) {
                200 -> { buzz(); showResult(act, out.substringAfter('\n', "")) }
                401 -> fail(getString(R.string.act_http_401))
                404, 500 -> fail(getString(R.string.act_http_unknown_cmd, status))
                else -> fail(getString(R.string.act_http_unexpected, status))
            }
        }
    }

    /** Anmeldeart fuer Wgbridge.request: Gen2+ nur Digest, Gen1 Basic, eigene URL wie vom Geraet verlangt. */
    private fun authMode(a: ShellyAction?): String = when (a?.gen) {
        1 -> "basic"
        2 -> "digest"
        else -> ""
    }

    /** Zustand beim Shelly nachfragen: (Ausgang an?, Leistung W) - null = unbekannt. */
    private suspend fun readShelly(a: ShellyAction): Pair<Boolean?, Double?> = withContext(Dispatchers.IO) {
        val url = if (a.gen == 1) "http://${a.ip}/status" else "http://${a.ip}/rpc/Switch.GetStatus?id=${a.channel}"
        val out = runCatching { Wgbridge.request("GET", url, a.user, a.pass, authMode(a), 5000) }.getOrNull() ?: return@withContext null to null
        if (out.substringBefore('\n') != "200") return@withContext null to null
        val o = runCatching { org.json.JSONObject(out.substringAfter('\n')) }.getOrNull() ?: return@withContext null to null
        if (a.gen == 1) {
            val on = o.optJSONArray("relays")?.optJSONObject(a.channel)?.let { if (it.has("ison")) it.optBoolean("ison") else null }
            val pw = o.optJSONArray("meters")?.optJSONObject(a.channel)?.let { if (it.has("power")) it.optDouble("power") else null }
            on to pw
        } else {
            (if (o.has("output")) o.optBoolean("output") else null) to (if (o.has("apower")) o.optDouble("apower") else null)
        }
    }


    /**
     * Nach dem Senden den echten Zustand zeigen: nur "Eingeschaltet"/"Ausgeschaltet";
     * beim Impuls zusaetzlich abwarten, ob der Ausgang wieder zurueckschaltet.
     */
    private suspend fun showResult(a: ShellyAction?, body: String) {
        if (a == null || a.gen == 0) {
            state = State.Done(getString(R.string.act_sent), listOf(getString(R.string.act_reply_200), body.trim().take(80)).filter { it.isNotEmpty() })
            autoClose(); return
        }
        // Nur das Ergebnis zeigen - echter Zustand vom Shelly
        val (now, _) = readShelly(a)
        fun word(on: Boolean?) = stateWord(on)
        if (a.mode == 0) {
            state = State.Done(word(now), listOf(getString(R.string.act_back_in, a.secs)), waiting = true)
            delay(a.secs * 1000L + 700)
            val (after, _) = readShelly(a)
            state = if (after == true) State.Done(getString(R.string.act_still_on), listOf(getString(R.string.act_check_shelly)))
            else State.Done(word(after), emptyList())
        } else {
            state = State.Done(word(now), emptyList())
        }
        autoClose()
    }

    private fun stateWord(on: Boolean?) = getString(when (on) { true -> R.string.act_on; false -> R.string.act_off; null -> R.string.act_sent })

    private suspend fun autoClose() {
        val shown = state
        delay(5000)
        if (state === shown) finishAndRemoveTask()
    }

    private fun fail(msg: String) {
        state = State.Failed(msg)
        started = false
    }

    /** kurzes Haptik-Feedback (ohne VIBRATE-Berechtigung) */
    private fun buzz() {
        val c = if (Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM
        else android.view.HapticFeedbackConstants.LONG_PRESS
        window.decorView.performHapticFeedback(c)
    }

    override fun onDestroy() {
        holding?.let { Net.release(it) }
        if (entered && !isChangingConfigurations) Running.leave(app.id)
        super.onDestroy()
    }

    /**
     * Prozessweite Sperre je Schaltflaeche: solange eine Karte dafuer laeuft und 3 s nach dem
     * Start wird kein zweites Mal ausgeloest (Doppeltipp auf Kachel oder Startbildschirm-Symbol).
     */
    private object Running {
        private const val DEBOUNCE_MS = 3000L
        private val active = HashMap<String, Int>()
        private val lastStart = HashMap<String, Long>()

        @Synchronized
        fun enter(id: String, force: Boolean): Boolean {
            val now = SystemClock.elapsedRealtime()
            val busy = (active[id] ?: 0) > 0 || lastStart[id]?.let { now - it < DEBOUNCE_MS } == true
            if (busy && !force) return false
            active[id] = (active[id] ?: 0) + 1
            lastStart[id] = now
            return true
        }

        @Synchronized
        fun leave(id: String) {
            active[id] = ((active[id] ?: 0) - 1).coerceAtLeast(0)
        }
    }

    private companion object {
        const val KEY_SENT = "sent"
        const val KEY_DONE = "done"
    }

    // ------------------------------------------------------------------- UI

    @Composable
    private fun Sheet() {
        val none = remember { MutableInteractionSource() }
        val icon = remember { Shortcuts.roundIcon(this@ActionActivity, app, 144).asImageBitmap() }
        Box(
            Modifier.fillMaxSize().background(Color(0x66000000))
                .clickable(interactionSource = none, indication = null) { if (state !is State.Busy) finishAndRemoveTask() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()
                    .background(Color(0xFF1C2228), RoundedCornerShape(28.dp))
                    .clickable(interactionSource = none, indication = null) {}
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(icon, null, Modifier.size(48.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(app.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                when (val s = state) {
                    is State.Busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Color(0xFFF2B544), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(s.text, color = Color(0xFFB8C4CC))
                    }
                    is State.Done -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.waiting) CircularProgressIndicator(Modifier.size(24.dp), color = Color(0xFFF2B544), strokeWidth = 2.5.dp)
                            else Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFFF2B544), modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(s.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(10.dp))
                        s.lines.forEach { Text(it, color = Color(0xFFB8C4CC), fontSize = 15.sp) }
                        if (!s.waiting) {
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { finishAndRemoveTask() }) { Text(stringResource(R.string.common_close)) }
                        }
                    }
                    is State.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Error, null, tint = Color(0xFFFF8A80), modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(s.text, color = Color(0xFFE0E6EA))
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { finishAndRemoveTask() }) { Text(stringResource(R.string.common_close)) }
                            Button(onClick = { authenticate() }) { Text(stringResource(R.string.act_again)) }
                        }
                    }
                }
            }
        }
    }
}
