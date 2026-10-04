package de.camperflower.homehyrax

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HotTub
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SolarPower
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Yard
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath

/** Fertige Symbole fuer Web-Apps (weiss auf der App-Farbe). */
object Symbols {
    data class Symbol(val key: String, @androidx.annotation.StringRes val labelRes: Int, val icon: ImageVector)

    val all = listOf(
        Symbol("home", R.string.sym_home, Icons.Filled.Home),
        Symbol("solar", R.string.sym_solar, Icons.Filled.SolarPower),
        Symbol("battery", R.string.sym_battery, Icons.Filled.BatteryChargingFull),
        Symbol("bolt", R.string.sym_bolt, Icons.Filled.Bolt),
        Symbol("light", R.string.sym_light, Icons.Filled.Lightbulb),
        Symbol("heat", R.string.sym_heat, Icons.Filled.Thermostat),
        Symbol("sauna", R.string.sym_sauna, Icons.Filled.HotTub),
        Symbol("pool", R.string.sym_pool, Icons.Filled.Pool),
        Symbol("water", R.string.sym_water, Icons.Filled.WaterDrop),
        Symbol("garden", R.string.sym_garden, Icons.Filled.Yard),
        Symbol("garage", R.string.sym_garage, Icons.Filled.Garage),
        Symbol("door", R.string.sym_door, Icons.Filled.DoorFront),
        Symbol("camera", R.string.sym_camera, Icons.Filled.Videocam),
        Symbol("alarm", R.string.sym_alarm, Icons.Filled.Security),
        Symbol("ev", R.string.sym_ev, Icons.Filled.EvStation),
        Symbol("nas", R.string.sym_nas, Icons.Filled.Storage),
        Symbol("server", R.string.sym_server, Icons.Filled.Dns),
        Symbol("router", R.string.sym_router, Icons.Filled.Router),
        Symbol("download", R.string.sym_download, Icons.Filled.Download),
        Symbol("print", R.string.sym_print, Icons.Filled.Print),
        Symbol("tv", R.string.sym_tv, Icons.Filled.Tv),
        Symbol("music", R.string.sym_music, Icons.Filled.Speaker),
        Symbol("pets", R.string.sym_pets, Icons.Filled.Pets),
    )

    fun find(key: String?) = all.firstOrNull { it.key == key }

    /**
     * Zeichnet ein Material-Symbol (Vektor) direkt auf einen Android-Canvas -
     * ohne Compose-Komposition, damit es auch fuer Startbildschirm-Symbole geht.
     * [size] ist die Kantenlaenge, zentriert in einem Feld der Breite [box].
     */
    fun draw(c: Canvas, icon: ImageVector, box: Float, size: Float, color: Int = android.graphics.Color.WHITE) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val m = Matrix().apply {
            val s = size / icon.viewportWidth
            setScale(s, s)
            postTranslate((box - size) / 2f, (box - size) / 2f)
        }
        drawNode(c, icon.root, m, p)
    }

    private fun drawNode(c: Canvas, node: VectorNode, m: Matrix, p: Paint) {
        when (node) {
            is VectorGroup -> node.forEach { drawNode(c, it, m, p) }
            is VectorPath -> {
                val path = node.pathData.toPath().asAndroidPath()
                path.transform(m)
                c.drawPath(path, p)
            }
        }
    }

    /** Symbol als Bitmap (fuer Vorschau/Liste). */
    fun bitmap(icon: ImageVector, px: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), icon, px.toFloat(), px.toFloat())
        return b
    }
}
