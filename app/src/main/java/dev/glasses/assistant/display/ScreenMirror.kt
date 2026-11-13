package dev.glasses.assistant.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display

/** How the phone screen is fitted into the round display. */
enum class Crop {
    Auto,   // picked from the app in front: TikTok fills, YouTube shows the video, others fit
    Fit,    // the whole screen, small
    Fill,   // a square from the middle of the screen, filling the circle (TikTok, Reels, Shorts)
    Video,  // the video strip at the top of a portrait screen (YouTube), or the middle of a landscape one
    Zoom,   // a closer look at part of the screen, panned top to bottom
}

/**
 * Mirrors the phone screen to the glasses using Android's screen capture (the user agrees once per
 * session). The capture runs at about half the phone's resolution; each picture is cropped for the
 * round display and handed to [GlassesScreen], at most [FPS] times a second, and only when something
 * on screen changed. Turning, the phone resizes the capture so landscape video fills it.
 */
class ScreenMirror(
    private val context: Context,
    private val projection: MediaProjection,
    private val screen: GlassesScreen,
    private val foregroundApp: () -> String?,
    private val onStopped: () -> Unit,
) {
    @Volatile var crop: Crop = Crop.Auto
    @Volatile var zoom: Float = 2f        // Zoom: how much closer
    @Volatile var pan: Float = 0f         // Zoom: 0 = top of the screen, 1 = bottom

    private val thread = HandlerThread("mirror").apply { start() }
    private val handler = Handler(thread.looper)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private var reader: ImageReader? = null
    private var virtual: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var lastFrame = 0L
    private var source: Bitmap? = null
    @Volatile var running = false
        private set

    private val statusBarPx: Int = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        .takeIf { it > 0 }?.let { context.resources.getDimensionPixelSize(it) } ?: 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler.post { release() }
            onStopped()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id == Display.DEFAULT_DISPLAY) handler.post { resizeIfTurned() }
        }
    }

    fun start() {
        // Android 14+ requires the callback before the capture starts.
        projection.registerCallback(projectionCallback, handler)
        val (w, h) = captureSize()
        width = w; height = h
        val r = newReader(w, h)
        virtual = projection.createVirtualDisplay(
            "glasses-mirror", w, h, context.resources.displayMetrics.densityDpi / 2,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
        displays.registerDisplayListener(displayListener, handler)
        running = true
    }

    fun stop() {
        handler.post { release() }
        runCatching { projection.stop() }
    }

    private fun release() {
        if (!running && virtual == null) return
        running = false
        runCatching { displays.unregisterDisplayListener(displayListener) }
        runCatching { projection.unregisterCallback(projectionCallback) }
        virtual?.release(); virtual = null
        reader?.close(); reader = null
        thread.quitSafely()
    }

    /** Half the real screen size, in the current orientation. */
    private fun captureSize(): Pair<Int, Int> {
        val p = Point()
        @Suppress("DEPRECATION")
        displays.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        return (p.x / 2).coerceAtLeast(2) / 2 * 2 to (p.y / 2).coerceAtLeast(2) / 2 * 2
    }

    private fun resizeIfTurned() {
        val v = virtual ?: return
        val (w, h) = captureSize()
        if (w == width && h == height) return
        width = w; height = h
        val old = reader
        val r = newReader(w, h)
        v.resize(w, h, context.resources.displayMetrics.densityDpi / 2)
        v.surface = r.surface
        old?.close()
        source = null
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also { r ->
            reader = r
            r.setOnImageAvailableListener({ onImage(it) }, handler)
        }

    private var imageW = 0
    private var imageH = 0
    private var emitQueued = false
    private val emit = Runnable {
        emitQueued = false
        val bmp = source ?: return@Runnable
        if (!running || screen.source.value != Source.Mirror) return@Runnable
        lastFrame = SystemClock.elapsedRealtime()
        screen.offer(Source.Mirror, fitted(bmp, imageW, imageH))
    }

    /** Sends the current screen again, e.g. after switching back to the mirror or changing the crop. */
    fun refresh() { handler.post(emit) }

    private fun onImage(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (screen.source.value != Source.Mirror) return
            val plane = image.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val bmp = source?.takeIf { it.width == rowPixels && it.height == image.height }
                ?: Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888).also { source = it }
            plane.buffer.rewind()
            bmp.copyPixelsFromBuffer(plane.buffer)
            imageW = image.width; imageH = image.height
            val wait = 1000 / FPS - (SystemClock.elapsedRealtime() - lastFrame)
            // Too soon: send it a little later, so the last change on screen always reaches the glasses.
            if (wait > 0) { if (!emitQueued) { emitQueued = true; handler.postDelayed(emit, wait) } }
            else emit.run()
        } catch (t: Throwable) {
            Log.w(TAG, "Mirror frame failed", t)
        } finally {
            image.close()
        }
    }

    /** Crops the captured screen ([w]x[h] of [bmp]) into a new 240x240 picture. */
    private fun fitted(bmp: Bitmap, w: Int, h: Int): Bitmap {
        val n = GlassesScreen.SIZE
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val landscape = w > h
        val sb = statusBarPx / 2   // capture is half size

        val mode = if (crop != Crop.Auto) crop else when (foregroundApp()) {
            in FILL_APPS -> Crop.Fill
            in VIDEO_APPS -> if (landscape) Crop.Fill else Crop.Video
            else -> Crop.Fit
        }
        val (src, dst) = when (mode) {
            Crop.Fill -> {
                val side = minOf(w, h)
                Rect((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2) to RectF(0f, 0f, n.toFloat(), n.toFloat())
            }
            Crop.Video -> {
                val src = if (landscape) {
                    val vh = minOf(h, w * 9 / 16)
                    Rect(0, (h - vh) / 2, w, (h + vh) / 2)
                } else {
                    Rect(0, sb, w, (sb + w * 9 / 16).coerceAtMost(h))
                }
                // Slightly wider than the circle, so the strip uses more of the display.
                val dw = n * 1.12f
                val dh = dw * src.height() / src.width()
                src to RectF((n - dw) / 2, (n - dh) / 2, (n + dw) / 2, (n + dh) / 2)
            }
            Crop.Zoom -> {
                val side = (minOf(w, h) / zoom.coerceIn(1f, 4f)).toInt().coerceAtLeast(16)
                val left = (w - side) / 2
                val top = ((h - side) * pan.coerceIn(0f, 1f)).toInt()
                Rect(left, top, left + side, top + side) to RectF(0f, 0f, n.toFloat(), n.toFloat())
            }
            else -> {   // Fit
                val scale = minOf(n.toFloat() / w, n.toFloat() / h)
                val dw = w * scale; val dh = h * scale
                Rect(0, 0, w, h) to RectF((n - dw) / 2, (n - dh) / 2, (n + dw) / 2, (n + dh) / 2)
            }
        }
        cv.drawBitmap(bmp, src, dst, paint)
        return out
    }

    companion object {
        private const val TAG = "ScreenMirror"
        const val FPS = 12
        val FILL_APPS = setOf(
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",      // TikTok
            "com.instagram.android", "com.snapchat.android",
        )
        val VIDEO_APPS = setOf(
            "com.google.android.youtube", "com.google.android.apps.youtube.music",
            "com.netflix.mediaclient", "tv.twitch.android.app",
        )
    }
}
