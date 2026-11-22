package dev.glasses.assistant.model3d

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A model ready to show: its mesh, plus the recipe Claude made it from (null for STL files). */
class Model(val name: String, val mesh: Mesh, val spec: JSONObject?, val source: String)

/**
 * Where models live:
 *  - built in: the glasses' own printed parts (assets/models/ *.stl)
 *  - made by Claude: recipes saved as JSON in the app's storage (models/ *.json)
 *  - imported by the user: STL files copied into the app's storage (models/ *.stl)
 */
class ModelLibrary(private val context: Context) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    fun list(): List<String> {
        val builtIn = runCatching { context.assets.list("models")?.toList() }.getOrNull().orEmpty()
            .filter { it.endsWith(".stl") }.map { it.removeSuffix(".stl") }
        val mine = dir.listFiles().orEmpty().map { it.nameWithoutExtension }
        return (mine + builtIn).distinct().sorted()
    }

    fun load(name: String): Model {
        val slug = slug(name)
        File(dir, "$slug.json").takeIf { it.exists() }?.let { f ->
            val spec = JSONObject(f.readText())
            return Model(spec.optString("name", slug), ModelSpec.build(spec), spec, "made by Claude")
        }
        File(dir, "$slug.stl").takeIf { it.exists() }?.let { f ->
            return Model(slug, Stl.parse(f.readBytes(), DEFAULT_COLOR), null, "your STL file")
        }
        val asset = runCatching { context.assets.open("models/$slug.stl").use { it.readBytes() } }.getOrNull()
            ?: throw IllegalArgumentException("No model called \"$name\". Models: ${list().joinToString()}")
        return Model(slug, Stl.parse(asset, DEFAULT_COLOR), null, "part of the glasses")
    }

    fun saveSpec(spec: JSONObject): Model {
        val name = spec.optString("name").ifBlank { "model" }
        val mesh = ModelSpec.build(spec)        // throws a readable error on a bad recipe
        File(dir, "${slug(name)}.json").writeText(spec.toString(2))
        return Model(name, mesh, spec, "made by Claude")
    }

    /** Copies an STL the user picked (from Files, Downloads, Drive...) into the library. */
    fun import(uri: Uri, displayName: String?): Model {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Couldn't read that file.")
        val name = slug((displayName ?: "imported").removeSuffix(".stl").removeSuffix(".STL"))
        val mesh = Stl.parse(bytes, DEFAULT_COLOR)
        File(dir, "$name.stl").writeBytes(bytes)
        return Model(name, mesh, null, "your STL file")
    }

    companion object {
        const val DEFAULT_COLOR = 0xFF45C2B3.toInt()
        fun slug(s: String) = s.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "model" }
    }
}

/**
 * Builds a mesh from a recipe Claude writes:
 * {"name": "...", "parts": [{"shape": "box", "size": [x,y,z], "at": [x,y,z], "rotate": [rx,ry,rz], "color": "#rrggbb"}, ...]}
 * Shapes: box (size), cylinder (radius or radius_bottom/radius_top, height), cone (radius, height),
 * sphere (radius), torus (radius = to the tube's middle, tube = the tube's radius). Millimetres, Z up.
 */
object ModelSpec {
    const val MAX_PARTS = 200

    fun build(spec: JSONObject): Mesh {
        val parts = spec.optJSONArray("parts") ?: throw IllegalArgumentException("The model has no parts.")
        require(parts.length() in 1..MAX_PARTS) { "A model needs 1 to $MAX_PARTS parts." }
        return Mesh.merge((0 until parts.length()).map { i ->
            runCatching { part(parts.getJSONObject(i)) }.getOrElse {
                throw IllegalArgumentException("Part ${i + 1}: ${it.message}")
            }
        })
    }

    private fun part(p: JSONObject): Mesh {
        val color = color(p.optString("color"))
        fun num(key: String, default: Float? = null): Float {
            if (!p.has(key)) return default ?: throw IllegalArgumentException("missing \"$key\"")
            return p.getDouble(key).toFloat().also { require(it.isFinite() && it >= 0 && it < 10_000) { "\"$key\" is out of range" } }
        }
        val mesh = when (val shape = p.optString("shape").lowercase()) {
            "box", "cube" -> {
                val s = vec(p.optJSONArray("size")) ?: throw IllegalArgumentException("a box needs \"size\": [x, y, z]")
                Shapes.box(s[0], s[1], s[2], color)
            }
            "cylinder" -> {
                val r = num("radius", 5f)
                Shapes.cylinder(num("radius_bottom", r), num("radius_top", r), num("height"), color)
            }
            "cone" -> Shapes.cylinder(num("radius"), num("radius_top", 0f), num("height"), color)
            "sphere" -> Shapes.sphere(num("radius"), color)
            "torus", "ring" -> Shapes.torus(num("radius"), num("tube"), color)
            else -> throw IllegalArgumentException("unknown shape \"$shape\" (use box, cylinder, cone, sphere or torus)")
        }
        val at = vec(p.optJSONArray("at")) ?: floatArrayOf(0f, 0f, 0f)
        val rot = vec(p.optJSONArray("rotate")) ?: floatArrayOf(0f, 0f, 0f)
        return Shapes.place(mesh, rot[0], rot[1], rot[2], at[0], at[1], at[2])
    }

    private fun vec(a: JSONArray?): FloatArray? {
        if (a == null) return null
        require(a.length() == 3) { "expected three numbers, like [10, 20, 5]" }
        return FloatArray(3) { a.getDouble(it).toFloat() }
    }

    private fun color(s: String): Int = runCatching {
        val hex = s.trim().removePrefix("#")
        require(hex.length == 6)
        (0xFF shl 24) or hex.toInt(16)
    }.getOrDefault(ModelLibrary.DEFAULT_COLOR)

    /** A short description for Claude: size, parts, triangles. */
    fun summary(m: Model): String = buildString {
        append("\"${m.name}\" (${m.source}): ${m.mesh.sizeText()}, ${m.mesh.triangleCount} triangles")
        m.spec?.optJSONArray("parts")?.let { append(", ${it.length()} parts") }
        append(".")
    }
}
