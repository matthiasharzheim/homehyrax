package de.camperflower.homehyrax

import android.text.format.DateUtils
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.produceState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.LocalContentColor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import android.view.TextureView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.MutableState
import androidx.compose.ui.graphics.ImageBitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ListItem
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.LeadingIconTab
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import de.camperflower.homehyrax.wgbridge.Wgbridge
import kotlinx.coroutines.launch

/** Farbvorschlaege fuer Symbole ohne eigenes Bild. */
private val palette = listOf(0xFF1F242B, 0xFF1565C0, 0xFF6A1B9A, 0xFFC62828, 0xFFEF6C00, 0xFFC98A12).map { it.toInt() }

class MainActivity : FragmentActivity() {

    sealed interface Page {
        data object Home : Page
        data class EditApp(val id: String?) : Page
        data object Tunnels : Page
        /** returnTo: nach dem Speichern zurueck in den Editor (neue Verbindung direkt auswaehlen) */
        data class AddTunnel(val returnTo: EditApp?, val editId: String? = null) : Page
        data object Share : Page
        data object Import : Page
        data object Licenses : Page
    }

    /**
     * Seiten- und Editor-Zustand, der Drehen/Dunkelmodus (Activity wird neu erzeugt) ueberstehen muss.
     * Einfache Eingabefelder liegen per rememberSaveable in den Seiten selbst; hier nur, was nicht
     * ins Bundle passt (Bild), nicht hineingehoert (Geheimnisse) oder seitenuebergreifend ist.
     */
    class Ui : ViewModel() {
        var page by mutableStateOf<Page>(Page.Home)
        /** Editor-Stand waehrend "neue Verbindung" */
        var draft: WebApp? = null
        /** Bild im Editor (ungespeichert) und ob es geaendert wurde */
        val editIcon = mutableStateOf<Bitmap?>(null)
        val editIconChanged = mutableStateOf(false)
        /** nach einem Import: diese Web-Apps bekommen die naechste neue Verbindung */
        var assignAfterTunnel by mutableStateOf(emptyList<String>())
        /** Listen aus dem Store (im Hintergrund geladen); loaded = erster Ladevorgang fertig */
        var apps by mutableStateOf(emptyList<WebApp>())
        var tunnels by mutableStateOf(emptyList<Tunnel>())
        var pinnedIds by mutableStateOf(emptySet<String>())
        var loaded by mutableStateOf(false)
        /**
         * Geheimnisse der offenen Seite (Passwoerter, WireGuard-Konfiguration, Auth-Key): ueberstehen
         * das Drehen, landen aber nie im Instance-State (der liegt ausserhalb der App-Verschluesselung).
         */
        val secrets = HashMap<String, MutableState<String>>()
        /** Zeitpunkt der letzten Entsperrung (elapsedRealtime), 0 = keine */
        var unlockedAt = 0L
        /** Import wartet auf Bestaetigung */
        var importPlan by mutableStateOf<Share.Plan?>(null)
        /** gerade geteilte Export-Datei (wird nach dem Teilen geloescht) */
        var shareFile: File? = null
    }

    private val ui by lazy { ViewModelProvider(this)[Ui::class.java] }
    private var page: Page
        get() = ui.page
        set(v) { ui.page = v }
    private var draft: WebApp?
        get() = ui.draft
        set(v) { ui.draft = v }
    private var assignAfterTunnel: List<String>
        get() = ui.assignAfterTunnel
        set(v) { ui.assignAfterTunnel = v }
    private var apps: List<WebApp>
        get() = ui.apps
        set(v) { ui.apps = v }
    private var tunnels: List<Tunnel>
        get() = ui.tunnels
        set(v) { ui.tunnels = v }
    private var pinnedIds: Set<String>
        get() = ui.pinnedIds
        set(v) { ui.pinnedIds = v }

