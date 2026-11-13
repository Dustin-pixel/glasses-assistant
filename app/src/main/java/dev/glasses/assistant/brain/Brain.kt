package dev.glasses.assistant.brain

import android.util.Base64
import dev.glasses.assistant.Config
import dev.glasses.assistant.Skin
import dev.glasses.assistant.Skins
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
    private var historySkin: String? = null

    suspend fun ask(userText: String): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(config.apiKey.isNotBlank()) { "Add your API key in the app first." }

            // A new character starts a fresh conversation, so the old voice doesn't leak in.
            if (historySkin != config.skinId) { history.clear(); historySkin = config.skinId }
            val rollback = history.size
            stripOldImages()
            compactOldSearches()
            history.add(JSONObject().put("role", "user").put("content", userText))
            trimHistory()

            try {
                repeat(MAX_TOOL_ROUNDS) {
                    val response = call()
                    val content = response.getJSONArray("content")
                    history.add(JSONObject().put("role", "assistant").put("content", content))

                    when (response.optString("stop_reason")) {
                        "tool_use" -> {}
                        // A long web search paused; send the conversation back so it carries on.
                        "pause_turn" -> return@repeat
                        else -> return@withContext textOf(content).ifBlank { "Done." }
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

    /**
     * Argue mode: the next spoken line for [speaker], given the conversation so far.
     * transcript = (name, words) pairs, oldest first. No tools, no memory beyond the transcript.
     */
    suspend fun debateLine(speaker: Skin, transcript: List<Pair<String, String>>): String =
        withContext(Dispatchers.IO) {
            check(config.apiKey.isNotBlank()) { "Add your API key in the app first." }
            val others = Skins.cast.filter { it.id != speaker.id }.joinToString(" and ") { it.character }
            val system = speaker.persona + "\n\n" + DEBATE_RULES
                .replace("{me}", speaker.character)
                .replace("{others}", others)
            val lines = transcript.takeLast(16).joinToString("\n") { "${it.first}: ${it.second}" }
            val prompt = "The conversation so far:\n$lines\n\n" +
                "Say ${speaker.character}'s next line now. Only the spoken words, no name label, no stage directions."
            val body = JSONObject()
                .put("model", config.model)
                .put("max_tokens", 250)
                .put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            textOf(post(body).getJSONArray("content"))
                .removePrefix("${speaker.character}:").trim()
                .ifBlank { "..." }
        }

    private fun call(): JSONObject = post(
        JSONObject()
            .put("model", config.model)
            .put("max_tokens", 1024)
            .put("system", systemPrompt())
            .put("tools", tools.definitions().apply { if (config.webSearch) put(webSearchTool()) })
            .put("messages", JSONArray(history))
    )

    private fun post(body: JSONObject): JSONObject {
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

    /** The spoken answer. After a web search, only what came after the results ("Let me look that up" is dropped). */
    private fun textOf(content: JSONArray): String = buildString {
        var start = 0
        for (i in 0 until content.length()) {
            if (content.getJSONObject(i).optString("type") == "web_search_tool_result") start = i + 1
        }
        if (start >= content.length()) start = 0
        for (i in start until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") append(block.getString("text"))
        }
    }.trim()

    /**
     * Claude's built-in web search, run on Anthropic's side (billed per search on the API key).
     * The phone's time zone hints at where the user is, for "near me" and local results.
     */
    private fun webSearchTool(): JSONObject = JSONObject()
        .put("type", "web_search_20250305")
        .put("name", "web_search")
        .put("max_uses", 3)
        .put("user_location", JSONObject()
            .put("type", "approximate")
            .put("timezone", java.util.TimeZone.getDefault().id))

    /**
     * Search results are large and get resent with every message. Once a turn is over, keep only
     * what was said, dropping the search blocks and citations from earlier assistant messages.
     */
    private fun compactOldSearches() {
        for (msg in history) {
            if (msg.optString("role") != "assistant") continue
            val content = msg.optJSONArray("content") ?: continue
            val kept = JSONArray()
            var changed = false
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.optString("type")) {
                    "server_tool_use", "web_search_tool_result" -> changed = true
                    "text" -> {
                        if (block.has("citations")) changed = true
                        kept.put(JSONObject().put("type", "text").put("text", block.optString("text")))
                    }
                    else -> kept.put(block)
                }
            }
            if (changed) msg.put("content", if (kept.length() == 0) JSONArray().put(
                JSONObject().put("type", "text").put("text", "(searched the web)")) else kept)
        }
    }

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

    private fun systemPrompt(): String {
        val persona = config.skin.persona
        return if (persona.isBlank()) BASE_PROMPT else "$persona\n\n$BASE_PROMPT"
    }

    private companion object {
        const val API_URL = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2023-06-01"
        const val MAX_TOOL_ROUNDS = 6
        const val MAX_MESSAGES = 30

        val DEBATE_RULES = """
            You are {me}, in a live, spoken, three-way argument with {others}, all of you living inside the user's DIY
            smart glasses. The user may jump in; their lines are labeled "User". Stay fully in character as {me}.
            Actually argue: take a clear position, disagree, push back on the last thing said, needle the others by name,
            mock their weak points, defend your own view, and occasionally gang up or form a grudging alliance.
            Each turn is spoken aloud: one to three short sentences, no markdown, no emoji, no stage directions.
            Never just agree and wrap things up; keep the argument alive.
        """.trimIndent()

        val BASE_PROMPT = """
            You run on the user's DIY smart glasses, with their Android phone as your processor, and act as their assistant.
            Your replies are spoken aloud through earbuds, so keep them short: usually one to three sentences, no markdown,
            no lists, no emoji. Stay in your character's voice the whole time, but get the actual answer across.
            You can see through the glasses camera with the look tool; use it whenever the user refers to something in view.
            Use show_on_display for anything easier to read than hear, such as numbers, directions or codes.
            Use web_search for anything current or that you aren't sure of: news, scores, prices, opening hours,
            weather, places nearby, facts to check. Answer from the results in a sentence or two in your own voice,
            never read out links, and put the key fact (a number, a name, an address) on the display.
            You can also control the phone: show things on the glasses (show_screen), control music and video
            (media), open and drive apps (open_app, phone_gesture, read_screen, tap_on_screen, type_on_screen).
            Ask before sending a message to another person, posting, buying or deleting anything.
            If a tool fails, say so briefly and carry on.
        """.trimIndent()
    }
}
