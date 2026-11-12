package dev.glasses.assistant.voice

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * The Kokoro neural voice pack (Apache 2.0), repacked as a zip by this repo's
 * "Publish voice pack" workflow. Downloaded once (resumable) and unpacked with
 * Android's native zip code into the app's private storage.
 */
object VoicePack {
    sealed interface State {
        data object NotInstalled : State
        data class Downloading(val fraction: Float, val mb: Int, val totalMb: Int) : State
        data class Unpacking(val fraction: Float) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private const val URL =
        "https://github.com/Dustin-pixel/glasses-assistant/releases/download/voicepack-1/kokoro-voicepack-1.zip"
    private const val MODEL_BYTES = 325_560_556L   // model.onnx, to check the unpacked file
    private const val NEEDED_MB = 800L             // zip + unpacked files, briefly at the same time

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    fun dir(context: Context) = File(context.filesDir, "kokoro")
    private fun marker(context: Context) = File(dir(context), ".complete")
    private fun archive(context: Context) = File(context.filesDir, "kokoro-voicepack-1.zip.part")

    fun isReady(context: Context) = marker(context).exists()

    fun refresh(context: Context) {
        if (job?.isActive == true) return
        _state.value = if (isReady(context)) State.Ready else State.NotInstalled
    }

    fun install(context: Context) {
        if (job?.isActive == true) return
        if (isReady(context)) return refresh(context)
        val app = context.applicationContext
        job = scope.launch {
            try {
                cleanUpOldAttempts(app)
                val zip = archive(app)
                val freeMb = StatFs(app.filesDir.path).availableBytes / 1_000_000 + zip.length() / 1_000_000
                if (freeMb < NEEDED_MB) throw IOException("Needs about $NEEDED_MB MB free; your phone has $freeMb MB.")
                download(zip)
                unpack(app, zip)
                zip.delete()
                _state.value = State.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = State.Failed(
                    if (t is OutOfMemoryError) "The phone ran out of memory." else t.message ?: t.javaClass.simpleName
                )
            }
        }
    }

    fun remove(context: Context) {
        job?.cancel()
        dir(context).deleteRecursively()
        archive(context).delete()
        cleanUpOldAttempts(context)
        _state.value = State.NotInstalled
    }

    /** Leftovers from the first version, which used a slow tar.bz2. */
    private fun cleanUpOldAttempts(context: Context) {
        File(context.cacheDir, "kokoro.tar.bz2").delete()
        File(context.filesDir, "kokoro.tmp").deleteRecursively()
    }

    /** Downloads to [target], resuming a partial file if there is one. */
    private suspend fun download(target: File) {
        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val have = target.length()
        val request = Request.Builder().url(URL).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 416) return                                   // already complete
            if (!resp.isSuccessful) throw IOException("Download failed (HTTP ${resp.code}).")
            val body = resp.body ?: throw IOException("Download failed (empty response).")
            val resumed = resp.code == 206
            var done = if (resumed) have else 0L
            val total = (if (resumed) have else 0L) + body.contentLength().coerceAtLeast(0)
            val totalMb = (total / 1_000_000).toInt().coerceAtLeast(1)
            var lastMb = -1
            body.byteStream().use { input ->
                FileOutputStream(target, resumed).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val mb = (done / 1_000_000).toInt()
                        if (mb != lastMb) {
                            lastMb = mb
                            _state.value = State.Downloading((done.toFloat() / total).coerceIn(0f, 1f), mb, totalMb)
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("The download was cut off. Tap Try again to resume.")
        }
    }

    private suspend fun unpack(context: Context, zip: File) {
        val tmp = File(context.filesDir, "kokoro.unpack").apply { deleteRecursively(); mkdirs() }
        val total = zip.length().toFloat()
        val counting = CountingStream(zip.inputStream().buffered(1 shl 16))
        _state.value = State.Unpacking(0f)
        ZipInputStream(counting).use { z ->
            val buf = ByteArray(1 shl 16)
            var lastPct = -1
            while (true) {
                val entry = z.nextEntry ?: break
                val out = File(tmp, entry.name)
                if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) continue   // no path tricks
                if (entry.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        val pct = (counting.count * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            _state.value = State.Unpacking(pct / 100f)
                            currentCoroutineContext().ensureActive()
                        }
                    }
                }
            }
        }
        val model = File(tmp, "model.onnx")
        if (model.length() != MODEL_BYTES || !File(tmp, "voices.bin").exists()) {
            tmp.deleteRecursively()
            zip.delete()
            throw IOException("The voice pack was damaged in download. Tap Try again.")
        }
        File(tmp, ".complete").writeText("kokoro-voicepack-1")
        val dest = dir(context)
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw IOException("Couldn't move the voice pack into place.")
    }

    private class CountingStream(private val inner: java.io.InputStream) : java.io.InputStream() {
        @Volatile var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }
}
