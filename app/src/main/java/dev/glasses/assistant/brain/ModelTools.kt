package dev.glasses.assistant.brain

import dev.glasses.assistant.display.ModelView
import dev.glasses.assistant.model3d.ModelLibrary
import dev.glasses.assistant.model3d.ModelSpec
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject

private fun enumOf(desc: String, vararg values: String) =
    JSONObject().put("type", "string").put("description", desc).put("enum", JSONArray(values.toList()))

private fun numberProp(desc: String) = JSONObject().put("type", "number").put("description", desc)
private fun vec3(desc: String) = JSONObject().put("type", "array").put("description", desc)
    .put("items", JSONObject().put("type", "number")).put("minItems", 3).put("maxItems", 3)

/** Makes a 3D model from simple shapes and shows it on the glasses. */
class CreateModelTool(
    private val library: ModelLibrary,
    private val view: ModelView,
    private val scope: CoroutineScope,
) : Tool {
    override val name = "create_model"
    override val description =
        "Create a 3D model from simple solid shapes and show it on the glasses, where it slowly turns. Use it when " +
            "the user wants to see, design or sketch an object. Units are millimetres, Z is up, and every shape is " +
            "centred on its \"at\" point. Shapes: box (size [x,y,z]), cylinder (radius, height; or radius_bottom and " +
            "radius_top), cone (radius, height), sphere (radius), torus (radius to the tube's middle, tube). Rotate " +
            "with [rx,ry,rz] in degrees, applied X then Y then Z. Give parts sensible colors. To change a model, call " +
            "this again with the same name and the full updated list of parts. Saved models can be opened later."
    override val inputSchema = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("name", JSONObject().put("type", "string").put("description", "Short name, e.g. \"desk lamp\""))
            .put("parts", JSONObject().put("type", "array").put("maxItems", ModelSpec.MAX_PARTS).put("items", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("shape", enumOf("Which solid", "box", "cylinder", "cone", "sphere", "torus"))
                    .put("size", vec3("box: [x, y, z] in mm"))
                    .put("radius", numberProp("cylinder, cone, sphere; torus: to the tube's middle"))
                    .put("radius_bottom", numberProp("cylinder: radius at the bottom"))
                    .put("radius_top", numberProp("cylinder or cone: radius at the top"))
                    .put("height", numberProp("cylinder, cone"))
                    .put("tube", numberProp("torus: the tube's radius"))
                    .put("at", vec3("centre position [x, y, z] in mm"))
                    .put("rotate", vec3("[rx, ry, rz] in degrees"))
                    .put("color", JSONObject().put("type", "string").put("description", "#rrggbb")))
                .put("required", JSONArray(listOf("shape"))))))
        .put("required", JSONArray(listOf("name", "parts")))

    override suspend fun run(input: JSONObject): ToolResult = try {
        val m = library.saveSpec(JSONObject().put("name", input.getString("name")).put("parts", input.getJSONArray("parts")))
        view.show(scope, m)
        ToolResult.Text("Showing it on the glasses. " + ModelSpec.summary(m) +
            " Use look_at_model if you want to check how it came out.")
    } catch (e: Exception) {
        ToolResult.Text("Couldn't build that model: ${e.message}. Fix it and try again.")
    }
}

/** Opens a saved, imported or built-in model. */
class OpenModelTool(
    private val library: ModelLibrary,
    private val view: ModelView,
    private val scope: CoroutineScope,
) : Tool {
    override val name = "open_model"
    override val description =
        "Show a saved 3D model on the glasses, or list them. Includes models you made, STL files the user " +
            "imported, and the glasses' own printed parts (eyepiece, front_cap, sled, clip, arm, battery_clip)."
    override val inputSchema = schema(mapOf("name" to ("string" to "Which model; leave out to list them")))

    override suspend fun run(input: JSONObject): ToolResult {
        val name = input.optString("name").trim()
        if (name.isEmpty()) return ToolResult.Text("Models: " + library.list().joinToString())
        return try {
            val m = library.load(name)
            view.show(scope, m)
            val recipe = m.spec?.let { " Its recipe: ${it.optJSONArray("parts")}" }.orEmpty()
            ToolResult.Text("Showing it. " + ModelSpec.summary(m) + recipe)
        } catch (e: Exception) {
            ToolResult.Text(e.message ?: "Couldn't open that model.")
        }
    }
}

/** Zooms and turns the model on the glasses. */
class ModelViewTool(private val view: ModelView) : Tool {
    override val name = "model_view"
    override val description = "Zoom or turn the 3D model showing on the glasses, or face it from a side."
    override val inputSchema = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("action", enumOf("What to do", "zoom_in", "zoom_out", "turn_left", "turn_right", "tilt_up",
                "tilt_down", "front", "back", "left", "right", "top", "bottom", "spin", "stop_spin", "reset")))
        .put("required", JSONArray(listOf("action")))

    override suspend fun run(input: JSONObject): ToolResult {
        if (view.model == null) return ToolResult.Text("No model is showing. Use open_model or create_model first.")
        when (input.getString("action")) {
            "zoom_in" -> view.zoomBy(1.5f)
            "zoom_out" -> view.zoomBy(1 / 1.5f)
            "turn_left" -> view.rotateBy(-45f, 0f)
            "turn_right" -> view.rotateBy(45f, 0f)
            "tilt_up" -> view.rotateBy(0f, -25f)
            "tilt_down" -> view.rotateBy(0f, 25f)
            "front" -> view.face(0f, 0f)
            "back" -> view.face(180f, 0f)
            "left" -> view.face(90f, 0f)
            "right" -> view.face(-90f, 0f)
            "top" -> view.face(0f, 89f)
            "bottom" -> view.face(0f, -89f)
            "spin" -> view.spinning = true
            "stop_spin" -> view.spinning = false
            else -> view.reset()
        }
        return ToolResult.Text("Done.")
    }
}

/** Lets Claude see the model, to talk about it or check its own work. */
class LookAtModelTool(private val view: ModelView) : Tool {
    override val name = "look_at_model"
    override val description =
        "See the 3D model on the glasses as it looks right now, as a picture, with its size. Use it to discuss " +
            "the model with the user or to check a model you just made. Optionally turn it to a side first."
    override val inputSchema = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("side", enumOf("Turn it to this side first (optional)", "as_is", "front", "back", "left", "right", "top", "angled")))

    override suspend fun run(input: JSONObject): ToolResult {
        val m = view.model ?: return ToolResult.Text("No model is showing.")
        when (input.optString("side", "as_is")) {
            "front" -> view.face(0f, 0f)
            "back" -> view.face(180f, 0f)
            "left" -> view.face(90f, 0f)
            "right" -> view.face(-90f, 0f)
            "top" -> view.face(0f, 89f)
            "angled" -> view.face(35f, 25f)
        }
        val jpeg = view.snapshot() ?: return ToolResult.Text("Couldn't draw it.")
        return ToolResult.Image(jpeg, ModelSpec.summary(m) +
            " View: turned ${view.view.yaw.toInt()}°, tilted ${view.view.pitch.toInt()}°, zoom ${"%.1f".format(view.view.zoom)}×.")
    }
}
