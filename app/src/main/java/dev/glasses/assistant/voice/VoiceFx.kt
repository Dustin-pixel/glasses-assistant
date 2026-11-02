package dev.glasses.assistant.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** The sound treatment a skin's voice gets. */
enum class Fx { NONE, METAL, SYSTEM, GLITCH }

class Pcm(val samples: FloatArray, val rate: Int)

/**
 * Small offline effects chain applied to the phone's synthesized speech.
 * Everything works on mono float samples in [-1, 1].
 */
object VoiceFx {

    fun apply(fx: Fx, x: FloatArray, sr: Int): FloatArray {
        val y = when (fx) {
            Fx.NONE -> x.copyOf()
            Fx.METAL -> metal(x, sr)
            Fx.SYSTEM -> system(x, sr)
            Fx.GLITCH -> glitch(x, sr)
        }
        normalize(y, 0.92f)
        return y
    }

    /** Ultron: doubled voice with a wobbling delay, a buzzing metallic modulation and a little grit. */
    private fun metal(x: FloatArray, sr: Int): FloatArray {
        val out = FloatArray(x.size)
        val base = 0.017 * sr
        val depth = 0.004 * sr
        val drive = 1.7
        val norm = tanh(drive)
        for (n in x.indices) {
            val lfo = sin(2 * PI * 0.33 * n / sr)
            val d = (base + depth * lfo).toInt()
            val doubled = if (n - d >= 0) x[n - d] else 0f
            var y = 0.8f * x[n] + 0.45f * doubled
            val ring = sin(2 * PI * 46.0 * n / sr).toFloat()
            y *= 0.68f + 0.32f * ring
            out[n] = (tanh(y * drive) / norm).toFloat()
        }
        return out
    }

    /** Great Sage: thin and even, with a short robotic resonance and a faint echo. */
    private fun system(x: FloatArray, sr: Int): FloatArray {
        // High-pass around 200 Hz takes the warmth out.
        val hp = FloatArray(x.size)
        val rc = 1.0 / (2 * PI * 200.0)
        val a = (rc / (rc + 1.0 / sr)).toFloat()
        for (n in 1 until x.size) hp[n] = a * (hp[n - 1] + x[n] - x[n - 1])

        // Feedback comb (~6 ms) gives the synthetic, boxed-in timbre.
        val comb = FloatArray(hp.size)
        val cd = (0.006 * sr).toInt().coerceAtLeast(1)
        for (n in hp.indices) comb[n] = hp[n] + if (n >= cd) 0.42f * comb[n - cd] else 0f

        // Faint echo, with a tail so it isn't cut off.
        val ed = (0.17 * sr).toInt()
        val out = FloatArray(comb.size + 2 * ed)
        for (n in out.indices) {
            val dry = if (n < comb.size) comb[n] else 0f
            val e1 = if (n - ed in comb.indices) comb[n - ed] else 0f
            val e2 = if (n - 2 * ed in comb.indices) comb[n - 2 * ed] else 0f
            out[n] = dry + 0.22f * e1 + 0.08f * e2
        }
        return out
    }

    /** Capri: a bit of digital crunch, with random stutters, dropouts and crushed bursts. */
    private fun glitch(x: FloatArray, sr: Int): FloatArray {
        val rnd = Random(x.size)
        val out = FloatBuilder(x.size + x.size / 3)
        var n = 0
        var nextGlitch = (sr * (0.5 + rnd.nextDouble() * 0.6)).toInt()
        while (n < x.size) {
            if (n >= nextGlitch && n + sr / 8 < x.size) {
                val len = (sr * (0.06 + rnd.nextDouble() * 0.06)).toInt()
                when (rnd.nextInt(3)) {
                    0 -> repeat(2 + rnd.nextInt(2)) { for (i in 0 until len) out.add(x[n + i]) }   // stutter
                    1 -> repeat((sr * 0.035).toInt()) { out.add(0f) }                               // dropout
                    else -> for (i in 0 until len) out.add(crush(x[n + i - (i % 4)], 4))            // crushed burst
                }
                n += len
                nextGlitch = n + (sr * (0.7 + rnd.nextDouble() * 0.8)).toInt()
            } else {
                // Light constant crunch so she always sounds digital.
                out.add(0.85f * x[n] + 0.15f * crush(x[n], 6))
                n++
            }
        }
        return out.toArray()
    }

    private fun crush(v: Float, bits: Int): Float {
        val steps = (1 shl (bits - 1)).toFloat()
        return Math.round(v * steps) / steps
    }

    private fun normalize(y: FloatArray, target: Float) {
        var peak = 0f
        for (v in y) peak = maxOf(peak, abs(v))
        if (peak < 1e-4f) return
        val g = target / peak
        for (i in y.indices) y[i] *= g
    }

    /** Reads a 16-bit PCM WAV (as written by TextToSpeech.synthesizeToFile) into mono floats. */
    fun readWav(file: File): Pcm? {
        val b = file.readBytes()
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var format = 0; var channels = 1; var rate = 0; var bits = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                format = bb.getShort(body).toInt()
                channels = bb.getShort(body + 2).toInt().coerceAtLeast(1)
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
            } else if (id == "data") {
                if (format != 1 || bits != 16 || rate <= 0) return null
                val end = if (size <= 0 || body + size > b.size) b.size else body + size
                val frames = (end - body) / (2 * channels)
                val out = FloatArray(frames) { i -> bb.getShort(body + i * 2 * channels) / 32768f }
                return Pcm(out, rate)
            }
            if (size < 0) return null
            pos = body + size + (size and 1)
        }
        return null
    }

    private class FloatBuilder(capacity: Int) {
        private var data = FloatArray(maxOf(capacity, 16))
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
