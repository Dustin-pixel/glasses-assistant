package dev.glasses.assistant.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import dev.glasses.assistant.Skin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Longer text laid out to fit the round display, a page at a time. Each line is as wide as the circle
 * allows at its height. Pages turn by themselves at a comfortable reading pace, with dots showing
 * where you are; after the last page it goes back to what was showing before.
 */
class TextCard(private val screen: GlassesScreen, private val skin: () -> Skin) {
    private var job: Job? = null

    fun show(scope: CoroutineScope, title: String, body: String, previous: Source) {
        job?.cancel()
        screen.setSource(Source.Text)
        val pages = paginate(body)
        job = scope.launch {
            pages.forEachIndexed { i, lines ->
                screen.offer(Source.Text, render(title, lines, i, pages.size))
                val words = lines.sumOf { it.split(' ').size }
                delay((words * 330L).coerceIn(3500, 12_000))
            }
            delay(6000)
            if (screen.source.value == Source.Text) screen.setSource(previous)
        }
    }

    fun stop() { job?.cancel() }

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = FONT
        typeface = Typeface.DEFAULT
    }

    /** Usable width of the circle for a line whose middle sits at [y]. */
    private fun widthAt(y: Float): Float {
        val r = GlassesScreen.SIZE / 2f - 14f
        val dy = y - GlassesScreen.SIZE / 2f
        return if (dy * dy >= r * r) 0f else 2 * sqrt(r * r - dy * dy)
    }

    private fun rows(): List<Float> = (0 until ROWS).map { TOP + it * LINE + LINE / 2 }

    fun paginate(text: String): List<List<String>> {
        val words = text.replace(Regex("\\s+"), " ").trim().split(' ').filter { it.isNotEmpty() }.toMutableList()
        val pages = mutableListOf<List<String>>()
        while (words.isNotEmpty()) {
            val lines = mutableListOf<String>()
            for (y in rows()) {
                if (words.isEmpty()) break
                val w = widthAt(y)
                var line = ""
                while (words.isNotEmpty()) {
                    val next = if (line.isEmpty()) words[0] else "$line ${words[0]}"
                    if (paint.measureText(next) <= w || line.isEmpty()) {
                        line = next
                        words.removeAt(0)
                        if (paint.measureText(line) > w) break   // a single very long word
                    } else break
                }
                lines += line
            }
            pages += lines
        }
        return pages.ifEmpty { listOf(listOf("")) }
    }

    private fun render(title: String, lines: List<String>, page: Int, pages: Int): Bitmap {
        val n = GlassesScreen.SIZE
        val s = skin()
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(s.background.toArgb())
        val accent = s.accent.toArgb()

        NowPlayingCard.text(cv, title, n / 2f, 30f, 12f, accent, bold = true, maxW = 120f)
        paint.color = s.text.toArgb()
        paint.textAlign = Paint.Align.CENTER
        rows().zip(lines).forEach { (y, line) -> cv.drawText(line, n / 2f, y + FONT * 0.35f, paint) }

        if (pages > 1) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val shown = pages.coerceAtMost(9)
            val start = n / 2f - (shown - 1) * 5f
            for (i in 0 until shown) {
                p.color = if (i == page.coerceAtMost(8)) accent else NowPlayingCard.withAlpha(accent, 70)
                cv.drawCircle(start + i * 10f, 222f, 3f, p)
            }
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = NowPlayingCard.withAlpha(accent, 90)
        }
        cv.drawArc(RectF(3f, 3f, n - 3f, n - 3f), 0f, 360f, false, ring)
        return bmp
    }

    private companion object {
        const val FONT = 17f
        const val LINE = 22f
        const val TOP = 40f
        const val ROWS = 8
    }
}
