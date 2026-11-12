package dev.glasses.assistant.voice

import android.content.Context
import android.os.StatFs
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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The Kokoro neural voice pack (Apache 2.0), downloaded once from sherpa-onnx's
 * GitHub release and unpacked into the app's private storage.
 */
object VoicePack {
    sealed interface State {
        data object NotInstalled : State
        data class Downloading(val fraction: Float, val mb: Int) : State
        data class Unpacking(val fraction: Float) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private const val URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"
    private const val TOP = "kokoro-multi-lang-v1_0/"
    private const val NEEDED_MB = 800L          // archive + unpacked files, briefly at the same time
    private val KEEP = listOf(
        "model.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt",
        "espeak-ng-data/", "dict/",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    fun dir(context: Context) = File(context.filesDir, "kokoro")
    private fun marker(context: Context) = File(dir(context), ".complete")

    fun isReady(context: Context) = marker(context).exists()

    fun refresh(context: Context) {
        if (job?.isActive == true) return
        _state.value = if (isReady(context)) State.Ready else State.NotInstalled
    }

    fun install(context: Context) {
        if (job?.isActive == true || isReady(context)) return refresh(context)
        val app = context.applicationContext
        job = scope.launch {
            try {
                val freeMb = StatFs(app.filesDir.path).availableBytes / 1_000_000
                if (freeMb < NEEDED_MB) throw IOException("Needs about $NEEDED_MB MB free; your phone has $freeMb MB.")
                val archive = File(app.cacheDir, "kokoro.tar.bz2")
                download(archive)
                unpack(app, archive)
                archive.delete()
                _state.value = State.Ready
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun remove(context: Context) {
        job?.cancel()
        dir(context).deleteRecursively()
        File(context.cacheDir, "kokoro.tar.bz2").delete()
        _state.value = State.NotInstalled
    }

    private suspend fun download(target: File) {
        val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        http.newCall(Request.Builder().url(URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed (HTTP ${resp.code}).")
            val body = resp.body ?: throw IOException("Download failed (empty response).")
            val total = body.contentLength().takeIf { it > 0 } ?: 350_000_000L
            var done = 0L
            var lastMb = -1
            body.byteStream().use { input ->
                target.outputStream().use { out ->
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
                            _state.value = State.Downloading((done.toFloat() / total).coerceIn(0f, 1f), mb)
                        }
                    }
                }
            }
        }
    }

    private fun unpack(context: Context, archive: File) {
        val tmp = File(context.filesDir, "kokoro.tmp").apply { deleteRecursively(); mkdirs() }
        val total = archive.length().toFloat()
        val counting = CountingStream(archive.inputStream().buffered(1 shl 16))
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(counting, 1 shl 16))).use { tar ->
            var lastPct = -1
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.removePrefix("./").removePrefix(TOP)
                val pct = (counting.count * 100 / total).toInt()
                if (pct != lastPct) { lastPct = pct; _state.value = State.Unpacking(pct / 100f) }
                if (entry.isDirectory || KEEP.none { name == it || (it.endsWith("/") && name.startsWith(it)) }) continue
                val out = File(tmp, name)
                if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) continue   // no path tricks
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it, 1 shl 16) }
            }
        }
        if (!File(tmp, "model.onnx").exists() || !File(tmp, "voices.bin").exists())
            throw IOException("The voice pack was incomplete. Try again.")
        File(tmp, ".complete").writeText("kokoro-multi-lang-v1_0")
        val dest = dir(context)
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw IOException("Couldn't move the voice pack into place.")
    }

    private class CountingStream(private val inner: java.io.InputStream) : java.io.InputStream() {
        var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }
}
