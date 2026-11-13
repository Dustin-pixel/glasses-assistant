package dev.glasses.assistant.display

import android.graphics.Bitmap
import dev.glasses.assistant.link.GlassesLink
import dev.glasses.assistant.link.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/** What the glasses display is filled with. */
enum class Source { None, NowPlaying, Mirror, Text }

/**
 * Streams pictures to the glasses display: whatever the current [Source] draws becomes a 240x240 JPEG.
 * Frames are paced by the glasses: the next one goes out after the glasses say they drew the last one,
 * so a slow link drops frames instead of building a backlog. The latest frame is also kept as an
 * in-app preview, which works without glasses connected.
 */
class GlassesScreen(private val link: GlassesLink, private val scope: CoroutineScope) {

    private val frames = Channel<ByteArray>(Channel.CONFLATED)
    private val acks = Channel<Unit>(Channel.CONFLATED)
    private var sender: Job? = null

    private val _source = MutableStateFlow(Source.None)
    val source: StateFlow<Source> = _source.asStateFlow()

    fun setSource(s: Source) {
        if (_source.value == s) return
        _source.value = s
        if (s == Source.None) {
            _preview.value = null
            link.send(Protocol.streamEnd())
        }
    }

    /** Called by a source with a fresh 240x240 picture. Ignored unless that source is showing. */
    fun offer(from: Source, frame: Bitmap) {
        if (from != _source.value) return
        _preview.value = frame
        val out = ByteArrayOutputStream(16 * 1024)
        frame.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        frames.trySend(out.toByteArray())
        ensureSender()
    }

    fun onAck() { acks.trySend(Unit) }

    private fun ensureSender() {
        if (sender?.isActive == true) return
        sender = scope.launch(Dispatchers.IO) {
            while (true) {
                val jpeg = frames.receive()
                if (_source.value == Source.None) continue
                if (!link.sendFrame(jpeg)) continue
                // Wait for the glasses to finish drawing; give up after a moment so a lost ack can't stall us.
                withTimeoutOrNull(ACK_TIMEOUT_MS) { acks.receive() }
            }
        }
    }

    companion object {
        const val SIZE = 240
        private const val QUALITY = 60
        private const val ACK_TIMEOUT_MS = 500L

        private val _preview = MutableStateFlow<Bitmap?>(null)
        /** The last picture sent to the glasses, for the app's preview. */
        val preview: StateFlow<Bitmap?> = _preview.asStateFlow()
    }
}
