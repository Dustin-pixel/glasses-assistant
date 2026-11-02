package dev.glasses.assistant.link

import org.json.JSONObject

/**
 * Messages between phone and glasses over one WebSocket.
 * Text frames carry JSON; binary frames carry a JPEG answering the latest "capture".
 *
 * glasses -> phone:  hello {token, device} | button | status {battery, temp_c, ...} | capture_failed {id, reason}
 * phone -> glasses:  capture {id} | overlay {text, pos, ttl_ms} | clear | skin {name}
 */
object Protocol {
    const val HELLO = "hello"
    const val BUTTON = "button"
    const val STATUS = "status"
    const val CAPTURE_FAILED = "capture_failed"

    const val CAPTURE = "capture"
    const val OVERLAY = "overlay"
    const val CLEAR = "clear"
    const val SKIN = "skin"

    fun capture(id: Int): String =
        JSONObject().put("type", CAPTURE).put("id", id).toString()

    fun overlay(text: String, ttlMs: Long = 6000, pos: String = "bottom"): String =
        JSONObject().put("type", OVERLAY).put("text", text).put("pos", pos).put("ttl_ms", ttlMs).toString()

    fun clear(): String = JSONObject().put("type", CLEAR).toString()

    fun skin(name: String): String = JSONObject().put("type", SKIN).put("name", name).toString()
}
