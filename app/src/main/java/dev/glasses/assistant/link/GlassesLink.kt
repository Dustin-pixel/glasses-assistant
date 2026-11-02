package dev.glasses.assistant.link

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer

sealed interface LinkEvent {
    data class Connected(val device: String) : LinkEvent
    data object Disconnected : LinkEvent
    data object ButtonPressed : LinkEvent
    data class Status(val json: JSONObject) : LinkEvent
}

/**
 * WebSocket server on the phone. The glasses join the phone's hotspot and connect to
 * ws://<phone>:<port>, then authenticate with a hello carrying the shared token.
 * One pair of glasses at a time; a new authenticated client replaces the old one.
 */
class GlassesLink(
    port: Int,
    private val token: String,
    private val onEvent: (LinkEvent) -> Unit,
) {
    @Volatile private var glasses: WebSocket? = null
    @Volatile private var pendingFrame: CompletableDeferred<ByteArray>? = null
    private val captureMutex = Mutex()
    private var nextId = 0

    private val server = object : WebSocketServer(InetSocketAddress(port)) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            // Nothing until the client says hello.
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            if (conn == glasses) {
                glasses = null
                pendingFrame?.completeExceptionally(IOException("Glasses disconnected"))
                onEvent(LinkEvent.Disconnected)
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val msg = runCatching { JSONObject(message) }.getOrNull() ?: return
            val type = msg.optString("type")

            if (conn != glasses) {
                if (type == Protocol.HELLO && msg.optString("token") == token) {
                    glasses?.close(1000, "replaced")
                    glasses = conn
                    onEvent(LinkEvent.Connected(msg.optString("device", "glasses")))
                } else {
                    conn.close(4001, "unauthorized")
                }
                return
            }

            when (type) {
                Protocol.BUTTON -> onEvent(LinkEvent.ButtonPressed)
                Protocol.STATUS -> onEvent(LinkEvent.Status(msg))
                Protocol.CAPTURE_FAILED -> pendingFrame?.completeExceptionally(
                    IOException("Camera error: ${msg.optString("reason", "unknown")}")
                )
            }
        }

        override fun onMessage(conn: WebSocket, message: ByteBuffer) {
            if (conn != glasses) return
            val bytes = ByteArray(message.remaining())
            message.get(bytes)
            pendingFrame?.complete(bytes)
        }

        override fun onError(conn: WebSocket?, ex: Exception?) {
            Log.w(TAG, "Link error", ex)
        }

        override fun onStart() {
            Log.i(TAG, "Glasses link listening on port $port")
        }
    }.apply { isReuseAddr = true }

    val isConnected: Boolean get() = glasses?.isOpen == true

    fun start() = server.start()

    /** Blocking; call off the main thread. */
    fun stop() {
        runCatching { server.stop(1000) }
    }

    fun send(json: String) {
        glasses?.takeIf { it.isOpen }?.send(json)
    }

    /** Asks the glasses for one JPEG frame and waits for it. */
    suspend fun captureFrame(timeoutMs: Long = 4000): ByteArray = captureMutex.withLock {
        val conn = glasses?.takeIf { it.isOpen } ?: throw IOException("Glasses not connected")
        val deferred = CompletableDeferred<ByteArray>()
        pendingFrame = deferred
        try {
            conn.send(Protocol.capture(++nextId))
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingFrame = null
        }
    }

    private companion object {
        const val TAG = "GlassesLink"
    }
}
