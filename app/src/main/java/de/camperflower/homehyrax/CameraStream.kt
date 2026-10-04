package de.camperflower.homehyrax

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import de.camperflower.homehyrax.wgbridge.Wgbridge

/**
 * Gemeinsamer Teil fuer Kamera-Vollbild (CameraActivity) und Kamera-Kachel (frisches Bild beim Start):
 * lokale Portweiterleitung im Go-Kern (der Player kann keinen HTTP-Proxy) + ExoPlayer mit RTSP ueber TCP.
 * Den Weg (Route direkt/Tunnel) setzt der Aufrufer vorher mit Net.route.
 */
object CameraStream {

    /** Player samt Weiterleitung; release() gibt beides frei. */
    class Handle(val player: ExoPlayer, private val port: Long) {
        fun release() {
            player.release()
            Wgbridge.stopForward(port)
        }
    }

    /** Wirft bei einem Fehler der Weiterleitung. Der Player ist vorbereitet und spielt ab (Ton aus). */
    @OptIn(UnstableApi::class)
    fun open(ctx: Context, app: WebApp): Handle {
        val src = Uri.parse(app.url)
        val host = app.host.let { if (':' in it) "[$it]" else it }
        val port = Wgbridge.forward("$host:${app.port}")   // Go int -> Java long (gomobile)
        var p: ExoPlayer? = null
        try {
            // Zugangsdaten nur im RAM an den Player; der Player beantwortet Basic/Digest selbst
            val a = app.action
            val cred = if (a != null && (a.user.isNotEmpty() || a.pass.isNotEmpty())) Uri.encode(a.user) + ":" + Uri.encode(a.pass) + "@" else ""
            val path = src.encodedPath.orEmpty() + (src.encodedQuery?.let { "?$it" } ?: "")
            val player = ExoPlayer.Builder(ctx).build()
            p = player
            player.volume = 0f
            player.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri("rtsp://${cred}127.0.0.1:$port$path")))
            player.playWhenReady = true
            player.prepare()
            return Handle(player, port)
        } catch (e: Throwable) {
            // sonst bliebe der lokale Port offen
            runCatching { p?.release() }
            runCatching { Wgbridge.stopForward(port) }
            throw e
        }
    }

    /** Verstaendlicher Grund fuer einen Abspielfehler. */
    fun reason(ctx: Context, error: PlaybackException): String {
        val detail = error.cause?.message ?: error.message ?: error.errorCodeName
        return when {
            // Eufy & Co.: 404 = RTSP-Stream gerade aus (z. B. nur bei Bewegung), 401 = Zugangsdaten falsch
            Regex("\\b404\\b").containsMatchIn(detail) -> ctx.getString(R.string.cam_stream_off)
            Regex("\\b401\\b").containsMatchIn(detail) -> ctx.getString(R.string.cam_auth)
            else -> detail
        }
    }
}
