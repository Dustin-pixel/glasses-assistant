package dev.glasses.assistant.display

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import dev.glasses.assistant.Skin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Needed only so Android lets this app see which music or video app is playing (Spotify, YouTube Music,
 * YouTube, podcasts...) and control it. The user switches it on under Notification access.
 * Notifications themselves are ignored.
 */
class MediaListener : NotificationListenerService()

/** Finds and controls whatever media is playing on the phone. */
object Media {
    fun hasAccess(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ComponentName(context, MediaListener::class.java).flattenToString())
    }

    /** Active players, best first: playing before paused, other apps before this one. */
    fun controllers(context: Context): List<MediaController> {
        if (!hasAccess(context)) return emptyList()
        val msm = context.getSystemService(MediaSessionManager::class.java)
        val all = runCatching {
            msm.getActiveSessions(ComponentName(context, MediaListener::class.java))
        }.getOrDefault(emptyList())
        return all.filter { it.packageName != context.packageName }
            .sortedByDescending { if (it.playbackState?.state == PlaybackState.STATE_PLAYING) 1 else 0 }
    }

    fun current(context: Context): MediaController? = controllers(context).firstOrNull()

    fun forPackage(context: Context, pkg: String): MediaController? =
        controllers(context).firstOrNull { it.packageName == pkg }

    fun appName(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** e.g. "Blinding Lights by The Weeknd on Spotify, playing, 1:12 of 3:20". */
    fun describe(context: Context, c: MediaController): String {
        val m = c.metadata
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val dur = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
        val pos = position(c)
        return buildString {
            append(title.ifBlank { "Something" })
            if (artist.isNotBlank()) append(" by ").append(artist)
            append(" on ").append(appName(context, c.packageName))
            append(if (playing) ", playing" else ", paused")
            if (dur > 0) append(", ${clock(pos)} of ${clock(dur)}")
        }
    }

    fun position(c: MediaController): Long {
        val st = c.playbackState ?: return 0
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        return pos.coerceAtLeast(0)
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/**
 * The "now playing" card for the round display: album art, title, artist and a progress ring
 * around the rim in the skin's color. Redrawn once a second while it's showing.
 */
class NowPlayingCard(
    private val context: Context,
    private val screen: GlassesScreen,
    private val skin: () -> Skin,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive && screen.source.value == Source.NowPlaying) {
                screen.offer(Source.NowPlaying, render(Media.current(context)))
                delay(1000)
            }
        }
    }

    fun render(c: MediaController?): Bitmap {
        val n = GlassesScreen.SIZE
        val s = skin()
        val accent = s.accent.toArgb()
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(s.background.toArgb())
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val m = c?.metadata
        val art = m?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }

        if (c == null) {
            text(cv, "Nothing playing", n / 2f, 112f, 20f, s.text.toArgb(), bold = true, maxW = 180f)
            text(cv, if (Media.hasAccess(context)) "Start music in any app" else "Turn on Notification access in the app",
                n / 2f, 140f, 13f, withAlpha(s.text.toArgb(), 170), maxW = 170f)
            return bmp
        }

        // Dimmed full-bleed art behind everything.
        if (art != null) {
            p.alpha = 70
            cv.drawBitmap(art, null, RectF(0f, 0f, n.toFloat(), n.toFloat()), p)
            p.alpha = 255
        }
        // Round cover in the upper middle.
        val r = 52f
        val cx = n / 2f
        val cy = 86f
        if (art != null) {
            val shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = (2 * r) / minOf(art.width, art.height)
            shader.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(cx - art.width * scale / 2, cy - art.height * scale / 2)
            })
            p.shader = shader
            cv.drawCircle(cx, cy, r, p)
            p.shader = null
        } else {
            p.color = withAlpha(accent, 60)
            cv.drawCircle(cx, cy, r, p)
            text(cv, "♪", cx, cy + 14, 40f, accent)
        }

        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { "Unknown" }
        val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        text(cv, title, cx, 164f, 18f, s.text.toArgb(), bold = true, maxW = 196f)
        text(cv, artist, cx, 184f, 13f, withAlpha(s.text.toArgb(), 190), maxW = 180f)

        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        text(cv, (if (playing) "▶  " else "❚❚  ") + Media.appName(context, c.packageName),
            cx, 206f, 11f, accent, maxW = 140f)

        // Progress ring around the rim.
        val dur = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
        val ring = RectF(5f, 5f, n - 5f, n - 5f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 5f
        p.strokeCap = Paint.Cap.ROUND
        p.color = withAlpha(accent, 50)
        cv.drawArc(ring, 0f, 360f, false, p)
        if (dur > 0) {
            p.color = accent
            val sweep = (Media.position(c).toFloat() / dur).coerceIn(0f, 1f) * 360f
            cv.drawArc(ring, -90f, sweep.coerceAtLeast(1f), false, p)
        }
        return bmp
    }

    companion object {
        /** Draws one line of centered text, shortened with "…" to fit [maxW]. */
        fun text(
            cv: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
            bold: Boolean = false, maxW: Float = 220f,
        ) {
            val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                textAlign = Paint.Align.CENTER
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            val fitted = TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END).toString()
            cv.drawText(fitted, x, y, tp)
        }

        fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    }
}
