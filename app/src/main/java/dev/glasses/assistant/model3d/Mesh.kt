package dev.glasses.assistant.model3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A triangle mesh in millimetres, Z up (the CAD convention). Each triangle has its own color.
 * Plain Kotlin with no Android types, so it can be tested on a computer.
 */
class Mesh(val pos: FloatArray, val tris: IntArray, val colors: IntArray) {
    val vertexCount get() = pos.size / 3
    val triangleCount get() = tris.size / 3

    /** min x, min y, min z, max x, max y, max z */
    fun bounds(): FloatArray {
        if (pos.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in pos.indices step 3) for (k in 0..2) {
            if (pos[i + k] < b[k]) b[k] = pos[i + k]
            if (pos[i + k] > b[k + 3]) b[k + 3] = pos[i + k]
        }
        return b
    }

    /** "42 × 18 × 30 mm" */
    fun sizeText(): String {
        val b = bounds()
        fun f(v: Float) = if (v >= 10) "%.0f".format(v) else "%.1f".format(v)
        return "${f(b[3] - b[0])} × ${f(b[4] - b[1])} × ${f(b[5] - b[2])} mm"
    }

    companion object {
        fun merge(parts: List<Mesh>): Mesh {
            val pos = FloatArray(parts.sumOf { it.pos.size })
            val tris = IntArray(parts.sumOf { it.tris.size })
            val colors = IntArray(parts.sumOf { it.colors.size })
            var p = 0; var t = 0; var c = 0; var base = 0
            for (m in parts) {
                m.pos.copyInto(pos, p); p += m.pos.size
                for (i in m.tris.indices) tris[t + i] = m.tris[i] + base
                t += m.tris.size
                m.colors.copyInto(colors, c); c += m.colors.size
                base += m.vertexCount
            }
            return Mesh(pos, tris, colors)
        }
    }
}

/** Collects vertices and triangles while a shape is being built. */
class MeshBuilder(private val color: Int) {
    private val pos = ArrayList<Float>()
    private val tris = ArrayList<Int>()

    fun v(x: Float, y: Float, z: Float): Int {
        pos += x; pos += y; pos += z
        return pos.size / 3 - 1
    }

    fun tri(a: Int, b: Int, c: Int) { tris += a; tris += b; tris += c }
    fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }

    fun build() = Mesh(pos.toFloatArray(), tris.toIntArray(), IntArray(tris.size / 3) { color })
}

/** The simple solids Claude builds models from. Sizes in mm; each is centred on the origin. */
object Shapes {
    fun box(sx: Float, sy: Float, sz: Float, color: Int): Mesh {
        val b = MeshBuilder(color)
        val x = sx / 2; val y = sy / 2; val z = sz / 2
        val p = arrayOf(
            b.v(-x, -y, -z), b.v(x, -y, -z), b.v(x, y, -z), b.v(-x, y, -z),
            b.v(-x, -y, z), b.v(x, -y, z), b.v(x, y, z), b.v(-x, y, z),
        )
        b.quad(p[0], p[3], p[2], p[1]); b.quad(p[4], p[5], p[6], p[7])
        b.quad(p[0], p[1], p[5], p[4]); b.quad(p[1], p[2], p[6], p[5])
        b.quad(p[2], p[3], p[7], p[6]); b.quad(p[3], p[0], p[4], p[7])
        return b.build()
    }

    /** A cylinder along Z; r1 at the bottom, r2 at the top (r2 = 0 makes a cone). */
    fun cylinder(r1: Float, r2: Float, h: Float, color: Int, segments: Int = 32): Mesh {
        val b = MeshBuilder(color)
        val z0 = -h / 2; val z1 = h / 2
        val bottom = IntArray(segments) { i ->
            val a = 2 * PI * i / segments
            b.v((r1 * cos(a)).toFloat(), (r1 * sin(a)).toFloat(), z0)
        }
        val top = IntArray(segments) { i ->
            val a = 2 * PI * i / segments
            b.v((r2 * cos(a)).toFloat(), (r2 * sin(a)).toFloat(), z1)
        }
        val cb = b.v(0f, 0f, z0); val ct = b.v(0f, 0f, z1)
        for (i in 0 until segments) {
            val j = (i + 1) % segments
            b.quad(bottom[i], bottom[j], top[j], top[i])
            if (r1 > 0) b.tri(cb, bottom[j], bottom[i])
            if (r2 > 0) b.tri(ct, top[i], top[j])
        }
        return b.build()
    }

