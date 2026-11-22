package dev.glasses.assistant.model3d

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** How the model is being looked at. */
data class View(
    val yaw: Float = 35f,      // degrees around the vertical axis
    val pitch: Float = 25f,    // degrees looking down from level
    val zoom: Float = 1f,      // 1 = whole model fits the circle
)

/**
 * A small software renderer: perspective, depth buffer, flat shading with a key light and a
 * soft fill light. Fast enough for a 240x240 picture at about 10 frames a second on a phone.
 * Draws both sides of every triangle, because STL files often have mixed-up winding.
 */
object Rasterizer {
    /** ARGB pixels, row by row. */
    fun render(mesh: Mesh, view: View, w: Int, h: Int, background: Int): IntArray {
        val px = IntArray(w * h) { background }
        if (mesh.triangleCount == 0) return px
        val depth = FloatArray(w * h) { Float.MAX_VALUE }

        // Centre on the model and scale it so its bounding sphere fits the round screen.
        val b = mesh.bounds()
        val cx = (b[0] + b[3]) / 2; val cy = (b[1] + b[4]) / 2; val cz = (b[2] + b[5]) / 2
        val radius = max(1e-3f, len3(b[3] - b[0], b[4] - b[1], b[5] - b[2]) / 2)
        val camDist = radius * 3.2f
        val focal = min(w, h) * 0.5f * 0.95f * camDist / radius * view.zoom / 1.05f

        val yaw = Math.toRadians(view.yaw.toDouble()); val pitch = Math.toRadians(view.pitch.toDouble())
        val cyw = cos(yaw).toFloat(); val syw = sin(yaw).toFloat()
        val cp = cos(pitch).toFloat(); val sp = sin(pitch).toFloat()

        // Transform every vertex into camera space: x right, y up, z away from the viewer.
        val n = mesh.vertexCount
        val vx = FloatArray(n); val vy = FloatArray(n); val vz = FloatArray(n)
        val sx = FloatArray(n); val sy = FloatArray(n)
        for (i in 0 until n) {
            val x0 = mesh.pos[i * 3] - cx; val y0 = mesh.pos[i * 3 + 1] - cy; val z0 = mesh.pos[i * 3 + 2] - cz
            // Turn around the vertical (Z) axis.
            val x1 = x0 * cyw - y0 * syw
            val y1 = x0 * syw + y0 * cyw
            // Model Y points away from the viewer, Z up. The camera looks down by `pitch`:
            // its forward is (0, cos p, -sin p) and its up is (0, sin p, cos p).
            val up = y1 * sp + z0 * cp
            val away = y1 * cp - z0 * sp + camDist
            vx[i] = x1; vy[i] = up; vz[i] = away
            val inv = focal / max(away, 1e-3f)
            sx[i] = w / 2f + x1 * inv
            sy[i] = h / 2f - up * inv
        }

        // The direction the key light travels: from the upper left and the viewer's side, onto the model.
        val lx = 0.45f; val ly = -0.75f; val lz = 0.5f
        val ll = len3(lx, ly, lz)

        val tris = mesh.tris
        for (t in 0 until mesh.triangleCount) {
            val a = tris[t * 3]; val bb = tris[t * 3 + 1]; val c = tris[t * 3 + 2]
            if (vz[a] <= 0 || vz[bb] <= 0 || vz[c] <= 0) continue
            // Face normal in camera space.
            val ux = vx[bb] - vx[a]; val uy = vy[bb] - vy[a]; val uz = vz[bb] - vz[a]
            val wx = vx[c] - vx[a]; val wy = vy[c] - vy[a]; val wz = vz[c] - vz[a]
            var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
            val nl = len3(nx, ny, nz)
            if (nl < 1e-12f) continue
            nx /= nl; ny /= nl; nz /= nl
            if (nz > 0) { nx = -nx; ny = -ny; nz = -nz }    // face the viewer (two-sided)
            val key = max(0f, -(nx * lx + ny * ly + nz * lz) / ll)
            val fill = max(0f, nx * 0.6f + ny * 0.1f) * 0.25f
            val light = min(1.15f, 0.28f + 0.75f * key + fill)
            val color = shade(mesh.colors[t], light)
            fill(px, depth, w, h, sx[a], sy[a], vz[a], sx[bb], sy[bb], vz[bb], sx[c], sy[c], vz[c], color)
        }
        return px
    }

    private fun shade(argb: Int, k: Float): Int {
        val r = min(255, ((argb shr 16 and 255) * k).toInt())
        val g = min(255, ((argb shr 8 and 255) * k).toInt())
        val bl = min(255, ((argb and 255) * k).toInt())
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun fill(
        px: IntArray, depth: FloatArray, w: Int, h: Int,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
        color: Int,
    ) {
        val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        if (abs(area) < 1e-6f) return
        val minX = max(0, min(x0, min(x1, x2)).toInt()); val maxX = min(w - 1, max(x0, max(x1, x2)).toInt() + 1)
        val minY = max(0, min(y0, min(y1, y2)).toInt()); val maxY = min(h - 1, max(y0, max(y1, y2)).toInt() + 1)
        if (minX > maxX || minY > maxY) return
        val inv = 1f / area
        for (y in minY..maxY) {
            val py = y + 0.5f
            for (x in minX..maxX) {
                val pxf = x + 0.5f
                val w0 = ((x1 - pxf) * (y2 - py) - (x2 - pxf) * (y1 - py)) * inv
                val w1 = ((x2 - pxf) * (y0 - py) - (x0 - pxf) * (y2 - py)) * inv
                val w2 = 1f - w0 - w1
                if (w0 < 0 || w1 < 0 || w2 < 0) continue
                val z = w0 * z0 + w1 * z1 + w2 * z2
                val i = y * w + x
                if (z < depth[i]) { depth[i] = z; px[i] = color }
            }
        }
    }
}
