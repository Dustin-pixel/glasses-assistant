package dev.glasses.assistant.brain

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import dev.glasses.assistant.control.ControlService
import dev.glasses.assistant.display.Crop
import dev.glasses.assistant.display.Media
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** What the display tools need from the assistant service. */
interface DisplayControl {
    fun showNowPlaying(): String
    fun showMirror(crop: Crop, zoom: Float?, pan: Float?): String
    fun showText(title: String, text: String): String
    fun showOff(): String
}

private fun enumSchema(desc: String, vararg values: String) =
    JSONObject().put("type", "string").put("description", desc).put("enum", JSONArray(values.toList()))

private fun noControl() = ToolResult.Text(
    "Phone control is off. Tell the user to turn on Phone control in the Glasses Assistant app " +
        "(Accessibility settings) so you can do that."
)

// ---------------------------------------------------------------- display

class ShowScreenTool(private val display: DisplayControl) : Tool {
    override val name = "show_screen"
    override val description =
        "Choose what fills the glasses display. now_playing: a card for the music or video playing (Spotify, " +
            "YouTube Music...). mirror: the phone screen itself, for watching YouTube or TikTok or glancing at any " +
            "app (the first time, the user has to tap Start on the phone). text: show longer text you provide, " +
            "paged to fit the round screen, e.g. a reply read from the Claude app. off: back to the normal display."
    override val inputSchema = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("mode", enumSchema("What to show", "now_playing", "mirror", "text", "off"))
            .put("crop", enumSchema(
                "mirror only. auto picks by app: fill (a square from the middle, for TikTok/Reels/Shorts), " +
                    "video (the video strip in YouTube), fit (whole screen, small), zoom (closer look at part of it)",
                "auto", "fit", "fill", "video", "zoom"))
            .put("zoom", JSONObject().put("type", "number").put("description", "zoom crop: 1-4, default 2"))
            .put("pan", JSONObject().put("type", "number").put("description", "zoom crop: 0 = top of screen, 1 = bottom"))
            .put("title", JSONObject().put("type", "string").put("description", "text mode: a short heading"))
            .put("text", JSONObject().put("type", "string").put("description", "text mode: what to show")))
        .put("required", JSONArray(listOf("mode")))

    override suspend fun run(input: JSONObject): ToolResult = ToolResult.Text(
        when (input.getString("mode")) {
            "now_playing" -> display.showNowPlaying()
            "mirror" -> display.showMirror(
                when (input.optString("crop", "auto")) {
                    "fit" -> Crop.Fit; "fill" -> Crop.Fill; "video" -> Crop.Video; "zoom" -> Crop.Zoom
                    else -> Crop.Auto
                },
                input.optDouble("zoom").takeIf { !it.isNaN() }?.toFloat(),
                input.optDouble("pan").takeIf { !it.isNaN() }?.toFloat(),
            )
            "text" -> display.showText(input.optString("title", ""), input.optString("text", ""))
            else -> display.showOff()
        }
    )
}

// ---------------------------------------------------------------- media

