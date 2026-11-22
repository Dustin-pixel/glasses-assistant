package dev.glasses.assistant.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Live view from the phone's back camera on the glasses display: a square from the middle of the
 * picture, filling the round screen, at up to [FPS] frames a second. This stands in for the glasses'
 * own camera while it's out. The latest full picture is kept, so "what am I looking at?" can use it
 * without opening the camera a second time.
 */
class CameraView(private val context: Context, private val screen: GlassesScreen) {

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var owner: Owner? = null
    private var provider: ProcessCameraProvider? = null
    private var lastFrameAt = 0L
    @Volatile private var latest: Bitmap? = null
    @Volatile var running = false
        private set

    /** Must be called on the main thread. */
    fun start(onError: (String) -> Unit) {
        if (running) return
        running = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val o = Owner().also { owner = it }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(worker) { onFrame(it) }
                o.registry.currentState = Lifecycle.State.RESUMED
                p.bindToLifecycle(o, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (t: Throwable) {
                Log.e(TAG, "Camera view failed to start", t)
                stop()
                onError(t.message ?: t.javaClass.simpleName)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Must be called on the main thread. */
    fun stop() {
        if (!running) return
        running = false
        runCatching { provider?.unbindAll() }
        owner?.registry?.currentState = Lifecycle.State.DESTROYED
        owner = null
        latest = null
    }

    /** The newest camera picture as a JPEG, upright, or null when the view isn't running. */
    fun snapshot(): ByteArray? {
        val bmp = latest ?: return null
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }

    private fun onFrame(image: ImageProxy) {
        try {
            if (!running || screen.source.value != Source.Camera) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < 1000 / FPS) return
            lastFrameAt = now
            val raw = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            val upright = if (rot == 0) raw else
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            latest = upright
            screen.offer(Source.Camera, square(upright))
        } catch (t: Throwable) {
            Log.w(TAG, "Camera frame failed", t)
        } finally {
            image.close()
        }
    }

    /** The middle square of the picture, scaled to the round display. */
    private fun square(src: Bitmap): Bitmap {
        val n = GlassesScreen.SIZE
        val side = minOf(src.width, src.height)
        val from = Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2)
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(src, from, RectF(0f, 0f, n.toFloat(), n.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    private companion object {
        const val TAG = "CameraView"
        const val FPS = 12
    }
}
