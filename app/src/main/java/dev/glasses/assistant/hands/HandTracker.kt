package dev.glasses.assistant.hands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hand tracking with Google's MediaPipe gesture recognizer, entirely on the phone.
 * Frames come from the phone's front camera (point it at your hand), or from the camera view when
 * that's showing the back camera. Recognized hands go through [GestureEngine]; its actions are handed
 * to [onActions] on a background thread.
 */
class HandTracker(private val context: Context, private val onActions: (List<HandAction>) -> Unit) {

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val engine = GestureEngine()
    private val worker = Executors.newSingleThreadExecutor()
    private var recognizer: GestureRecognizer? = null
    private var provider: ProcessCameraProvider? = null
    private var owner: Owner? = null
    private var analysis: ImageAnalysis? = null
    private val busy = AtomicBoolean(false)
    private var lastTs = 0L
    private var lastFeed = 0L

    @Volatile var enabled = false
        private set
    @Volatile private var ownCamera = false

    private val _status = MutableStateFlow("off")
    /** "off", "starting", "no hand", "Victory", ... for the app's status line. */
    val status: StateFlow<String> = _status.asStateFlow()

    /** Turns tracking on with the front camera. Call on the main thread. */
    fun start(onError: (String) -> Unit) {
        if (enabled) return
        enabled = true
        _status.value = "starting"
        worker.execute {
            try {
                if (recognizer == null) recognizer = createRecognizer()
                ContextCompat.getMainExecutor(context).execute { if (enabled) bindFrontCamera(onError) }
            } catch (t: Throwable) {
                Log.e(TAG, "Hand tracking failed to start", t)
                enabled = false
                _status.value = "off"
                onError(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** Call on the main thread. */
    fun stop() {
        enabled = false
        unbindCamera()
        engine.noHand()
        _status.value = "off"
    }

    /** Lets another part of the app use the camera (a photo, the camera view). Call on the main thread. */
    fun releaseCamera() = unbindCamera()

    /** Takes the front camera back after [releaseCamera]. Call on the main thread. */
    fun reclaimCamera() { if (enabled && !ownCamera) bindFrontCamera { } }

    /** Frames from elsewhere (the camera view), already upright and from the user's point of view. */
    fun feedExternal(upright: Bitmap) { if (enabled && !ownCamera) feed(upright) }

    private fun createRecognizer(): GestureRecognizer {
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result: GestureRecognizerResult, _ -> onResult(result) }
            .setErrorListener { e -> Log.w(TAG, "Recognizer error", e); busy.set(false) }
            .build()
        return GestureRecognizer.createFromOptions(context, options)
    }

    private fun bindFrontCamera(onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get().also { provider = it }
                val o = Owner().also { owner = it }
                val a = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { analysis = it }
                a.setAnalyzer(worker) { onFrontFrame(it) }
                o.registry.currentState = Lifecycle.State.RESUMED
                p.bindToLifecycle(o, CameraSelector.DEFAULT_FRONT_CAMERA, a)
                ownCamera = true
                _status.value = "no hand"
            } catch (t: Throwable) {
                Log.e(TAG, "Front camera unavailable", t)
                ownCamera = false
                onError("front camera: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun unbindCamera() {
        if (!ownCamera) return
        ownCamera = false
        analysis?.let { a -> runCatching { provider?.unbind(a) } }   // only ours: the camera view may be bound too
        analysis = null
        owner?.registry?.currentState = Lifecycle.State.DESTROYED
        owner = null
    }

    private fun onFrontFrame(image: ImageProxy) {
        try {
            if (!enabled) return
            val now = SystemClock.uptimeMillis()
            if (now - lastFeed < 1000 / FPS || busy.get()) return
            val raw = image.toBitmap()
            // Upright, then mirrored like a selfie, so your right is the picture's right.
            val m = Matrix().apply {
                postRotate(image.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f)
            }
            feed(Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true))
        } catch (t: Throwable) {
            Log.w(TAG, "Hand frame failed", t)
        } finally {
            image.close()
        }
    }

    private fun feed(bmp: Bitmap) {
        val rec = recognizer ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastFeed < 1000 / FPS || !busy.compareAndSet(false, true)) return
        lastFeed = now
        val ts = maxOf(now, lastTs + 1).also { lastTs = it }
        runCatching { rec.recognizeAsync(BitmapImageBuilder(bmp).build(), ts) }
            .onFailure { busy.set(false); Log.w(TAG, "recognizeAsync failed", it) }
    }

    private fun onResult(r: GestureRecognizerResult) {
        busy.set(false)
        if (!enabled) return
        val hands = r.landmarks()
        if (hands.isEmpty()) { engine.noHand(); _status.value = "no hand"; return }
        val lm = hands[0]
        val top = r.gestures().firstOrNull()?.firstOrNull()
        val frame = HandFrame(
            gesture = top?.categoryName() ?: "None",
            score = top?.score() ?: 0f,
            x = FloatArray(lm.size) { lm[it].x() },
            y = FloatArray(lm.size) { lm[it].y() },
            timeMs = SystemClock.uptimeMillis(),
        )
        val actions = engine.feed(frame)
        _status.value = engine.lastSeen
        if (actions.isNotEmpty()) onActions(actions)
    }

    private companion object {
        const val TAG = "HandTracker"
        const val MODEL = "gesture_recognizer.task"
        const val FPS = 12
    }
}