class MediaTool(private val context: Context) : Tool {
    override val name = "media"
    override val description =
        "Control music and video on the phone (Spotify, YouTube, YouTube Music, podcasts, any player). " +
            "play_search starts something by name, e.g. an artist, song, album or playlist; it uses Spotify unless " +
            "the user names another app. status says what's playing."
    override val inputSchema = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("action", enumSchema("What to do", "play", "pause", "next", "previous",
                "skip_forward_30s", "skip_back_15s", "play_search", "status", "volume_up", "volume_down"))
            .put("query", JSONObject().put("type", "string").put("description", "play_search: what to play"))
            .put("app", JSONObject().put("type", "string").put("description", "play_search: spotify (default), youtube_music, youtube")))
        .put("required", JSONArray(listOf("action")))

    override suspend fun run(input: JSONObject): ToolResult {
        val action = input.getString("action")
        val c = Media.current(context)
        val am = context.getSystemService(android.media.AudioManager::class.java)
        fun key(code: Int) {
            val t = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        }
        val t = c?.transportControls
        when (action) {
            "status" -> return ToolResult.Text(
                c?.let { Media.describe(context, it) }
                    ?: if (Media.hasAccess(context)) "Nothing is playing." else
                        "Can't see what's playing: Notification access is off for Glasses Assistant."
            )
            "play" -> t?.play() ?: key(KeyEvent.KEYCODE_MEDIA_PLAY)
            "pause" -> t?.pause() ?: key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            "next" -> t?.skipToNext() ?: key(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> t?.skipToPrevious() ?: key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "skip_forward_30s", "skip_back_15s" -> {
                c ?: return ToolResult.Text("Nothing is playing.")
                val delta = if (action == "skip_forward_30s") 30_000 else -15_000
                c.transportControls.seekTo((Media.position(c) + delta).coerceAtLeast(0))
            }
            "volume_up" -> am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, 0)
            "volume_down" -> am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, 0)
            "play_search" -> return ToolResult.Text(playSearch(input.optString("query"), input.optString("app", "spotify")))
            else -> return ToolResult.Text("Unknown action $action.")
        }
        delay(700)
        return ToolResult.Text(Media.current(context)?.let { "Done. Now: " + Media.describe(context, it) } ?: "Done.")
    }

    private suspend fun playSearch(query: String, app: String): String {
        if (query.isBlank()) return "Say what to play."
        val pkg = when (app.lowercase().replace(" ", "_")) {
            "youtube_music" -> Apps.YT_MUSIC
            "youtube" -> Apps.YOUTUBE
            else -> Apps.SPOTIFY
        }
        if (!Apps.installed(context, pkg)) return "${Apps.label(pkg)} isn't installed."
        // If that app already has a player running, ask it directly; no screen needed.
        Media.forPackage(context, pkg)?.let {
            it.transportControls.playFromSearch(query, Bundle())
            delay(2500)
            if (it.playbackState?.state == PlaybackState.STATE_PLAYING) return "Playing. " + Media.describe(context, it)
        }
        val intent = if (pkg == Apps.YOUTUBE) {
            Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra("query", query)
        } else {
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
                .putExtra(SearchManager.QUERY, query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
        if (!Apps.launch(context, intent)) return Apps.CANT_OPEN
        delay(4000)
        return Media.forPackage(context, pkg)?.let { "Asked ${Apps.label(pkg)} to play it. Now: " + Media.describe(context, it) }
            ?: "Opened ${Apps.label(pkg)} with that search."
    }
}

// ---------------------------------------------------------------- apps

object Apps {
    const val SPOTIFY = "com.spotify.music"
    const val YOUTUBE = "com.google.android.youtube"
    const val YT_MUSIC = "com.google.android.apps.youtube.music"
    const val CLAUDE = "com.anthropic.claude"
    val TIKTOK = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
    const val CANT_OPEN =
        "Couldn't open it from the background. Tell the user to turn on Phone control in the Glasses Assistant app."

    fun installed(context: Context, pkg: String) =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun label(pkg: String) = when (pkg) {
        SPOTIFY -> "Spotify"; YOUTUBE -> "YouTube"; YT_MUSIC -> "YouTube Music"; CLAUDE -> "Claude"
        in TIKTOK -> "TikTok"; else -> pkg
    }

    /** Android blocks apps from opening screens from the background; the Phone control service is allowed to. */
    fun launch(context: Context, intent: Intent): Boolean {
        if (intent.resolveActivity(context.packageManager) == null && intent.`package` == null) return false
        return ControlService.instance?.launch(intent) ?: false
    }

    /** Any installed app by (part of) its name. */
    fun find(context: Context, name: String): String? {
        val pm = context.packageManager
        val want = name.trim().lowercase()
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val labelled = apps.map { it.activityInfo.packageName to it.loadLabel(pm).toString().lowercase() }
        return (labelled.firstOrNull { it.second == want } ?: labelled.firstOrNull { it.second.contains(want) })?.first
    }
}

class OpenAppTool(private val context: Context) : Tool {
    override val name = "open_app"
    override val description =
        "Open an app or a search on the phone. app: spotify, youtube, tiktok, claude, google, maps, or any " +
            "installed app's name. With query: YouTube and TikTok search for it, Google searches the web in the " +
            "browser, Maps searches places, Spotify searches music. To play music, prefer the media tool. " +
            "To start a chat in the Claude app, open it, then use type_on_screen with send=true."
    override val inputSchema = schema(
        mapOf(
            "app" to ("string" to "Which app"),
            "query" to ("string" to "Optional search"),
        ),
        required = listOf("app"),
    )

    override suspend fun run(input: JSONObject): ToolResult {
        val app = input.getString("app").trim().lowercase()
        val q = input.optString("query", "").trim()
        val enc = Uri.encode(q)
        val intent: Intent = when {
            app == "google" || app == "browser" || app == "web" ->
                Intent(Intent.ACTION_VIEW, Uri.parse(if (q.isEmpty()) "https://www.google.com" else "https://www.google.com/search?q=$enc"))
            app == "maps" || app == "google maps" ->
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc"))
            app == "youtube" -> if (q.isEmpty()) launcher(Apps.YOUTUBE) ?: return missing("YouTube")
                else Intent(Intent.ACTION_SEARCH).setPackage(Apps.YOUTUBE).putExtra("query", q)
            app == "tiktok" -> {
                val pkg = Apps.TIKTOK.firstOrNull { Apps.installed(context, it) } ?: return missing("TikTok")
                if (q.isEmpty()) launcher(pkg) ?: return missing("TikTok")
                else Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/search?q=$enc")).setPackage(pkg)
            }
            app == "spotify" -> if (q.isEmpty()) launcher(Apps.SPOTIFY) ?: return missing("Spotify")
                else Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$enc")).setPackage(Apps.SPOTIFY)
            app == "claude" -> launcher(Apps.CLAUDE) ?: return missing("Claude")
            else -> Apps.find(context, app)?.let { launcher(it) } ?: return missing(app)
        }
        if (!Apps.launch(context, intent)) return ToolResult.Text(Apps.CANT_OPEN)
        delay(1500)
        return ToolResult.Text("Opened.")
    }

    private fun launcher(pkg: String): Intent? = context.packageManager.getLaunchIntentForPackage(pkg)
    private fun missing(name: String) = ToolResult.Text("$name isn't installed on the phone.")
}

// ---------------------------------------------------------------- driving apps

class GestureTool : Tool {
    override val name = "phone_gesture"
    override val description =
        "Do a gesture on the phone screen. next/previous move through vertical feeds (next TikTok, next Short, " +
            "next Reel). double_tap likes a TikTok. Also scrolling, swipes, back, home, recents and notifications."
    override val inputSchema = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("gesture", enumSchema("Which gesture",
            "next", "previous", "scroll_down", "scroll_up", "swipe_left", "swipe_right",
            "tap_center", "double_tap", "back", "home", "recents", "notifications")))
        .put("required", JSONArray(listOf("gesture")))

    override suspend fun run(input: JSONObject): ToolResult {
        val svc = ControlService.instance ?: return noControl()
        return ToolResult.Text(svc.perform(input.getString("gesture")))
    }
}

