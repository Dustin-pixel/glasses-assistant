package dev.glasses.assistant.brain

import dev.glasses.assistant.link.GlassesLink
import dev.glasses.assistant.link.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

sealed interface ToolResult {
    data class Text(val text: String) : ToolResult
    class Image(val jpeg: ByteArray, val note: String) : ToolResult
}

/** Something the assistant can do. Add new abilities by implementing this and registering it. */
interface Tool {
    val name: String
    val description: String
    val inputSchema: JSONObject
    suspend fun run(input: JSONObject): ToolResult
}

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

    fun definitions(): JSONArray = JSONArray().apply {
        byName.values.forEach {
            put(JSONObject()
                .put("name", it.name)
                .put("description", it.description)
                .put("input_schema", it.inputSchema))
        }
    }

    suspend fun run(name: String, input: JSONObject): ToolResult {
        val tool = byName[name] ?: return ToolResult.Text("Error: unknown tool $name")
        return try {
            tool.run(input)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Text("Error: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** Builds a JSON schema object. props: name to (type, description). */
fun schema(props: Map<String, Pair<String, String>> = emptyMap(), required: List<String> = emptyList()) =
    JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().apply {
            props.forEach { (k, v) -> put(k, JSONObject().put("type", v.first).put("description", v.second)) }
        })
        .put("required", JSONArray(required))

// ---------------------------------------------------------------- built-in tools

class LookTool(private val link: GlassesLink) : Tool {
    override val name = "look"
    override val description =
        "Capture a photo from the camera on the user's glasses, showing what they are looking at. " +
            "Use whenever the user refers to something they see (this, that, here, what am I looking at)."
    override val inputSchema = schema()
    override suspend fun run(input: JSONObject): ToolResult =
        ToolResult.Image(link.captureFrame(), "Photo from the glasses camera, just now.")
}

class ShowOnDisplayTool(private val link: GlassesLink) : Tool {
    override val name = "show_on_display"
    override val description =
        "Show short text on the glasses display. Use for things better read than heard: " +
            "numbers, directions, codes, short lists. Keep it under ~60 characters."
    override val inputSchema = schema(
        mapOf(
            "text" to ("string" to "Text to display"),
            "seconds" to ("integer" to "How long to show it (default 8)"),
        ),
        required = listOf("text"),
    )
    override suspend fun run(input: JSONObject): ToolResult {
        if (!link.isConnected) return ToolResult.Text("Glasses not connected; say it instead.")
        val seconds = input.optInt("seconds", 8).coerceIn(1, 120)
        link.send(Protocol.overlay(input.getString("text"), ttlMs = seconds * 1000L, pos = "top"))
        return ToolResult.Text("Shown.")
    }
}

class TimeTool : Tool {
    override val name = "get_time"
    override val description = "Get the current local date, time and time zone."
    override val inputSchema = schema()
    override suspend fun run(input: JSONObject): ToolResult =
        ToolResult.Text(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy, h:mm a z")))
}

class TimerTool(
    private val scope: CoroutineScope,
    private val onDone: suspend (label: String) -> Unit,
) : Tool {
    override val name = "set_timer"
    override val description = "Start a countdown timer. The user is alerted by voice and on the display when it ends."
    override val inputSchema = schema(
        mapOf(
            "seconds" to ("integer" to "Duration in seconds"),
            "label" to ("string" to "Short name for the timer, e.g. 'pasta'"),
        ),
        required = listOf("seconds"),
    )
    override suspend fun run(input: JSONObject): ToolResult {
        val seconds = input.getLong("seconds").coerceIn(1, 24 * 3600)
        val label = input.optString("label", "Timer")
        scope.launch {
            delay(seconds * 1000)
            onDone(label)
        }
        return ToolResult.Text("Timer '$label' set for $seconds seconds.")
    }
}