    private lateinit var unlocker: Unlocker
    /** Sortieren per Drag & Drop: solange aktiv (und kurz danach) loest ein Tipp auf die Kachel nichts aus */
    private var dragActive = false
    private var dragEndedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        unlocker = Unlocker(this)
        lifecycleScope.launch(Dispatchers.IO) { runCatching { Share.cleanupExports(this@MainActivity) } }
        // Startbildschirm-Symbole mit dem aktuellen Zeichner auffrischen (z. B. nach einem Update der Symbol-Darstellung)
        lifecycleScope.launch(Dispatchers.Default) {
            Shortcuts.pinnedIds(this@MainActivity).forEach { id -> store.app(id)?.let { Shortcuts.update(this@MainActivity, it) } }
        }
        // nur beim ersten Start; nach Drehen o. ae. bleibt die aktuelle Seite.
        // Erst nach dem Anzeigen: eine gesperrte Kachel fragt vorher nach dem Entsperren
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_EDIT)?.let { id -> window.decorView.post { editGuarded(id) } }
        setContent { AppTheme { Root() } }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onStop() {
        super.onStop()
        // App verlassen: naechster Zugriff auf Geheimnisse fragt wieder nach dem Entsperren
        if (!isChangingConfigurations) ui.unlockedAt = 0L
    }

    private var reloadSeq = 0

    /** Listen im Hintergrund neu laden (Entschluesseln kostet Zeit, nicht im Main-Thread). */
    private fun reload() {
        val seq = ++reloadSeq
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Triple(store.apps(), store.tunnels(), Shortcuts.pinnedIds(this@MainActivity)) }.getOrNull()
            } ?: return@launch
            if (seq != reloadSeq) return@launch   // ein neuerer Ladevorgang laeuft schon
            apps = r.first; tunnels = r.second; pinnedIds = r.third
            ui.loaded = true
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Speichern mit Hinweis statt Absturz, falls der Keystore streikt. */
    private inline fun safeStore(block: () -> Unit): Boolean = try {
        block(); true
    } catch (e: Exception) {
        toast(getString(R.string.main_err_save)); false
    }

    /**
     * Zugriff auf Geheimnisse (Teilen, Tunnel ansehen, gesperrte Kachel bearbeiten, Sperre abschalten)
     * erst nach Fingerabdruck/Gesicht/PIN; gilt danach 60 s, solange die App im Vordergrund bleibt.
     * Ohne Displaysperre gibt es nichts abzufragen (dann schuetzt auch die Kachel-Sperre nicht).
     */
    private fun guard(then: () -> Unit) {
        val at = ui.unlockedAt
        if (at > 0 && SystemClock.elapsedRealtime() - at < UNLOCK_VALID_MS) { then(); return }
        if (!unlocker.available()) { then(); return }
        unlocker.ask(getString(R.string.app_name), getString(R.string.main_unlock_confirm)) { ok, err ->
            when {
                ok -> { ui.unlockedAt = SystemClock.elapsedRealtime(); then() }
                err != null -> toast(err)
            }
        }
    }

    /** Kachel bearbeiten; gesperrte Kacheln erst nach dem Entsperren. */
    private fun editGuarded(id: String) {
        if (store.app(id)?.requireAuth == true) guard { startEdit(id) } else startEdit(id)
    }

    /**
     * Geheimes Eingabefeld: Wert liegt im ViewModel (uebersteht Drehen), nicht im Instance-State.
     * Wird beim Verlassen der Seite verworfen.
     */
    @Composable
    private fun rememberSecret(key: String, init: () -> String): MutableState<String> {
        val st = remember(key) { ui.secrets.getOrPut(key) { mutableStateOf(init()) } }
        DisposableEffect(key) { onDispose { if (!isChangingConfigurations) ui.secrets.remove(key) } }
        return st
    }

    // ================================================================ Seiten

    @Composable
    private fun Root() {
        BackHandler(enabled = page != Page.Home && page != Page.Tunnels) {
            page = when (val p = page) {
                is Page.AddTunnel -> p.returnTo ?: Page.Tunnels
                else -> Page.Home
            }
        }
        var crash by remember { mutableStateOf(CrashLog.read()) }
        if (crash.isNotBlank()) {
            val clipboard = LocalClipboardManager.current
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.main_crash_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.main_crash_text), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.main_crash_check), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Text(crash.takeLast(4000), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp))
                    }
                },
                confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(crash)); toast(getString(R.string.main_crash_copied)) }) { Text(stringResource(R.string.main_copy)) } },
                dismissButton = { TextButton(onClick = { CrashLog.clear(); crash = "" }) { Text(stringResource(R.string.main_close)) } },
            )
        }
        ui.importPlan?.let { ImportConfirm(it) }
        when (val p = page) {
            Page.Home, Page.Tunnels -> MainTabs()
            is Page.EditApp -> EditPage(p)
            is Page.AddTunnel -> AddTunnelPage(p.returnTo, p.editId)
            Page.Share -> SharePage()
            Page.Import -> ImportPage()
            Page.Licenses -> LicensesPage()
        }
    }

    /** Startseite: zwei Registerkarten (antippen oder wischen), + passend zur Karte. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainTabs() {
        val pager = rememberPagerState(initialPage = if (page == Page.Tunnels) 1 else 0) { 2 }
        val scope = rememberCoroutineScope()
        LaunchedEffect(pager.currentPage) { page = if (pager.currentPage == 1) Page.Tunnels else Page.Home }
        BackHandler(enabled = pager.currentPage == 1) { scope.launch { pager.animateScrollToPage(0) } }
        Scaffold(
            topBar = {
                // Eine durchgehende Leiste: gleiche Farbe wie die Seite (auch unter der
                // Statusleiste und hinter dem Menue), Trennlinie ueber die volle Breite.
                Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryTabRow(selectedTabIndex = pager.currentPage, modifier = Modifier.weight(1f),
                        containerColor = Color.Transparent, divider = {}) {
                        // flach: Symbol und Text nebeneinander (Home | Tunnel | Menue)
                        LeadingIconTab(pager.currentPage == 0, { scope.launch { pager.animateScrollToPage(0) } },
                            text = { Text(stringResource(R.string.main_tab_home), fontWeight = FontWeight.SemiBold) },
                            icon = { Icon(Icons.Filled.Home, null, Modifier.size(20.dp)) })
                        LeadingIconTab(pager.currentPage == 1, { scope.launch { pager.animateScrollToPage(1) } },
                            text = { Text(stringResource(R.string.main_tab_tunnels), fontWeight = FontWeight.SemiBold) },
                            icon = { Icon(Icons.Filled.VpnKey, null, Modifier.size(20.dp)) })
                    }
                    var more by remember { mutableStateOf(false) }
                    var info by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.main_more)) }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.main_share_title)) }, leadingIcon = { Icon(Icons.Filled.Share, null) },
                                enabled = apps.isNotEmpty() || tunnels.isNotEmpty(),
                                onClick = { more = false; guard { page = Page.Share } })
                            DropdownMenuItem(text = { Text(stringResource(R.string.main_import_title)) }, leadingIcon = { Icon(Icons.Filled.QrCodeScanner, null) },
                                onClick = { more = false; page = Page.Import })
                            DropdownMenuItem(text = { Text(stringResource(R.string.main_menu_info)) }, leadingIcon = { Icon(Icons.Filled.Info, null) },
                                onClick = { more = false; info = true })
                        }
                    }
                    if (info) InfoDialog { info = false }
                }
                HorizontalDivider()
                }
                }
            },
            floatingActionButton = {
                if (pager.currentPage == 0) ExtendedFloatingActionButton(
                    onClick = { startEdit(null) },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.main_new)) },
                ) else ExtendedFloatingActionButton(
                    onClick = { page = Page.AddTunnel(null) },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.main_fab_tunnel)) },
                )
            },
        ) { pad ->
            HorizontalPager(pager, Modifier.padding(pad).fillMaxSize()) { i ->
                if (i == 0) HomeContent() else TunnelsContent()
            }
        }
    }

    @Composable
    private fun HomeContent() {
        if (!ui.loaded) return
        if (apps.isEmpty()) { Welcome(Modifier); return }
        // Drag & Drop: lange druecken, dann ziehen. Die Liste wird live umsortiert, gespeichert beim Loslassen.
        val grid = rememberLazyGridState()
        val haptic = LocalHapticFeedback.current
        val list = remember { mutableStateListOf<WebApp>() }
        var dragId by remember { mutableStateOf<String?>(null) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }
        LaunchedEffect(apps) { if (dragId == null) { list.clear(); list.addAll(apps) } }

        fun itemAt(pos: Offset) = grid.layoutInfo.visibleItemsInfo.firstOrNull {
            pos.x >= it.offset.x && pos.x < it.offset.x + it.size.width &&
                pos.y >= it.offset.y && pos.y < it.offset.y + it.size.height
        }
        fun endDrag() {
            if (dragId != null) { safeStore { store.setOrder(list.map { it.id }) }; reload() }
            dragId = null; dragOffset = Offset.Zero
            if (dragActive) { dragActive = false; dragEndedAt = SystemClock.uptimeMillis() }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(156.dp),
            state = grid,
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        itemAt(pos)?.let { dragId = it.key as String; dragOffset = Offset.Zero; dragActive = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                    },
                    onDrag = { change, amount ->
                        val id = dragId ?: return@detectDragGesturesAfterLongPress
                        change.consume()
                        dragOffset += amount
                        val cur = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return@detectDragGesturesAfterLongPress
                        val center = Offset(cur.offset.x + cur.size.width / 2f, cur.offset.y + cur.size.height / 2f) + dragOffset
                        val target = itemAt(center)?.takeIf { it.key != id } ?: return@detectDragGesturesAfterLongPress
                        val from = list.indexOfFirst { it.id == id }
                        val to = list.indexOfFirst { it.id == target.key }
                        if (from >= 0 && to >= 0) {
                            list.add(to, list.removeAt(from))
                            // Kachel bleibt unter dem Finger, obwohl sich ihr Platz geaendert hat
                            dragOffset += Offset((cur.offset.x - target.offset.x).toFloat(), (cur.offset.y - target.offset.y).toFloat())
                        }
                    },
                    onDragEnd = { endDrag() },
                    onDragCancel = { endDrag() },
                )
            },
        ) {
            // Kameras ueber die volle Breite (grosses Bild), alles andere als kleine Kachel
            items(list, key = { it.id }, span = { if (it.isCamera) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { app ->
                val dragging = app.id == dragId
                Box(
                    (if (dragging) Modifier.zIndex(1f).graphicsLayer {
                        translationX = dragOffset.x; translationY = dragOffset.y; scaleX = 1.05f; scaleY = 1.05f
                    } else Modifier.animateItem()),
                ) { AppTile(app) }
            }
        }
    }

    /** Leerer Start: zwei klare Schritte. */
    @Composable
    private fun Welcome(modifier: Modifier) {
        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.main_welcome_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.main_welcome_text),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Step(1, stringResource(R.string.main_welcome_step1_title), stringResource(R.string.main_welcome_step1_text),
                done = tunnels.isNotEmpty()) { page = Page.AddTunnel(null) }
            Spacer(Modifier.height(12.dp))
            Step(2, stringResource(R.string.main_welcome_step2_title), stringResource(R.string.main_welcome_step2_text),
                done = false) { startEdit(null) }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { page = Page.Import }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.main_welcome_import))
            }
        }
    }

    @Composable
    private fun Step(n: Int, title: String, text: String, done: Boolean, onClick: () -> Unit) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimary)
                    else Text("$n", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    private fun AppTile(app: WebApp) {
        val ctx = LocalContext.current
        var menu by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf(false) }
        // Symbol im Hintergrund zeichnen (liest ggf. ein Bild aus dem Speicher)
        val icon by produceState<ImageBitmap?>(null, app, apps) {
            value = withContext(Dispatchers.Default) { runCatching { Shortcuts.roundIcon(ctx, app, 192).asImageBitmap() }.getOrNull() }
        }
        // Art auf einen Blick: Schalter (ein Befehl) vs. Webseite - Plakette am Symbol und Typ-Zeile
        // in der Hauptfarbe. Bewusst kein Rahmen um die Kachel: wirkte wie ein eingeschalteter Zustand
        val accent = MaterialTheme.colorScheme.primary
        val onAccent = MaterialTheme.colorScheme.onPrimary
        Card(
            onClick = {
                // langes Druecken zum Sortieren ist kein Tipp (sonst loest ein Schalter beim Loslassen aus)
                if (!dragActive && SystemClock.uptimeMillis() - dragEndedAt > 400) startActivity(Shortcuts.launchIntent(ctx, app))
            },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Box {
                if (app.isCamera) CameraPreview(app) else Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        icon?.let { Image(it, null, Modifier.size(64.dp)) } ?: Spacer(Modifier.size(64.dp))
                        Box(
                            Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp).size(26.dp).clip(CircleShape)
                                .background(accent)
                                .border(2.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(when { app.isAction -> Icons.Filled.TouchApp; app.isCamera -> Icons.Filled.Videocam; else -> Icons.Filled.Language }, null,
                                Modifier.size(15.dp), tint = onAccent)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(app.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(when { app.isAction -> R.string.main_kind_action; app.isCamera -> R.string.main_kind_camera; else -> R.string.main_kind_web }), style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold, color = accent)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (app.tunnelId != null) {
                            Icon(Icons.Filled.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(app.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menu = true },
                        modifier = if (app.isCamera) Modifier.padding(6.dp).clip(CircleShape).background(Color(0x66000000)) else Modifier) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.main_menu), tint = if (app.isCamera) Color.White else LocalContentColor.current)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.main_edit)) }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; if (app.requireAuth) guard { startEdit(app.id) } else startEdit(app.id) })
                        if (Shortcuts.pinSupported(ctx)) DropdownMenuItem(
                            text = { Text(stringResource(R.string.main_pin_short)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.AddToHomeScreen, null) },
                            onClick = { menu = false; Shortcuts.pin(ctx, app) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.main_delete)) }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
        }
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.main_delete_title, app.name)) },
            text = { Text(stringResource(R.string.main_delete_app_text)) },
            confirmButton = { TextButton(onClick = {
                confirmDelete = false
                if (safeStore { store.deleteApp(app.id) }) Shortcuts.remove(ctx, app.id)
                reload()
            }) { Text(stringResource(R.string.main_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.main_cancel)) } },
        )
    }

    /** Kamera-Kachel: letztes Bild (aus CameraActivity) gross, darunter Name und wie alt das Bild ist. */
    @Composable
    private fun CameraPreview(app: WebApp) {
        val ctx = LocalContext.current
        var version by remember { mutableIntStateOf(0) }
        // gesperrte Kamera: kein Bild auf der Startseite (auch kein altes)
        val snap by produceState<Pair<Bitmap, Long>?>(null, app, apps, version) {
            value = withContext(Dispatchers.IO) {
                if (app.requireAuth) { runCatching { store.snapshotFile(app.id).delete() }; null }
                else runCatching { store.snapshot(app.id) }.getOrNull()
            }
        }
        // Bei jedem Anzeigen der Startseite: gespeichertes Bild neu einlesen (das Vollbild speichert alle 20 s),
        // und ist es aelter als CAM_REFRESH_MS, Stream kurz in der Kachel zeigen, Bild merken, wieder abbauen.
        // Die Activity wird dabei oft nicht neu erzeugt (Zurueck legt sie seit Android 12 nur in den Hintergrund).
        // Im Hintergrund wird abgebrochen, beim naechsten Anzeigen erneut versucht
        val texture = remember { TextureView(ctx) }
        var live by remember { mutableStateOf(false) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(app.id, app.requireAuth) {
            if (app.requireAuth) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                version++
                val saved = withContext(Dispatchers.IO) { store.snapshotFile(app.id).lastModified() }
                val now = System.currentTimeMillis()
                if (now - maxOf(saved, camTried[app.id] ?: 0L) < CAM_REFRESH_MS) return@repeatOnLifecycle
                val ok = refreshCamera(app, texture) { live = it }
                camTried[app.id] = System.currentTimeMillis()
                if (ok) version++
            }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(app.color))) {
            val s = snap
            if (s != null) Image(s.first.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Filled.Videocam, null, Modifier.align(Alignment.Center).size(56.dp), tint = Color.White.copy(alpha = 0.85f))
            if (live) AndroidView(factory = { texture }, modifier = Modifier.matchParentSize())
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(start = 14.dp, end = 14.dp, top = 20.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Videocam, null, Modifier.size(18.dp), tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val kindText = stringResource(R.string.main_kind_camera)
                    val justNow = stringResource(R.string.main_cam_just_now)
                    val age = s?.let { if (System.currentTimeMillis() - it.second < 60_000) justNow else DateUtils.getRelativeTimeSpanString(it.second) }
                    Text(if (age != null) "$kindText · $age" else kindText,
                        color = Color(0xFFDDE3E8), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                if (app.tunnelId != null) Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = Color.White)
            }
        }
    }

    /**
     * Frisches Kamerabild fuer die Kachel: Weg wie im Vollbild (zuhause direkt, sonst Tunnel), Stream
     * laeuft kurz in der Kachel (TextureView), nach dem ersten Bild + 1,5 s wird es gespeichert.
     * true = neues Bild da. Alles wird danach wieder abgebaut (auch bei Abbruch/Scrollen).
     */
    private suspend fun refreshCamera(app: WebApp, texture: TextureView, showLive: (Boolean) -> Unit): Boolean {
        if (app.requireAuth) return false
        val t = withContext(Dispatchers.IO) { store.tunnel(app.tunnelId) }
        var held = false
        var h: CameraStream.Handle? = null
        try {
            if (t == null || Net.where(app).home) Net.route(app, null)
            else {
                Net.acquire(t.id); held = true
                if (Net.connect(t) != null) return false
                Net.route(app, t.id)
            }
            showLive(true)
            val first = CompletableDeferred<Boolean>()
            h = CameraStream.open(this, app)
            h.player.setVideoTextureView(texture)
            h.player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { first.complete(true) }
                override fun onPlayerError(error: PlaybackException) { first.complete(false) }
            })
            if (withTimeoutOrNull(20_000) { first.await() } != true) return false
            delay(1500)   // das allererste Bild ist oft noch grau/unscharf
            val bmp = texture.bitmap ?: return false
            withContext(Dispatchers.IO) { store.saveSnapshot(app.id, bmp) }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        } finally {
            h?.release()
            showLive(false)
            if (held) t?.let { Net.release(it.id) }
        }
    }

    private fun startEdit(id: String?) {
        draft = null
        ui.editIcon.value = id?.let { store.icon(it) }
        ui.editIconChanged.value = false
        page = Page.EditApp(id)
    }

    // ================================================================ Editor

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun EditPage(p: Page.EditApp) {
        val ctx = LocalContext.current
        val existing = remember(p.id) { p.id?.let { store.app(it) } }
        val base = remember(p.id) {
            draft ?: existing ?: WebApp(name = "", url = "", tunnelId = tunnels.singleOrNull()?.id,
                color = 0xFF1565C0.toInt())   // neue Eintraege blau
        }
        var name by rememberSaveable { mutableStateOf(base.name) }
        var url by rememberSaveable { mutableStateOf(base.url.removePrefix("http://")) }
        var tunnelId by rememberSaveable { mutableStateOf(base.tunnelId) }
        var remote by rememberSaveable { mutableStateOf(base.tunnelId != null) }
        var always by rememberSaveable { mutableStateOf(base.alwaysTunnel) }
        var color by rememberSaveable { mutableIntStateOf(base.color) }
        var screenOn by rememberSaveable { mutableStateOf(base.keepScreenOn) }
        var desktop by rememberSaveable { mutableStateOf(base.desktop) }
        var fullscreen by rememberSaveable { mutableStateOf(base.fullscreen) }
        var advanced by rememberSaveable { mutableStateOf(false) }
        var symbol by rememberSaveable { mutableStateOf(base.symbol) }
        var kind by rememberSaveable { mutableStateOf(base.kind) }
        val a0 = base.action ?: ShellyAction()
        var aGen by rememberSaveable { mutableIntStateOf(a0.gen) }
        var aIp by rememberSaveable { mutableStateOf(a0.ip) }
        var aCh by rememberSaveable { mutableStateOf(a0.channel.toString()) }
        var aMode by rememberSaveable { mutableIntStateOf(a0.mode) }
        var aSecs by rememberSaveable { mutableStateOf(a0.secs.toString()) }
        var aLogin by rememberSaveable { mutableStateOf(a0.user) }
        // Geheimnisse nicht in den Instance-State (landet ausserhalb der App-Verschluesselung), sondern ins ViewModel
        var aPass by rememberSecret("edit.${p.id}.aPass") { a0.pass }
        var needAuth by rememberSaveable { mutableStateOf(base.requireAuth) }
        var aDevice by rememberSaveable { mutableStateOf(a0.device) }
        var aOutputs by rememberSaveable { mutableIntStateOf(0) }          // 0 = unbekannt
        var aNeedsPass by rememberSaveable { mutableStateOf(a0.pass.isNotEmpty()) }
        // Kamera: Zugangsdaten getrennt von der Adresse (werden verschluesselt wie das Shelly-Passwort gespeichert)
        var camLogin by rememberSaveable { mutableStateOf(if (base.isCamera) a0.user else "") }
        var camPass by rememberSecret("edit.${p.id}.camPass") { if (base.isCamera) a0.pass else "" }
        // 0 = gewaehlter Shelly (Zusammenfassung), 1 = suchen, 2 = manuell
        var shellyMode by rememberSaveable { mutableIntStateOf(if (a0.ip.isBlank()) 1 else if (a0.device.isEmpty()) 2 else 0) }
        val found = remember { mutableStateListOf<FoundShelly>() }
        var scanning by remember { mutableStateOf(false) }
        var scanned by remember { mutableStateOf(false) }
        var scanProgress by remember { mutableStateOf(0f) }
        var scanHint by remember { mutableStateOf<String?>(null) }
        val scanScope = rememberCoroutineScope()
        var iconMenu by remember { mutableStateOf(false) }
        var symbolPicker by remember { mutableStateOf(false) }
        var howToUnpin by remember { mutableStateOf(false) }
        // Bild im ViewModel (passt nicht ins Bundle), gesetzt in startEdit()
        var icon by ui.editIcon
        var iconChanged by ui.editIconChanged
        var confirmDelete by remember { mutableStateOf(false) }

        val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { Shortcuts.loadSquare(ctx, it) }?.let { icon = it; iconChanged = true }
        }

        fun currentAction() = ShellyAction(aGen, aIp.trim(), aCh.toIntOrNull() ?: 0, aMode,
            (aSecs.toIntOrNull() ?: 1).coerceIn(1, 60), aLogin.trim().ifEmpty { "admin" }, aPass,
            if (shellyMode == 2) "" else aDevice)

        fun current(): WebApp {
            val isAct = kind == KIND_ACTION
            val isCam = kind == KIND_CAMERA
            val act = when {
                isAct -> currentAction()
                isCam -> ShellyAction(gen = 0, user = camLogin.trim(), pass = camPass)
                else -> null
            }
            val fullUrl = when {
                isAct -> act!!.url(normalizeUrl(url))
                isCam -> rtspUrl(url)
                else -> normalizeUrl(url)
            }
            return base.copy(name = name.trim(), url = fullUrl, tunnelId = if (remote) tunnelId else null, alwaysTunnel = remote && always,
                color = color, keepScreenOn = screenOn, desktop = desktop && kind == KIND_WEB, fullscreen = fullscreen, symbol = symbol, kind = kind, action = act, requireAuth = needAuth)
        }

        /** Prueft und speichert; null = Eingabe unvollstaendig (Hinweis wurde gezeigt). */
        fun persist(): WebApp? {
            if (name.isBlank()) { toast(getString(R.string.main_err_name)); return null }
            if (kind == KIND_ACTION && aGen != 0 && shellyMode == 1) { toast(getString(R.string.main_err_pick_shelly)); return null }
            val app = current()
            if (app.host.isEmpty()) { toast(getString(if (app.isAction && aGen != 0) R.string.main_err_shelly_ip else R.string.main_err_address)); return null }
            // Loopback/Link-Local: waere der eigene lokale Proxy oder eine andere App auf dem Handy
            if (Hosts.isForbidden(app.host) || (app.isAction && aGen != 0 && Hosts.isForbidden(app.action?.ip))) {
                toast(getString(R.string.main_err_local_address)); return null
            }
            val ok = safeStore {
                store.saveApp(app)
                if (iconChanged) store.saveIcon(app.id, icon)
                // gesperrte Kamera: vorhandenes Kachelbild weg
                if (app.isCamera && app.requireAuth) store.snapshotFile(app.id).delete()
            }
            if (!ok) return null
            Shortcuts.update(ctx, app)
            return app
        }

        fun save() {
            persist() ?: return
            reload()
            page = Page.Home
        }

        /** Taste "Auf den Startbildschirm": speichert automatisch und legt das Symbol an. */
        fun saveAndPin() {
            val app = persist() ?: return
            Shortcuts.pin(ctx, app)
            reload()
            page = Page.Home
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(if (existing == null) R.string.main_new else R.string.main_edit)) },
                    navigationIcon = { IconButton(onClick = { page = Page.Home }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.main_back)) } },
                    actions = { TextButton(onClick = { save() }) { Text(stringResource(R.string.main_save), fontWeight = FontWeight.SemiBold) } },
                )
            },
        ) { pad ->
            Column(
                Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            ) {
                // --- Symbol: Vorschau antippen -> "Symbol" oder "Bild"
                Box(Modifier.padding(vertical = 12.dp).size(92.dp).clickable { iconMenu = true }) {
                    val preview = current()
                    val bmp = remember(icon, color, name, symbol) { Shortcuts.render(preview, icon, 192, adaptive = false) }
                    Image(bmp.asImageBitmap(), stringResource(R.string.main_change_icon), Modifier.size(84.dp))
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(32.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
                }

                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.main_name)) }, placeholder = { Text(stringResource(R.string.main_name_hint_app)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pick(kind == KIND_WEB, stringResource(R.string.main_kind_web), Icons.Filled.Language) { kind = KIND_WEB }
                    Pick(kind == KIND_ACTION, stringResource(R.string.main_kind_action), Icons.Filled.TouchApp) {
                        if (kind != KIND_ACTION && existing == null) needAuth = true
                        kind = KIND_ACTION
                        if (symbol == null && icon == null) symbol = "garage"
                    }
                    Pick(kind == KIND_CAMERA, stringResource(R.string.main_kind_camera), Icons.Filled.Videocam) {
                        if (kind == KIND_ACTION && existing == null) needAuth = false
                        kind = KIND_CAMERA
                        if (symbol == null && icon == null) symbol = "camera"
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (kind == KIND_WEB) {
                    OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.main_url_label)) },
                        placeholder = { Text("192.168.178.1") },
                        supportingText = { Text(stringResource(R.string.main_url_help)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth())
                } else if (kind == KIND_CAMERA) {
                    OutlinedTextField(url, { v ->
                        // eingefuegter Link mit Zugangsdaten (z. B. aus der Eufy-App): in die Felder aufteilen
                        val m = splitCred(v.trim())
                        if (m != null) {
                            val (scheme, info, rest) = m
                            camLogin = decodeCred(info.substringBefore(':'))
                            if (':' in info) camPass = decodeCred(info.substringAfter(':'))
                            url = scheme + rest
                        } else url = v
                    }, label = { Text(stringResource(R.string.main_camera_url_label)) },
                        placeholder = { Text("rtsp://192.168.178.40/live0") },
                        supportingText = { Text(stringResource(R.string.main_camera_url_help)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(camLogin, { camLogin = it }, label = { Text(stringResource(R.string.main_camera_user)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(camPass, { camPass = it }, label = { Text(stringResource(R.string.main_camera_pass)) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text(stringResource(R.string.main_action_hint),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.main_device), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pick(aGen != 0, "Shelly", Icons.Filled.Power) { if (aGen == 0) aGen = 2 }
                        Pick(aGen == 0, stringResource(R.string.main_custom_url), Icons.Filled.Link) { aGen = 0 }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (aGen == 0) {
                        OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.main_action_url_label)) },
                            placeholder = { Text("192.168.178.50/relay/0?turn=on") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth())
                    } else {
                        when (shellyMode) {
                            // ---- gewaehlter Shelly
                            0 -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Power, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(aDevice.ifEmpty { "Shelly" }, fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text("$aIp · ${if (aGen == 1) "Gen1" else "Gen2+"}", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                    TextButton(onClick = { shellyMode = 1 }) { Text(stringResource(R.string.main_change)) }
                                }
                            }
                            // ---- suchen (erste Wahl) + manuell (letzte Option)
                            1 -> {
                                Button(
                                    enabled = !scanning,
                                    onClick = {
                                        scanHint = Discovery.unavailableReason(ctx)
                                        if (scanHint == null) {
                                            found.clear(); scanning = true; scanned = false; scanProgress = 0f
                                            scanScope.launch {
                                                Discovery.scan(ctx, { d, t -> scanProgress = d.toFloat() / t }) { f ->
                                                    if (found.none { it.ip == f.ip }) found += f
                                                }
                                                scanning = false; scanned = true
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                ) {
                                    Icon(Icons.Filled.Search, null); Spacer(Modifier.width(10.dp))
                                    Text(stringResource(if (scanning) R.string.main_scan_running else R.string.main_scan_start))
                                }
                                if (scanning) {
                                    Spacer(Modifier.height(8.dp))
                                    LinearProgressIndicator(progress = { scanProgress }, modifier = Modifier.fillMaxWidth())
                                }
                                scanHint?.let {
                                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                                }
                                found.forEach { f ->
                                    Spacer(Modifier.height(8.dp))
                                    Card(onClick = {
                                        aGen = f.gen; aIp = f.ip; aCh = "0"; aOutputs = f.outputs
                                        aDevice = "${f.name} · ${f.model}"; aNeedsPass = f.auth
                                        if (name.isBlank()) name = f.name
                                        shellyMode = 0
                                    }, modifier = Modifier.fillMaxWidth()) {
                                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Power, null, tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(f.name, fontWeight = FontWeight.SemiBold)
                                                Text(if (f.auth) stringResource(R.string.main_shelly_found_auth, f.model, f.ip) else "${f.model} · ${f.ip}",
                                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                                if (scanned && found.isEmpty()) Text(stringResource(R.string.main_scan_none),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                                TextButton(onClick = { shellyMode = 2; aDevice = "" }, modifier = Modifier.padding(top = 4.dp)) {
                                    Text(stringResource(R.string.main_manual_setup))
                                }
                            }
                            // ---- manuell
                            else -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Pick(aGen == 2, stringResource(R.string.main_shelly_gen2), null) { aGen = 2 }
                                    Pick(aGen == 1, stringResource(R.string.main_shelly_gen1), null) { aGen = 1 }
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(aIp, { aIp = it }, label = { Text(stringResource(R.string.main_shelly_ip_label)) }, placeholder = { Text("192.168.178.60") },
                                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier.fillMaxWidth())
                                TextButton(onClick = { shellyMode = 1 }) { Text(stringResource(R.string.main_back_to_search)) }
                            }
                        }
                        // Kanal: nur wenn der Shelly mehrere hat (oder unbekannt / manuell)
                        if (shellyMode != 1 && (aOutputs != 1 || shellyMode == 2)) {
                            Spacer(Modifier.height(8.dp))
                            if (aOutputs > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                repeat(aOutputs) { i -> Pick(aCh == "$i", stringResource(R.string.main_channel_n, i + 1), null) { aCh = "$i" } }
                            } else OutlinedTextField(aCh, { aCh = it.filter(Char::isDigit).take(1) }, label = { Text(stringResource(R.string.main_channel)) },
                                supportingText = { Text(stringResource(R.string.main_channel_help)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(160.dp))
                        }
                        if (shellyMode != 1) {
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.main_action), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Choice(aMode == 0, stringResource(R.string.main_mode_pulse), stringResource(R.string.main_mode_pulse_sub)) { aMode = 0 }
                            if (aMode == 0) OutlinedTextField(aSecs, { aSecs = it.filter(Char::isDigit).take(2) },
                                label = { Text(stringResource(R.string.main_pulse_secs)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.padding(start = 48.dp).width(180.dp))
                            Choice(aMode == 1, stringResource(R.string.main_mode_on), "") { aMode = 1 }
                            Choice(aMode == 2, stringResource(R.string.main_mode_off), "") { aMode = 2 }
                            Choice(aMode == 3, stringResource(R.string.main_mode_toggle), "") { aMode = 3 }
                            if (aNeedsPass || shellyMode == 2 || aPass.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                if (aGen == 1) OutlinedTextField(aLogin, { aLogin = it }, label = { Text(stringResource(R.string.main_shelly_user)) },
                                    singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(aPass, { aPass = it },
                                    label = { Text(stringResource(if (aNeedsPass) R.string.main_shelly_pass else R.string.main_shelly_pass_optional)) },
                                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    supportingText = { Text(stringResource(R.string.main_shelly_pass_help)) },
                                    modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                IconSwitch(Icons.Filled.Fingerprint, stringResource(R.string.main_require_auth), needAuth) { on ->
                    // Sperre einer gesperrten Kachel abschalten: erst entsperren
                    if (!on && existing?.requireAuth == true) guard { needAuth = false } else needAuth = on
                }

                Spacer(Modifier.height(12.dp))
                // VPN in drei Stufen: Kein / Smart (zuhause direkt) / Immer
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Icon(Icons.Filled.VpnKey, null, tint = if (remote) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.main_vpn))
                }
                val pickVpn = { on: Boolean, alw: Boolean ->
                    remote = on; always = alw
                    if (on && tunnelId == null) tunnelId = tunnels.firstOrNull()?.id
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pick(!remote, stringResource(R.string.main_vpn_none), null) { pickVpn(false, false) }
                    Pick(remote && !always, stringResource(R.string.main_vpn_smart), null) { pickVpn(true, false) }
                    Pick(remote && always, stringResource(R.string.main_vpn_always), null) { pickVpn(true, true) }
                }
                Text(
                    when {
                        !remote -> stringResource(R.string.main_vpn_none_hint)
                        always -> stringResource(R.string.main_vpn_always_hint)
                        else -> stringResource(R.string.main_vpn_smart_hint)
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (remote) {
                    tunnels.forEach { t ->
                        Choice(tunnelId == t.id, t.name, t.endpoint) { tunnelId = t.id }
                    }
                    TextButton(onClick = {
                        draft = current()   // Bild bleibt in ui.editIcon
                        page = Page.AddTunnel(returnTo = p)
                    }) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.main_new_tunnel))
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                if (kind == KIND_WEB) {
                    // Erweitert (aufklappbar): Vollbild, Bildschirm an, Desktop
                    Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.main_advanced), Modifier.weight(1f))
                        Icon(if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                    }
                    if (advanced) {
                        IconSwitch(Icons.Filled.Fullscreen, stringResource(R.string.main_fullscreen), fullscreen) { fullscreen = it }
                        IconSwitch(Icons.Filled.LightMode, stringResource(R.string.main_keep_screen_on), screenOn) { screenOn = it }
                        IconSwitch(Icons.Filled.Computer, stringResource(R.string.main_desktop_site), desktop) { desktop = it }
                    }
                }
                if (Shortcuts.pinSupported(ctx)) {
                    Spacer(Modifier.height(12.dp))
                    if (existing != null && existing.id in pinnedIds) {
                        OutlinedButton(onClick = { howToUnpin = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Icon(Icons.Filled.Check, null); Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.main_pinned))
                        }
                    } else {
                        Button(onClick = { saveAndPin() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Icon(Icons.AutoMirrored.Filled.AddToHomeScreen, null); Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.main_pin))
                        }
                    }
                }
                if (existing != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.main_delete_app), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
        if (iconMenu) AlertDialog(
            onDismissRequest = { iconMenu = false },
            title = { Text(stringResource(R.string.main_icon)) },
            text = {
                Column {
                    ListItem(headlineContent = { Text(stringResource(R.string.main_pick_symbol)) },
                        supportingContent = { Text(stringResource(R.string.main_pick_symbol_sub)) },
                        leadingContent = { Icon(Icons.Filled.Apps, null) },
                        modifier = Modifier.clickable { iconMenu = false; symbolPicker = true })
                    ListItem(headlineContent = { Text(stringResource(R.string.main_pick_image)) },
                        supportingContent = { Text(stringResource(R.string.main_pick_image_sub)) },
                        leadingContent = { Icon(Icons.Filled.PhotoLibrary, null) },
                        modifier = Modifier.clickable {
                            iconMenu = false
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                    if (icon != null) ListItem(headlineContent = { Text(stringResource(R.string.main_remove_image)) },
                        leadingContent = { Icon(Icons.Filled.Delete, null) },
                        modifier = Modifier.clickable { iconMenu = false; icon = null; iconChanged = true })
                }
            },
            confirmButton = { TextButton(onClick = { iconMenu = false }) { Text(stringResource(R.string.main_cancel)) } },
        )
        if (symbolPicker) AlertDialog(
            onDismissRequest = { symbolPicker = false },
            title = { Text(stringResource(R.string.main_pick_symbol)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SymbolChip(selected = symbol == null, color = color, label = stringResource(R.string.app_name), onClick = { symbol = null }) {
                            Image(remember { Shortcuts.logoBitmap(96).asImageBitmap() }, stringResource(R.string.app_name), Modifier.size(26.dp))
                        }
                        Symbols.all.forEach { sym ->
                            val symLabel = stringResource(sym.labelRes)
                            SymbolChip(selected = symbol == sym.key, color = color, label = symLabel, onClick = { symbol = sym.key }) {
                                Icon(sym.icon, symLabel, tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.main_color), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        palette.forEach { c ->
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                                    .border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    .clickable { color = c },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                symbolPicker = false
                if (icon != null) { icon = null; iconChanged = true }   // Symbol gewaehlt -> eigenes Bild weg
            }) { Text(stringResource(R.string.main_done)) } },
        )
        if (howToUnpin) AlertDialog(
            onDismissRequest = { howToUnpin = false },
            title = { Text(stringResource(R.string.main_unpin_title)) },
            text = { Text(stringResource(R.string.main_unpin_text)) },
            confirmButton = { TextButton(onClick = { howToUnpin = false }) { Text(stringResource(R.string.main_got_it)) } },
        )
        if (confirmDelete && existing != null) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.main_delete_title, existing.name)) },
            confirmButton = { TextButton(onClick = {
                if (safeStore { store.deleteApp(existing.id) }) Shortcuts.remove(ctx, existing.id)
                reload(); page = Page.Home
            }) { Text(stringResource(R.string.main_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.main_cancel)) } },
        )

        // neue Verbindung wurde gerade angelegt -> direkt auswaehlen
        LaunchedEffect(tunnels.size) {
            if (draft != null && tunnels.isNotEmpty() && tunnels.none { it.id == tunnelId }) tunnelId = tunnels.last().id
        }
    }

    /** Auswahl-Chip: gewaehlt = kraeftige Hauptfarbe, damit die Auswahl deutlich erkennbar ist. */
    @Composable
    private fun Pick(selected: Boolean, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
        FilterChip(
            selected = selected, onClick = onClick,
            label = { Text(text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
            leadingIcon = icon?.let { ic -> { Icon(ic, null, Modifier.size(18.dp)) } },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
    }

    @Composable
    private fun SymbolChip(selected: Boolean, color: Int, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(color))
                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(12.dp))
                .clickable(onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { content() }
    }

    @Composable
    private fun Choice(selected: Boolean, title: String, sub: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).selectable(selected, onClick = onClick).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected, onClick)
            Column {
                Text(title)
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    /** Kurze Beschreibung: wozu die App da ist und wie sie "zuhause" erkennt. */
    @Composable
    private fun InfoDialog(onClose: () -> Unit) {
        @Composable fun H(t: String) = Text(t, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        @Composable fun P(t: String) = Text(t, style = MaterialTheme.typography.bodyMedium)
        AlertDialog(
            onDismissRequest = onClose,
            icon = { Icon(Icons.Filled.Info, null) },
            title = { Text(stringResource(R.string.app_name)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    P(stringResource(R.string.main_info_intro))
                    // Anleitung auf der Webseite, je Sprache eigene Adresse
                    val web = stringResource(R.string.main_info_web_url)
                    TextButton(onClick = {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web))) }
                    }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.main_info_web, web.removePrefix("https://").trimEnd('/')))
                    }
                    H(stringResource(R.string.main_info_where_title))
                    P(stringResource(R.string.main_info_where_text))
                    H(stringResource(R.string.main_info_wg_ts_title))
                    P(stringResource(R.string.main_info_wg_ts_text))
                    H(stringResource(R.string.main_info_novpn_title))
                    P(stringResource(R.string.main_info_novpn_text))
                    H(stringResource(R.string.main_info_security_title))
                    P(stringResource(R.string.main_info_security_text))
                    H(stringResource(R.string.main_info_contact_title))
                    P(stringResource(R.string.main_info_contact_text))
                    val mail = stringResource(R.string.main_info_mail)
                    TextButton(onClick = {
                        runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$mail"))) }
                    }, contentPadding = PaddingValues(0.dp)) { Text(mail) }
                    H(stringResource(R.string.main_info_imprint_title))
                    P(stringResource(R.string.main_info_imprint_text))
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.main_ok)) } },
            dismissButton = { TextButton(onClick = { onClose(); page = Page.Licenses }) { Text(stringResource(R.string.main_licenses)) } },
        )
    }

    @Composable
    private fun IconSwitch(icon: ImageVector, title: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(title, Modifier.weight(1f))
            Switch(value, onChange)
        }
    }

    @Composable
    private fun SwitchRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(value, onChange)
        }
    }

    // ========================================================= Verbindungen

    @Composable
    private fun TunnelsContent() {
        val scope = rememberCoroutineScope()
        val results = remember { mutableStateOf(mapOf<String, String?>()) }
        val testing = remember { mutableStateOf(setOf<String>()) }
        val probes = remember { mutableStateOf(mapOf<String, String>()) }   // Tailscale-Diagnose je Tunnel
        var confirmDelete by remember { mutableStateOf<Tunnel?>(null) }
        val ctx = LocalContext.current
        // Tailscale-Zustand alle 2 s (nur fuer gestartete Verbindungen)
        var tsStates by remember { mutableStateOf(mapOf<String, Net.TsStatus>()) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(tunnels) {
            // nur solange die App sichtbar ist
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    tsStates = withContext(Dispatchers.IO) {
                        tunnels.filter { it.isTailscale && Wgbridge.isRunning(it.id) }.associate { it.id to Net.tailscaleStatus(it.id) }
                    }
                    delay(2000)
                }
            }
        }
        run {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tunnels.isEmpty() && ui.loaded) item {
                    Text(stringResource(R.string.main_tunnels_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                }
                items(tunnels, key = { it.id }) { t ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.VpnKey, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(t.name, fontWeight = FontWeight.SemiBold)
                                    Text(t.endpoint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (t.isTailscale) tsStates[t.id]?.let { st ->
                                        Text(
                                            when (st.state) {
                                                "Running" -> (if (st.user.isNotEmpty()) stringResource(R.string.main_ts_signed_in_as, st.user)
                                                        else stringResource(R.string.main_ts_signed_in)) + " · " +
                                                    (if (st.routes.isNotEmpty()) stringResource(R.string.main_ts_home_net, st.routes)
                                                        else stringResource(R.string.main_ts_no_home_net))
                                                "NeedsLogin" -> stringResource(R.string.main_ts_needs_login)
                                                "NeedsMachineAuth" -> stringResource(R.string.main_ts_needs_approval)
                                                else -> stringResource(R.string.main_ts_connecting_short)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (st.state == "Running" && st.routes.isNotEmpty()) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    val used = apps.filter { it.tunnelId == t.id }.joinToString { it.name }
                                    if (used.isNotEmpty()) Text(stringResource(R.string.main_tunnel_used_by, used), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                // Tunnel-Seite zeigt Schluessel/Auth-Key: erst entsperren
                                IconButton(onClick = { guard { page = Page.AddTunnel(null, t.id) } }) { Icon(Icons.Filled.Edit, stringResource(R.string.main_edit)) }
                                IconButton(onClick = { confirmDelete = t }) { Icon(Icons.Filled.Delete, stringResource(R.string.main_delete)) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(enabled = t.id !in testing.value, onClick = {
                                    testing.value += t.id
                                    scope.launch {
                                        if (t.isTailscale) {
                                            val (ok, detail) = Net.testTailscale(t, apps)
                                            results.value += (t.id to (if (ok) "OK" else detail))
                                            probes.value += (t.id to detail)
                                        } else {
                                            val r = Net.test(t)
                                            results.value += (t.id to (r ?: "OK"))
                                        }
                                        testing.value -= t.id
                                    }
                                }) { Text(stringResource(R.string.main_test)) }
                                if (t.isTailscale && tsStates[t.id]?.state != "Running") {
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { guard { page = Page.AddTunnel(null, t.id) } }) { Text(stringResource(R.string.main_connect)) }
                                }
                                Spacer(Modifier.width(12.dp))
                                when {
                                    t.id in testing.value -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    results.value[t.id] == "OK" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E7D32)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.main_connected))
                                    }
                                    results.value[t.id] != null -> Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (results.value[t.id] == "OK") probes.value[t.id]?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp))
                            }
                            results.value[t.id]?.takeIf { it != "OK" }?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
        }
        confirmDelete?.let { t ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text(stringResource(R.string.main_delete_title, t.name)) },
                text = { Text(stringResource(R.string.main_delete_tunnel_text)) },
                confirmButton = { TextButton(onClick = { safeStore { store.deleteTunnel(t.id) }; confirmDelete = null; reload() }) { Text(stringResource(R.string.main_delete)) } },
                dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.main_cancel)) } },
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AddTunnelPage(returnTo: Page.EditApp?, editId: String? = null) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        // Schluessel/Auth-Key auf dem Bildschirm: keine Screenshots/Bildschirmaufnahmen
        DisposableEffect(Unit) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        val editing = remember(editId) { store.tunnel(editId) }
        var name by rememberSaveable { mutableStateOf(editing?.name ?: if (tunnels.isEmpty()) getString(R.string.main_tunnel_default_name) else "") }
        var type by rememberSaveable { mutableStateOf(editing?.type ?: TUNNEL_WG) }
        // Config enthaelt den privaten WireGuard-Schluessel -> nicht in den Instance-State, sondern ins ViewModel
        var config by rememberSecret("tunnel.$editId.config") { if (editing?.isTailscale == true) "" else editing?.config ?: "" }
        var error by rememberSaveable { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val ts0 = remember { runCatching { org.json.JSONObject(if (editing?.isTailscale == true) editing.config else "{}") }.getOrNull() }
        var tsControl by rememberSaveable { mutableStateOf(ts0?.optString("control").orEmpty()) }
        var tsKey by rememberSecret("tunnel.$editId.tsKey") { ts0?.optString("authkey").orEmpty() }
        var tsAdvanced by rememberSaveable { mutableStateOf(tsControl.isNotEmpty() || tsKey.isNotEmpty()) }

        fun accept(text: String?) {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return
            if (Share.isPayload(t)) { importPayload(t); return }
            config = t
            error = Wgbridge.validateConfig(t).ifEmpty { null }?.let { getString(R.string.main_wg_invalid, it) }
        }

        val scan = rememberLauncherForActivityResult(ScanContract()) { r -> accept(r.contents) }
        val pickQr = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val text = withContext(Dispatchers.Default) { Share.qrFromImage(ctx, uri) }
                text?.let { accept(it) } ?: toast(getString(R.string.main_no_qr))
            }
        }
        val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                accept(withContext(Dispatchers.IO) {
                    runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readAtMost(64_000).decodeToString() } }.getOrNull()
                })
            }
        }

        // gespeicherter Tailscale-Tunnel (nur die id ueberlebt das Drehen, der Tunnel kommt aus der Liste)
        var tsSavedId by rememberSaveable { mutableStateOf(editing?.takeIf { it.isTailscale }?.id) }
        val tsSaved = tsSavedId?.let { id -> tunnels.firstOrNull { it.id == id } }
        var tsConnecting by rememberSaveable { mutableStateOf(false) }
        fun tsConfigJson() = org.json.JSONObject().apply {
            tsControl.trim().takeIf { it.isNotEmpty() }?.let { put("control", if ("://" in it) it else "https://$it") }
            tsKey.trim().takeIf { it.isNotEmpty() }?.let { put("authkey", it) }
        }.toString()
        /** Tailscale-Tunnel speichern (Name darf leer sein -> Standardname), ohne die Seite zu verlassen. null = ging nicht. */
        fun persistTs(): Tunnel? {
            val tsName = name.trim().ifEmpty { getString(R.string.main_tunnel_ts_default_name) }
            val t = (tsSaved ?: editing)?.copy(name = tsName, config = tsConfigJson())
                ?: Tunnel(name = tsName, config = tsConfigJson(), type = TUNNEL_TS)
            val ok = safeStore {
                store.saveTunnel(t)
                assignAfterTunnel.forEach { id -> store.app(id)?.let { store.saveApp(it.copy(tunnelId = t.id)) } }
            }
            if (!ok) return null
            assignAfterTunnel = emptyList()
            if (returnTo != null) draft = draft?.copy(tunnelId = t.id)
            if (name.isBlank()) name = t.name
            tsSavedId = t.id
            reload()
            return t
        }

        fun save() {
            if (type == TUNNEL_TS) { if (persistTs() != null) page = returnTo ?: Page.Tunnels; return }
            if (name.isBlank()) { toast(getString(R.string.main_err_name)); return }
            if (config.isBlank() || error != null) { toast(getString(R.string.main_err_config)); return }
            val cfg = config
            busy = true
            val t = editing?.copy(name = name.trim(), config = cfg) ?: Tunnel(name = name.trim(), config = cfg, type = type)
            val ok = safeStore {
                store.saveTunnel(t)   // gleiche id -> ersetzt; der Go-Kern startet bei geaenderter Konfiguration neu
                assignAfterTunnel.forEach { id -> store.app(id)?.let { store.saveApp(it.copy(tunnelId = t.id)) } }
            }
            if (!ok) { busy = false; return }
            if (returnTo != null) draft = draft?.copy(tunnelId = t.id)   // neue Verbindung im Editor gleich auswaehlen
            val assigned = assignAfterTunnel.isNotEmpty()
            assignAfterTunnel = emptyList()
            reload()
            scope.launch {
                val r = Net.test(t)
                busy = false
                if (r == null) toast(getString(R.string.main_saved_ok))
                else toast(getString(R.string.main_saved_test_failed))
                page = returnTo ?: if (assigned) Page.Home else Page.Tunnels
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(if (editing != null) R.string.main_edit_tunnel else R.string.main_new_tunnel)) },
                    navigationIcon = { IconButton(onClick = { page = returnTo ?: Page.Tunnels }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.main_back)) } },
                    actions = {
                        if (busy) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(22.dp), strokeWidth = 2.dp)
                        else TextButton(onClick = { save() }) { Text(stringResource(R.string.main_save), fontWeight = FontWeight.SemiBold) }
                    },
                )
            },
        ) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.main_name)) }, placeholder = { Text(stringResource(R.string.main_name_hint_tunnel)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(20.dp))
                if (assignAfterTunnel.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                        Text(stringResource(R.string.main_assign_hint), Modifier.padding(16.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (editing == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pick(type == TUNNEL_WG, "WireGuard", Icons.Filled.VpnKey) { type = TUNNEL_WG }
                        Pick(type == TUNNEL_TS, "Tailscale", Icons.Filled.Hub) { type = TUNNEL_TS }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (type == TUNNEL_TS) {
                    TailscaleFields(tsControl, { tsControl = it }, tsKey, { tsKey = it }, tsAdvanced, { tsAdvanced = it })
                    Spacer(Modifier.height(16.dp))
                    val ts = tsSaved
                    if (tsConnecting && ts != null) {
                        TailscaleConnect(ts, onDone = { page = returnTo ?: Page.Tunnels })
                    } else {
                        Button(onClick = { if (persistTs() != null) tsConnecting = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Icon(Icons.Filled.Hub, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.main_connect), fontSize = 16.sp)
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    return@Column
                }
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.main_fritz_title), fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.main_fritz_steps),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                FilledTonalButton(onClick = {
                    scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt(getString(R.string.main_scan_prompt_wg)).setBeepEnabled(false).setOrientationLocked(false))
                }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.main_scan_qr), fontSize = 16.sp)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    pickQr.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PhotoLibrary, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.main_qr_from_image))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Description, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.main_pick_conf))
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    config, { accept(it); config = it },
                    label = { Text(stringResource(R.string.main_paste_config)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 5, modifier = Modifier.fillMaxWidth(),
                )
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    config.isNotBlank() -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E7D32)); Spacer(Modifier.width(6.dp))
                        val ep = Tunnel(name = "", config = config).endpoint
                        Text(if (ep.isNotEmpty()) stringResource(R.string.main_config_ok_endpoint, ep) else stringResource(R.string.main_config_ok))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.main_wg_key_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    /**
     * Tailscale verbinden, jeder Schritt sichtbar: starten -> Anmeldeseite im Browser (automatisch,
     * mit Pruefung ob der Browser wirklich aufging) -> warten -> verbunden (+ Heimnetz-Route).
     */
    @Composable
    private fun TailscaleConnect(t: Tunnel, onDone: () -> Unit) {
        val ctx = LocalContext.current
        val clipboard = LocalClipboardManager.current  // LocalClipboard (suspend) waere neuer, setText reicht hier
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var attempt by remember { mutableIntStateOf(0) }
        var st by remember { mutableStateOf<Net.TsStatus?>(null) }
        var startError by remember { mutableStateOf<String?>(null) }
        var slow by remember { mutableStateOf(false) }
        var browserFailed by remember { mutableStateOf(false) }
        var showLog by remember { mutableStateOf(false) }
        var paused by remember { mutableStateOf(false) }
        var autoOpened by rememberSaveable { mutableStateOf(false) }   // nach Drehen nicht erneut oeffnen
        val scope = rememberCoroutineScope()

        DisposableEffect(lifecycle) {
            val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) paused = true }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }
        // Tunnel offen halten, solange die Seite sichtbar ist (+ Nachlauf fuer die Anmeldung im Browser)
        DisposableEffect(t.id) {
            Net.acquire(t.id)
            onDispose { Net.release(t.id); Net.holdFor(t.id, 5 * 60_000L) }
        }

        fun openLogin(url: String) {
            browserFailed = false
            paused = false
            val ok = runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            }.isSuccess
            if (!ok) { browserFailed = true; return }
            // Ging der Browser wirklich auf? Dann pausiert diese Activity kurz danach.
            scope.launch { delay(2500); if (!paused) browserFailed = true }
        }

        LaunchedEffect(t.id, attempt) {
            startError = null; slow = false; st = null
            startError = Net.startTailscale(t)
            if (startError != null) return@LaunchedEffect
            val started = System.currentTimeMillis()
            while (true) {
                val s = withContext(Dispatchers.IO) { Net.tailscaleStatus(t.id) }
                st = s
                if (s.authUrl.isNotEmpty() && !autoOpened) { autoOpened = true; openLogin(s.authUrl) }
                if (s.state != "Running" && s.authUrl.isEmpty() && System.currentTimeMillis() - started > 20_000) slow = true
                delay(700)
            }
        }

        val s = st
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                when {
                    startError != null -> {
                        Text(stringResource(R.string.main_ts_start_failed), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                        Text(startError!!, style = MaterialTheme.typography.bodySmall)
                    }
                    s?.state == "Running" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E7D32)); Spacer(Modifier.width(8.dp))
                            Text(if (s.user.isNotEmpty()) stringResource(R.string.main_connected_as, s.user) else stringResource(R.string.main_connected), fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(6.dp))
                        if (s.routes.isNotEmpty()) Text(stringResource(R.string.main_ts_home_net_label, s.routes))
                        else Text(stringResource(R.string.main_ts_no_routes), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.main_done)) }
                    }
                    s?.state == "NeedsMachineAuth" -> {
                        Text(stringResource(R.string.main_ts_approve_title), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.main_ts_approve_text),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    s != null && s.authUrl.isNotEmpty() -> {
                        Text(stringResource(R.string.main_ts_login_title), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(if (browserFailed) R.string.main_ts_browser_failed
                            else R.string.main_ts_login_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (browserFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { openLogin(s.authUrl) }) { Text(stringResource(R.string.main_ts_open_login)) }
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(s.authUrl)); toast(getString(R.string.main_link_copied)) }) {
                                Text(stringResource(R.string.main_copy_link))
                            }
                        }
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                        Text(stringResource(if (slow) R.string.main_ts_no_answer else R.string.main_ts_connecting))
                    }
                }
                if (startError != null || slow || browserFailed) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { autoOpened = false; attempt++ }) { Text(stringResource(R.string.main_retry)) }
                }
                TextButton(onClick = { showLog = !showLog }) { Text(stringResource(if (showLog) R.string.main_details_hide else R.string.main_details)) }
                if (showLog) {
                    val noLog = stringResource(R.string.main_log_empty)
                    val log = remember(st, showLog) { Wgbridge.tailscaleLog(t.id).ifEmpty { noLog } }
                    Text(log.lines().takeLast(25).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp))
                    TextButton(onClick = { clipboard.setText(AnnotatedString(Wgbridge.tailscaleLog(t.id))); toast(getString(R.string.main_log_copied)) }) {
                        Text(stringResource(R.string.main_copy_log))
                    }
                }
            }
        }
    }

    /** Tailscale: kurze Anleitung + optional eigener Server (Headscale) und Auth-Key. */
    @Composable
    private fun TailscaleFields(
        control: String, onControl: (String) -> Unit, key: String, onKey: (String) -> Unit,
        advanced: Boolean, onAdvanced: (Boolean) -> Unit,
    ) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.main_ts_fields_title), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.main_ts_fields_steps),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Row(Modifier.fillMaxWidth().clickable { onAdvanced(!advanced) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.main_advanced), Modifier.weight(1f))
            Icon(if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        if (advanced) {
            OutlinedTextField(control, onControl, label = { Text(stringResource(R.string.main_ts_control_label)) },
                placeholder = { Text(stringResource(R.string.main_ts_control_hint)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            // Auth-Key wie ein Passwort verdeckt anzeigen
            OutlinedTextField(key, onKey, label = { Text(stringResource(R.string.main_ts_authkey_label)) },
                placeholder = { Text("tskey-auth-…") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.main_ts_key_note),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LicensesPage() {
        val ctx = LocalContext.current
        val entries = remember { Licenses.all(ctx) }
        var open by remember { mutableStateOf<String?>(null) }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.main_licenses)) },
                    navigationIcon = { IconButton(onClick = { page = Page.Home }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.main_back)) } },
                )
            },
        ) { pad ->
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp)) {
                item {
                    Text(stringResource(R.string.main_licenses_intro),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp))
                }
                items(entries, key = { it.name }) { e ->
                    Column(Modifier.fillMaxWidth().clickable { open = if (open == e.name) null else e.name }.padding(vertical = 10.dp)) {
                        Text(e.name, fontWeight = FontWeight.SemiBold)
                        Text(e.license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (open == e.name) Text(e.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    // ============================================================= Teilen

    /** Export lesen und pruefen (im Hintergrund), dann erst nach Bestaetigung uebernehmen. */
    private fun importPayload(text: String) {
        lifecycleScope.launch {
            val plan = withContext(Dispatchers.IO) { runCatching { Share.plan(this@MainActivity, text) }.getOrNull() }
            when {
                plan == null -> toast(getString(R.string.main_import_failed))
                plan.isEmpty && plan.skipped.isEmpty() -> toast(getString(R.string.main_import_nothing))
                else -> ui.importPlan = plan
            }
        }
    }

    private fun applyImport(plan: Share.Plan) {
        ui.importPlan = null
        if (plan.isEmpty) return
        lifecycleScope.launch {
            // Speichern nicht abbrechen (z. B. Drehen waehrenddessen)
            val r = withContext(NonCancellable + Dispatchers.IO) { runCatching { Share.apply(this@MainActivity, plan) } }
                .getOrElse { toast(getString(R.string.main_err_save)); return@launch }
            reload()
            toast(resources.getQuantityString(R.plurals.main_import_done_n, r.apps, r.apps,
                resources.getQuantityString(R.plurals.main_import_tunnels_n, r.tunnels, r.tunnels)))
            if (r.needTunnel.isNotEmpty()) {
                assignAfterTunnel = r.needTunnel
                page = Page.AddTunnel(null)
            } else {
                page = Page.Home
            }
        }
    }

    /** Vorschau vor dem Import: was neu ist, was aktualisiert wird, welche Verbindungen dazukommen. */
    @Composable
    private fun ImportConfirm(p: Share.Plan) {
        AlertDialog(
            onDismissRequest = { ui.importPlan = null },
            title = { Text(stringResource(R.string.main_import_preview_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.newApps.isNotEmpty()) Text(stringResource(R.string.main_import_preview_new, p.newApps.joinToString()))
                    if (p.changedApps.isNotEmpty()) Text(stringResource(R.string.main_import_preview_changed, p.changedApps.joinToString()))
                    if (p.newTunnels.isNotEmpty()) Text(stringResource(R.string.main_import_preview_tunnels, p.newTunnels.joinToString("\n")))
                    if (p.skipped.isNotEmpty()) Text(stringResource(R.string.main_import_preview_skipped, p.skipped.joinToString()),
                        color = MaterialTheme.colorScheme.error)
                    if (p.changedApps.isNotEmpty()) Text(stringResource(R.string.main_import_preview_note),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                if (!p.isEmpty) TextButton(onClick = { applyImport(p) }) { Text(stringResource(R.string.main_import_preview_ok)) }
            },
            dismissButton = { TextButton(onClick = { ui.importPlan = null }) { Text(stringResource(R.string.main_cancel)) } },
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SharePage() {
        val ctx = LocalContext.current
        // Schluessel im QR-Code: keine Screenshots/Bildschirmaufnahmen
        DisposableEffect(Unit) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        // Keine Verbindung vorausgewaehlt (Schluessel nur auf ausdruecklichen Wunsch); QR erst nach Tipp
        var include by remember { mutableStateOf(emptySet<String>()) }
        var showQr by remember { mutableStateOf(false) }
        val chosen = tunnels.filter { it.id in include }
        val scope = rememberCoroutineScope()
        // QR im Hintergrund bauen: first = zu gross fuer einen QR-Code
        val qrState by produceState<Pair<Boolean, Bitmap?>?>(null, include, apps, tunnels, showQr) {
            value = null
            if (!showQr) return@produceState
            value = withContext(Dispatchers.Default) {
                val payload = Share.export(ctx, apps, chosen, withIcons = false)
                if (payload.length > 2300) true to null else false to Share.qr(payload, 720)
            }
        }
        // Export-Datei gleich nach dem Teilen wieder loeschen (kurz verzoegert, falls die Ziel-App noch liest)
        val sendFile = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            ui.shareFile?.let { f -> Handler(Looper.getMainLooper()).postDelayed({ f.delete() }, 30_000) }
            ui.shareFile = null
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.main_share_title)) },
                    navigationIcon = { IconButton(onClick = { page = Page.Home }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.main_back)) } },
                )
            },
        ) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.main_share_intro), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val q = qrState
                    when {
                        !showQr -> FilledTonalButton(onClick = { showQr = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.main_share_show_qr))
                        }
                        q == null -> CircularProgressIndicator()
                        q.second != null -> Image(q.second!!.asImageBitmap(), stringResource(R.string.main_qr_code),
                            Modifier.size(280.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp))
                        else -> Text(stringResource(R.string.main_share_too_big), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        val (intent, file) = withContext(Dispatchers.IO) {
                            Share.shareFile(ctx, Share.export(ctx, apps, chosen, withIcons = true))
                        }
                        ui.shareFile = file
                        if (runCatching { sendFile.launch(intent) }.isFailure) { file.delete(); ui.shareFile = null }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.main_share_file))
                }
                Spacer(Modifier.height(20.dp))

                Text(stringResource(R.string.main_share_included), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (apps.isEmpty()) stringResource(R.string.main_share_no_apps) else apps.joinToString { it.name },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                tunnels.forEach { t ->
                    SwitchRow(stringResource(R.string.main_share_include_tunnel, t.name), stringResource(if (t.isTailscale) R.string.main_share_ts_sub else R.string.main_share_wg_sub),
                        t.id in include) { on -> include = if (on) include + t.id else include - t.id }
                }
                Spacer(Modifier.height(8.dp))
                if (chosen.isNotEmpty() || apps.any { it.action?.pass?.isNotEmpty() == true }) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                        Text(stringResource(R.string.main_share_warning),
                            Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
                    Text(stringResource(R.string.main_share_tip),
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ImportPage() {
        val ctx = LocalContext.current
        val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
            val t = r.contents ?: return@rememberLauncherForActivityResult
            if (Share.isPayload(t)) importPayload(t)
            else toast(getString(R.string.main_not_ht_code))
        }
        val scope = rememberCoroutineScope()
        val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readAtMost(8_000_000).decodeToString() } }.getOrNull()
                }
                if (text != null && Share.isPayload(text)) importPayload(text) else toast(getString(R.string.main_not_ht_file))
            }
        }
        val pickQr = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val t = withContext(Dispatchers.Default) { Share.qrFromImage(ctx, uri) }
                when {
                    t == null -> toast(getString(R.string.main_no_qr))
                    Share.isPayload(t) -> importPayload(t)
                    else -> toast(getString(R.string.main_not_ht_code))
                }
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.main_import_title)) },
                    navigationIcon = { IconButton(onClick = { page = Page.Home }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.main_back)) } },
                )
            },
        ) { pad ->
            Column(Modifier.padding(pad).padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.main_import_intro),
                    style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(24.dp))
                FilledTonalButton(onClick = {
                    scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt(getString(R.string.main_scan_prompt_ht)).setBeepEnabled(false).setOrientationLocked(false))
                }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.main_scan_qr), fontSize = 16.sp)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    pickQr.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PhotoLibrary, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.main_qr_from_image))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Description, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.main_open_sent_file))
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.main_import_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    companion object {
        const val EXTRA_EDIT = "edit"

        /** Kamera-Kachel: Zeitpunkt des letzten Versuchs, ein frisches Bild zu holen (je Eintrag). */
        private val camTried = mutableMapOf<String, Long>()
        /** Kachelbild aelter als das -> beim Anzeigen der Startseite neu holen */
        private const val CAM_REFRESH_MS = 2 * 60_000L

        /** Kamera-Adresse: ohne Schema -> rtsp://, Zugangsdaten nie in der gespeicherten Adresse. */
        fun rtspUrl(input: String): String {
            val s = input.trim()
            if (s.isEmpty()) return ""
            val full = if (s.contains("://")) s else "rtsp://$s"
            return splitCred(full)?.let { (scheme, _, rest) -> scheme + rest } ?: full
        }

        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

        /**
         * "rtsp://user:pass@host/pfad" -> Schema, Zugangsdaten, Rest. Getrennt am LETZTEN @, weil
         * Passwoerter @ und / enthalten duerfen; ein @ nur im Pfad ("host/stream@1") zaehlt nicht.
         */
        fun splitCred(s: String): Triple<String, String, String>? {
            val scheme = SCHEME.find(s)?.value ?: return null
            val rest = s.substring(scheme.length)
            val at = rest.lastIndexOf('@')
            if (at <= 0 || at == rest.length - 1) return null
            val info = rest.substring(0, at)
            val slash = info.indexOf('/')
            if (slash >= 0 && ':' !in info.substring(0, slash)) return null
            return Triple(scheme, info, rest.substring(at + 1))
        }

        /** Wie Uri.decode, aber ein rohes % (nicht %XX) bleibt erhalten. */
        fun decodeCred(s: String): String = Uri.decode(s.replace(Regex("%(?![0-9a-fA-F]{2})"), "%25"))

        /** Entsperren gilt so lange fuer weitere geschuetzte Seiten. */
        private const val UNLOCK_VALID_MS = 60_000L

        fun normalizeUrl(input: String): String {
            val s = input.trim()
            if (s.isEmpty()) return ""
            return if (s.contains("://")) s else "http://$s"
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFF2B544))
        else -> lightColorScheme(primary = Color(0xFF1F242B), primaryContainer = Color(0xFFECEEF1))
    }
    // Kopfleisten = Seitenfarbe: keine zweifarbigen Balken (Karten/Menues nutzen surfaceContainer*)
    MaterialTheme(colorScheme = scheme.copy(surface = scheme.background), content = content)
}
