package dev.glasses.assistant.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import dev.glasses.assistant.Skin
import dev.glasses.assistant.model3d.Model
import dev.glasses.assistant.model3d.Rasterizer
import dev.glasses.assistant.model3d.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * A 3D model on the round display. It turns slowly by itself until you take control (by voice or
 * hand), and can be zoomed and turned. Each picture is drawn on the phone and streamed like any other.
 */
class ModelView(private val screen: GlassesScreen, private val skin: () -> Skin) {
    @Volatile var model: Model? = null
        private set
    @Volatile var view = View()
        private set
    @Volatile var spinning = true
    @Volatile private var dirty = true
    private var job: Job? = null

    fun show(scope: CoroutineScope, m: Model) {
        model = m
        view = View()
        spinning = true
        dirty = true
        screen.setSource(Source.Model)
        start(scope)
    }

    /** Keeps drawing while the model view is on screen. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) { dirty = true; return }
        job = scope.launch(Dispatchers.Default) {
            while (isActive && screen.source.value == Source.Model) {
                if (spinning) { view = view.copy(yaw = (view.yaw + 3f) % 360f); dirty = true }
                if (dirty) {
                    dirty = false
                    model?.let { screen.offer(Source.Model, frame(it, view, 240, showName = true)) }
                }
                delay(80)
            }
        }
    }

    fun zoomBy(factor: Float) { set(view.copy(zoom = (view.zoom * factor).coerceIn(0.3f, 8f))) }
    fun setZoom(z: Float) { set(view.copy(zoom = z.coerceIn(0.3f, 8f))) }
    fun rotateBy(dYaw: Float, dPitch: Float) {
        spinning = false
        set(view.copy(yaw = (view.yaw + dYaw + 360f) % 360f, pitch = (view.pitch + dPitch).coerceIn(-89f, 89f)))
    }
    fun face(yaw: Float, pitch: Float) { spinning = false; set(view.copy(yaw = yaw, pitch = pitch)) }
    fun reset() { spinning = true; set(View()) }
    private fun set(v: View) { view = v; dirty = true }

    /** A larger, labelled picture for Claude to look at. */
    fun snapshot(size: Int = 512): ByteArray? {
        val m = model ?: return null
        val bmp = frame(m, view, size, showName = false)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun frame(m: Model, v: View, size: Int, showName: Boolean): Bitmap {
        val s = skin()
        val px = Rasterizer.render(m.mesh, v, size, size, s.background.toArgb())
        val bmp = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        if (showName) {
            val cv = Canvas(bmp)
            NowPlayingCard.text(cv, m.name.replace('_', ' '), size / 2f, size - 26f, 13f, s.text.toArgb(), bold = true, maxW = 130f)
            if (v.zoom != 1f) NowPlayingCard.text(cv, "%.1f×".format(v.zoom), size / 2f, 30f, 12f, s.accent.toArgb(), maxW = 80f)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2f; color = NowPlayingCard.withAlpha(s.accent.toArgb(), 110)
            }
            cv.drawArc(RectF(3f, 3f, size - 3f, size - 3f), 0f, 360f, false, ring)
        }
        return bmp
    }
}