    fun sphere(r: Float, color: Int, rings: Int = 16, segments: Int = 28): Mesh {
        val b = MeshBuilder(color)
        val grid = Array(rings + 1) { ring ->
            val phi = PI * ring / rings
            IntArray(segments) { s ->
                val th = 2 * PI * s / segments
                b.v((r * sin(phi) * cos(th)).toFloat(), (r * sin(phi) * sin(th)).toFloat(), (r * cos(phi)).toFloat())
            }
        }
        for (ring in 0 until rings) for (s in 0 until segments) {
            val t = (s + 1) % segments
            b.quad(grid[ring][s], grid[ring + 1][s], grid[ring + 1][t], grid[ring][t])
        }
        return b.build()
    }

    /** A ring lying flat in the XY plane: R to the middle of the tube, r the tube's radius. */
    fun torus(bigR: Float, r: Float, color: Int, segments: Int = 32, sides: Int = 14): Mesh {
        val b = MeshBuilder(color)
        val grid = Array(segments) { i ->
            val u = 2 * PI * i / segments
            IntArray(sides) { j ->
                val v = 2 * PI * j / sides
                val w = bigR + r * cos(v)
                b.v((w * cos(u)).toFloat(), (w * sin(u)).toFloat(), (r * sin(v)).toFloat())
            }
        }
        for (i in 0 until segments) for (j in 0 until sides) {
            val i2 = (i + 1) % segments; val j2 = (j + 1) % sides
            b.quad(grid[i][j], grid[i2][j], grid[i2][j2], grid[i][j2])
        }
        return b.build()
    }

    /** Rotates (degrees, about X then Y then Z), then moves a mesh, in place. */
    fun place(m: Mesh, rx: Float, ry: Float, rz: Float, tx: Float, ty: Float, tz: Float): Mesh {
        val (cx, sx) = cos(Math.toRadians(rx.toDouble())).toFloat() to sin(Math.toRadians(rx.toDouble())).toFloat()
        val (cy, sy) = cos(Math.toRadians(ry.toDouble())).toFloat() to sin(Math.toRadians(ry.toDouble())).toFloat()
        val (cz, sz) = cos(Math.toRadians(rz.toDouble())).toFloat() to sin(Math.toRadians(rz.toDouble())).toFloat()
        val p = m.pos
        for (i in p.indices step 3) {
            var x = p[i]; var y = p[i + 1]; var z = p[i + 2]
            var t = y * cx - z * sx; z = y * sx + z * cx; y = t          // about X
            t = x * cy + z * sy; z = -x * sy + z * cy; x = t             // about Y
            t = x * cz - y * sz; y = x * sz + y * cz; x = t              // about Z
            p[i] = x + tx; p[i + 1] = y + ty; p[i + 2] = z + tz
        }
        return m
    }
}

/** Reads STL files, binary or text. */
object Stl {
    fun parse(bytes: ByteArray, color: Int): Mesh {
        val text = bytes.size >= 5 && String(bytes, 0, 5) == "solid" &&
            String(bytes, 0, minOf(bytes.size, 1000)).contains("facet")
        return if (text) parseText(String(bytes), color) else parseBinary(bytes, color)
    }

    private fun parseBinary(b: ByteArray, color: Int): Mesh {
        require(b.size >= 84) { "That file is too short to be an STL." }
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val n = buf.getInt(80)
        require(n > 0 && 84L + n * 50L <= b.size) { "That STL file looks damaged." }
        val pos = FloatArray(n * 9)
        for (t in 0 until n) {
            val off = 84 + t * 50 + 12   // skip the stored normal
            for (k in 0 until 9) pos[t * 9 + k] = buf.getFloat(off + k * 4)
        }
        return Mesh(pos, IntArray(n * 3) { it }, IntArray(n) { color })
    }

    private fun parseText(s: String, color: Int): Mesh {
        val nums = ArrayList<Float>()
        for (line in s.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("vertex")) t.split(Regex("\\s+")).drop(1).take(3).forEach { nums += it.toFloat() }
        }
        val pos = nums.toFloatArray()
        val n = pos.size / 9
        require(n > 0) { "No triangles found in that STL file." }
        return Mesh(pos, IntArray(n * 3) { it }, IntArray(n) { color })
    }
}

internal fun len3(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
