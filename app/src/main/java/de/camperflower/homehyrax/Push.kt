package de.camperflower.homehyrax

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Push-Benachrichtigungen (wahlweise, Standard aus): Firebase Cloud Messaging, weitergereicht vom Schluesseldienst.
 * Aus = kein Firebase, kein Kontakt zu Google. An = die App meldet ihr FCM-Token beim Schluesseldienst an und
 * bekommt einen Push-Schluessel; wer ihn hat (eigener Server, Hausautomation), kann diesem Handy Nachrichten
 * schicken. Die Firebase-Angaben kommen vom Schluesseldienst (health), nicht aus dem Build.
 * Protokoll: keyserver/README.md, Abschnitt "Push notifications".
 */
object Push {
    const val CH_URGENT = "urgent"
    const val CH_NORMAL = "normal"
    private const val PREFS = "push"
    /** so oft meldet sich die App beim Schluesseldienst (Lebenszeichen, ggf. neues Token) */
    private const val REFRESH_MS = 7 * 86400_000L

    class Config(val project: String, val app: String, val apiKey: String, val sender: String) {
        fun json() = JSONObject().put("project", project).put("app", app).put("key", apiKey).put("sender", sender).toString()

        companion object {
            fun from(o: JSONObject?): Config? = o?.let {
                Config(it.optString("project"), it.optString("app"), it.optString("key"), it.optString("sender"))
                    .takeIf { c -> c.project.isNotBlank() && c.app.isNotBlank() && c.apiKey.isNotBlank() && c.sender.isNotBlank() }
            }
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", false)

    /** "lost" = Schluesseldienst kennt den Schluessel nicht mehr (neu einrichten), sonst null */
    fun problem(ctx: Context): String? = prefs(ctx).getString("problem", null)

    fun notificationsAllowed(ctx: Context) =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /**
     * Beim Prozessstart: Firebase nur, wenn Push an ist (aus = kein Kontakt zu Google). Eine nach dem Ausschalten noch
     * zugestellte Nachricht verwirft show() wegen enabled == false. Danach im Hintergrund das Lebenszeichen (refresh),
     * auch wenn nur Startbildschirm-Kacheln benutzt werden; ein ohne Netz gescheitertes Abmelden wird nachgeholt.
     */
    fun init(ctx: Context) {
        if (!enabled(ctx)) {
            if (prefs(ctx).getString("delete", null) != null) Thread { retryDelete(ctx) }.start()
            return
        }
        Config.from(prefs(ctx).getString("config", null)?.let { runCatching { JSONObject(it) }.getOrNull() })
            ?.let { runCatching { firebase(ctx, it) } }
        Thread { refresh(ctx) }.start()
    }

    private fun firebase(ctx: Context, c: Config): FirebaseApp =
        FirebaseApp.getApps(ctx).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(ctx.applicationContext, FirebaseOptions.Builder().setProjectId(c.project)
                .setApplicationId(c.app).setApiKey(c.apiKey).setGcmSenderId(c.sender).build())

    /** Bietet der Schluesseldienst Push an? Netzwerk -> Hintergrund. Fehler wie Guest.Failure */
    fun offered(ctx: Context): Boolean = Config.from(Guest.healthInfo(ctx).optJSONObject("push")) != null

    /**
     * Gespeicherte Anmeldung: Push-Schluessel (fuer Absender) und Geraete-Geheimnis (nur diese App: Token aendern,
     * abmelden) - wer den Push-Schluessel hat, kann so nur senden, nicht umlenken oder abschalten.
     */
    private class Creds(val key: String, val device: String)

    private fun creds(ctx: Context): Creds? = ctx.store.pushKey()?.split('\n')?.takeIf { it.size == 2 }?.let { Creds(it[0], it[1]) }

    /** Push-Schluessel dieses Handys ("<id>.<geheimnis>"), null = nicht angemeldet */
    fun key(ctx: Context): String? = creds(ctx)?.key

    /** Einschalten: Token holen, anmelden. Liefert den Push-Schluessel. Netzwerk -> Hintergrund */
    @Synchronized
    fun enable(ctx: Context): String {
        val c = Config.from(Guest.healthInfo(ctx).optJSONObject("push")) ?: throw Guest.Failure("push_off")
        firebase(ctx, c)
        channels(ctx)   // gleich sichtbar in den Android-Einstellungen, nicht erst mit der ersten Nachricht
        val key = register(ctx, fcmToken(), creds(ctx), newIfUnknown = true)
        // erst nach erfolgreicher Anmeldung: config steuert init() beim naechsten Start
        prefs(ctx).edit().putString("config", c.json()).putBoolean("on", true).apply()
        // Firebase prueft ab jetzt selbst beim Start, ob das Token erneuert werden muss (onNewToken)
        runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = true }
        return key
    }

