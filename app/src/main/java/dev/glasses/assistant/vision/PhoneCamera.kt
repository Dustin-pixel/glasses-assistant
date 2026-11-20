package dev.glasses.assistant.vision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Takes a photo with the phone's back camera, for when the glasses camera isn't available.
 * The camera is opened only for the moment of the photo, then closed again.
 * Photos are turned upright and scaled to at most [MAX_SIDE] pixels, which is plenty for Claude
 * to read signs and labels while keeping each request small.
 */
class PhoneCamera(private val context: Context) {

    /** A lifecycle that's "on" only while a photo is being taken, so CameraX releases the camera after. */
    private class Shot : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun open() { registry.currentState = Lifecycle.State.RESUMED }
        fun close() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    private val worker = Executors.newSingleThreadExecutor()

    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    suspend fun capture(): ByteArray {
        if (!hasPermission()) throw IOException("the app doesn't have camera permission; allow it in the app")
        val provider = withContext(Dispatchers.Main) { providerFuture() }
        val shot = Shot()
        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        try {
            withContext(Dispatchers.Main) {
                shot.open()
                provider.bindToLifecycle(shot, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture)
            }
            val image = suspendCancellableCoroutine<ImageProxy> { cont ->
                imageCapture.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) { cont.resume(image) }
                    override fun onError(exception: ImageCaptureException) { cont.resumeWithException(exception) }
                })
            }
            return withContext(Dispatchers.Default) { image.use { upright(it) } }
        } finally {
            withContext(Dispatchers.Main) {
                runCatching { provider.unbind(imageCapture) }
                shot.close()
            }
        }
    }

    private suspend fun providerFuture(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching { future.get() }.fold(cont::resume, cont::resumeWithException)
        }, ContextCompat.getMainExecutor(context))
    }

    /** JPEG bytes, rotated the way the phone was held and scaled down. */
    private fun upright(image: ImageProxy): ByteArray {
        val buffer = image.planes[0].buffer
        val raw = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(image.imageInfo.rotationDegrees.toFloat())
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private companion object {
        const val MAX_SIDE = 1280
    }
}
