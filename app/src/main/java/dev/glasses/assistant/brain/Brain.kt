package dev.glasses.assistant.brain

import android.util.Base64
import dev.glasses.assistant.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The assistant's reasoning: Claude Messages API with tool use and a rolling conversation.
 * Swap in a local model later by keeping ask(text): String.
 */
class Brain(private val config: Config, private val tools: ToolRegistry) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val history = mutableListOf<JSONObject>()
    private val mutex = Mutex()

    suspend fun ask(userText: String): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(config.apiKey.isNotBlank()) { "Add your API key in the app first." }

            val rollback = history.size
            stripOldImages()
            history.add(JSONObject().put("role", "user").put("content", userText))
            trimHistory()

            try {
                repeat(MAX_TOOL_ROUNDS) {
                    val response = call()
                    val content = response.getJSONArray("content")
                    history.add(JSONObject().put("role", "assistant").put("content", content))

                    if (response.optString("stop_reason") != "tool_use") {
                        return@withContext textOf(content).ifBlank { "Done." }
                    }

                    val results = JSONArray()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.optString("type") != "tool_use") continue
                        val result = tools.run(block.getString("name"), block.optJSONObject("input") ?: JSONObject())
                        results.put(toolResultBlock(block.getString("id"), result))
                    }
                    history.add(JSONObject().put("role", "user").put("content", results))
                }
                "Sorry, that took too many steps."
            } catch (e: Exception) {
                // Keep the history valid (no dangling tool_use) if a call failed mid-turn.
                while (history.size > rollback) history.removeAt(history.lastIndex)
                throw e
            }
        }
    }

    fun reset() {
        history.clear()
    }

    private fun call(): JSONObject {
        val body = JSONObject()
            .put("model", config.model)
            .put("max_tokens", 1024)
            .put("system", SYSTEM_PROMPT)
            .put("tools", tools.definitions())
            .put("messages", JSONArray(history))

        val request = Request.Builder()
            .url(API_URL)
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", API_VERSION)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("API ${resp.code}: ${text.take(300)}")
            return JSONObject(text)
        }
    }

    private fun toolResultBlock(id: String, result: ToolResult): JSONObject {
        val block = JSONObject().put("type", "tool_result").put("tool_use_id", id)
        return when (result) {
            is ToolResult.Text -> block.put("content", result.text)
            is ToolResult.Image -> block.put(
                "content", JSONArray()
                    .put(JSONObject()
                        .put("type", "image")
                        .put("source", JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", Base64.encodeToString(result.jpeg, Base64.NO_WRAP))))
                    .put(JSONObject().put("type", "text").put("text", result.note))
            )
        }
    }

    private fun textOf(content: JSONArray): String = buildString {
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") append(block.getString("text"))
        }
    }.trim()

    /** Images from earlier turns are expensive to resend; replace them with a note. */
    private fun stripOldImages() {
        for (msg in history) {
            val content = msg.optJSONArray("content") ?: continue
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                val inner = block.optJSONArray("content") ?: continue
                for (j in 0 until inner.length()) {
                    if (inner.getJSONObject(j).optString("type") == "image") {
                        inner.put(j, JSONObject().put("type", "text").put("text", "[earlier photo removed]"))
                    }
                }
            }
        }
    }

    /** Keeps the conversation short, always starting at a plain user message. */
    private fun trimHistory() {
        while (history.size > MAX_MESSAGES) history.removeAt(0)
        while (history.isNotEmpty() && !history[0].let { it.optString("role") == "user" && it.opt("content") is String }) {
            history.removeAt(0)
        }
    }

    private companion object {
        const val API_URL = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2023-06-01"
        const val MAX_TOOL_ROUNDS = 6
        const val MAX_MESSAGES = 30

        val SYSTEM_PROMPT = """
            You are a personal assistant running on the user's DIY smart glasses, with their Android phone as your processor.
            Your replies are spoken aloud through earbuds, so keep them short and natural: usually one to three sentences,
            no markdown, no lists, no emoji. Get to the point.
            You can see through the glasses camera with the look tool; use it whenever the user refers to something in view.
            Use show_on_display for anything easier to read than hear, such as numbers, directions or codes.
            If a tool fails, say so briefly and carry on.
        """.trimIndent()
    }
}