    /** Ausschalten: beim Schluesseldienst abmelden, Token und Firebase-Kennung bei Google loeschen */
    @Synchronized
    fun disable(ctx: Context) {
        prefs(ctx).edit().putBoolean("on", false).remove("problem").remove("at").apply()
        // ohne Netz: Abmelden beim naechsten App-Start nachholen (das Geraete-Geheimnis bleibt dafuer gemerkt)
        creds(ctx)?.let { prefs(ctx).edit().putString("delete", it.device).apply() }
        ctx.store.savePushKey(null)
        retryDelete(ctx)
        if (FirebaseApp.getApps(ctx).isNotEmpty()) {
            runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = false }
            runCatching { Tasks.await(FirebaseMessaging.getInstance().deleteToken(), 10, TimeUnit.SECONDS) }
            runCatching { Tasks.await(FirebaseInstallations.getInstance().delete(), 10, TimeUnit.SECONDS) }
        }
    }

    private fun retryDelete(ctx: Context) {
        val d = prefs(ctx).getString("delete", null) ?: return
        val done = try {
            Guest.request(ctx, "push-delete", JSONObject().put("device", d)); true
        } catch (e: Guest.Failure) {
            e.code == "unknown"   // schon weg
        }
        if (done) prefs(ctx).edit().remove("delete").apply()
    }

    /** Neuer Push-Schluessel: erst den neuen anmelden, dann den alten loeschen (scheitert etwas, bleibt der alte) */
    @Synchronized
    fun renew(ctx: Context): String {
        val old = creds(ctx)
        val key = register(ctx, fcmToken(), null, newIfUnknown = true)
        old?.let { o -> runCatching { Guest.request(ctx, "push-delete", JSONObject().put("device", o.device)) } }
        return key
    }

    /** Testnachricht an sich selbst in beiden Kanaelen, ueber denselben Weg wie jeder Absender */
    fun test(ctx: Context, title: String, body: String) {
        val k = key(ctx) ?: throw Guest.Failure("unknown")
        for (ch in listOf(CH_URGENT, CH_NORMAL)) {
            val name = ctx.getString(if (ch == CH_URGENT) R.string.push_channel_urgent else R.string.push_channel_normal)
            Guest.request(ctx, "push", JSONObject().put("key", k).put("ch", ch).put("title", "$title · $name").put("body", body))
        }
    }

    /** Beim App-Start: hoechstens woechentlich Lebenszeichen + aktuelles Token. Netzwerk -> Hintergrund */
    @Synchronized
    fun refresh(ctx: Context) {
        if (!enabled(ctx)) return
        val c = creds(ctx)
        if (c == null) { prefs(ctx).edit().putString("problem", "lost").apply(); return }   // nie still einen neuen
        // Benachrichtigungen inzwischen blockiert/wieder erlaubt: sofort melden, damit der Absender es erfaehrt
        val changed = prefs(ctx).getBoolean("active", true) != notificationsAllowed(ctx)
        if (!changed && System.currentTimeMillis() - prefs(ctx).getLong("at", 0) < REFRESH_MS) return
        runCatching { register(ctx, fcmToken(), c, newIfUnknown = false) }
    }

    /** Google hat ein neues Token vergeben (PushService) */
    @Synchronized
    fun tokenChanged(ctx: Context, token: String) {
        if (!enabled(ctx)) return
        val c = creds(ctx) ?: return
        runCatching { register(ctx, token, c, newIfUnknown = false) }
    }

    private fun fcmToken(): String = try {
        Tasks.await(FirebaseMessaging.getInstance().token, 30, TimeUnit.SECONDS)
    } catch (e: Exception) {
        throw Guest.Failure("fcm")   // keine Google-Play-Dienste oder Google nicht erreichbar
    }