class ReadScreenTool : Tool {
    override val name = "read_screen"
    override val description =
        "Read the text on the phone screen right now (which app, its text, buttons and text boxes). Use it to " +
            "answer about what's on screen, to read the latest reply in the Claude app, or before tapping something."
    override val inputSchema = schema()
    override suspend fun run(input: JSONObject): ToolResult {
        val svc = ControlService.instance ?: return noControl()
        return ToolResult.Text(svc.readScreen())
    }
}

class TapTool : Tool {
    override val name = "tap_on_screen"
    override val description = "Tap a button or item on the phone screen by its label (use read_screen to see labels)."
    override val inputSchema = schema(mapOf("label" to ("string" to "The button or item's text")), listOf("label"))
    override suspend fun run(input: JSONObject): ToolResult {
        val svc = ControlService.instance ?: return noControl()
        return ToolResult.Text(svc.tap(input.getString("label")))
    }
}

class TypeTool : Tool {
    override val name = "type_on_screen"
    override val description =
        "Type text into the text box on the phone screen, e.g. a prompt in the Claude app or a search. " +
            "send=true also presses Send. Ask first before sending a message to another person."
    override val inputSchema = schema(
        mapOf("text" to ("string" to "What to type"), "send" to ("boolean" to "Press send afterwards")),
        listOf("text"),
    )
    override suspend fun run(input: JSONObject): ToolResult {
        val svc = ControlService.instance ?: return noControl()
        return ToolResult.Text(svc.type(input.getString("text"), input.optBoolean("send", false)))
    }
}
