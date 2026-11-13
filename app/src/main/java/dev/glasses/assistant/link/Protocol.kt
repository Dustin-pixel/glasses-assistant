package dev.glasses.assistant.link

import org.json.JSONObject

/**
 * Messages between phone and glasses over one WebSocket.
 * Text frames carry JSON. Binary frames from the glasses are a camera JPEG answering the latest "capture";
 * binary frames from the phone are a 240x240 JPEG to fill the display (screen streaming).
 *
 * glasses -> phone:  hello {token, device, fw} | button {press: short|long|double} | status {battery, rssi, ...}
 *                    | capture_failed {id, reason} | frame_ack (after drawing a streamed frame)
 * phone -> glasses:  capture {id} | overlay {text, pos, ttl_ms} | clear | skin {name, accent, bg, text}
 *                    | stream_end (stop showing streamed frames; back to the glasses' own screen)
 */
object Protocol {
    const val HELLO = "hello"
    const val BUTTON = "button"
    const val STATUS = "status"
    const val CAPTURE_FAILED = "capture_failed"
    const val FRAME_ACK = "frame_ack"
    const val STREAM_END = "stream_end"

    const val CAPTURE = "capture"
    const val OVERLAY = "overlay"
    const val CLEAR = "clear"
    const val SKIN = "skin"

    fun capture(id: Int): String =
        JSONObject().put("type", CAPTURE).put("id", id).toString()

    fun overlay(text: String, ttlMs: Long = 6000, pos: String = "bottom"): String =
        JSONObject().put("type", OVERLAY).put("text", text).put("pos", pos).put("ttl_ms", ttlMs).toString()

    fun clear(): String = JSONObject().put("type", CLEAR).toString()

    /** The skin's name plus its colors as RGB565, so the glasses can draw it without a table of their own. */
    fun skin(name: String): String {
        val s = dev.glasses.assistant.Skins.byId(name)
        return JSONObject().put("type", SKIN).put("name", name)
            .put("accent", rgb565(s.accent)).put("bg", rgb565(s.background)).put("text", rgb565(s.text))
            .toString()
    }

    fun streamEnd(): String = JSONObject().put("type", STREAM_END).toString()

    private fun rgb565(c: androidx.compose.ui.graphics.Color): Int {
        val r = (c.red * 255).toInt(); val g = (c.green * 255).toInt(); val b = (c.blue * 255).toInt()
        return ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
    }
}