    /**
     * Anmelden bzw. Token aktualisieren. Kennt der Schluesseldienst die Anmeldung nicht mehr, gibt es nur beim
     * Einschalten eine neue - nebenbei nicht, sonst liefe der beim Absender hinterlegte Schluessel still ins Leere.
     * Aufrufer halten die Sperre (@Synchronized): Ausschalten und Token-Wechsel laufen nie gleichzeitig.
     */
    private fun register(ctx: Context, token: String, old: Creds?, newIfUnknown: Boolean): String {
        // active = Benachrichtigungen erlaubt; sonst antwortet der Dienst dem Absender "muted" statt still "ok"
        val active = notificationsAllowed(ctx)
        val body = JSONObject().put("token", token).put("active", active)
        if (old != null) body.put("device", old.device)
        val r = try {
            Guest.request(ctx, "push-register", body)
        } catch (e: Guest.Failure) {
            if (old == null || e.code != "unknown") throw e
            if (!newIfUnknown) { prefs(ctx).edit().putString("problem", "lost").apply(); throw e }
            Guest.request(ctx, "push-register", JSONObject().put("token", token).put("active", active))
        }
        val c = if (r.has("device")) Creds(r.getString("key"), r.getString("device")) else old!!
        ctx.store.savePushKey(c.key + "\n" + c.device)
        prefs(ctx).edit().putLong("at", System.currentTimeMillis()).putBoolean("active", active).remove("problem").apply()
        return c.key
    }

    // ================================================================ Anzeige

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_URGENT, ctx.getString(R.string.push_channel_urgent),
            NotificationManager.IMPORTANCE_HIGH).apply { description = ctx.getString(R.string.push_channel_urgent_desc) })
        nm.createNotificationChannel(NotificationChannel(CH_NORMAL, ctx.getString(R.string.push_channel_normal),
            NotificationManager.IMPORTANCE_DEFAULT).apply { description = ctx.getString(R.string.push_channel_normal_desc) })
    }

    fun show(ctx: Context, msg: Map<String, String>) {
        if (!enabled(ctx) || !notificationsAllowed(ctx)) return
        val title = msg["title"]?.take(120)?.takeIf { it.isNotBlank() } ?: return
        val body = msg["body"].orEmpty().take(2000)
        val urgent = msg["ch"] == CH_URGENT
        val url = msg["url"]?.takeIf { it.isNotBlank() }
        channels(ctx)
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        // Antippen: passender eigener Eintrag direkt (WebAppActivity ist nicht exportiert - keine fremde App kann so
        // eine Adresse einschleusen), sonst die Startseite
        val entry = url?.let { u -> runCatching { entryFor(ctx.store.apps(), u) }.getOrNull() }
        val open = (if (entry != null) Shortcuts.launchIntent(ctx, entry).putExtra(WebAppActivity.EXTRA_START_URL, url)
            else Intent(ctx, MainActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val n = NotificationCompat.Builder(ctx, if (urgent) CH_URGENT else CH_NORMAL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            // bewusst nicht CATEGORY_ALARM: das liefe durch "Nicht stoeren" - das entscheidet der Nutzer am Kanal
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setGroup(if (urgent) CH_URGENT else CH_NORMAL)   // viele Mitteilungen stapelt Android zu einer Gruppe
            .setContentIntent(PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: SecurityException) {
            // Berechtigung gerade entzogen - Nachricht verfaellt
        }
    }

    /**
     * Nur schlichte http(s)-Adressen: kein Backslash, kein @, keine Steuer- oder Leerzeichen - Android-Uri und Chromium lesen
     * solche Adressen sonst verschieden (anderer Host als geprueft)
     */
    fun safeUrl(url: String) = (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) &&
        url.none { it == '\\' || it == '@' || it.isWhitespace() || it.isISOControl() }

    /**
     * Eintrag zur Adresse aus einer Benachrichtigung: nur eigene Webseiten mit gleichem Ursprung (Schema, Host,
     * Port). Schalter nie - eine Nachricht darf nichts ausloesen; fremde Adressen werden nicht geoeffnet.
     */
    fun entryFor(apps: List<WebApp>, url: String): WebApp? {
        if (!safeUrl(url)) return null
        return apps.firstOrNull { !it.isGuest && !it.isAction && !it.isCamera && MainActivity.sameOrigin(it.url, url) }
    }
}

/** Empfang von Firebase: nur Datennachrichten (Benachrichtigung baut Push.show) */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) = Push.show(this, message.data)

    override fun onNewToken(token: String) = Push.tokenChanged(this, token)
}
