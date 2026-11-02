package dev.glasses.assistant.car

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import dev.glasses.assistant.AssistantService
import dev.glasses.assistant.AssistantState
import dev.glasses.assistant.Config
import dev.glasses.assistant.Phase
import dev.glasses.assistant.R
import dev.glasses.assistant.Skins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android Auto entry point, built as a media app because Android Auto's "Unknown sources"
 * setting covers media apps (and not template apps) for sideloaded builds.
 *
 * In the car: a Controls tab (Talk, mode, argue controls) and a Characters tab. The
 * "now playing" card shows who is talking and what they said. Saying
 * "Hey Google, play <anything> on Glasses Assistant" sends <anything> straight in.
 */
class CarMediaService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private lateinit var config: Config
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastTreeKey: String? = null

    override fun onCreate() {
        super.onCreate()
        config = Config(this)
        session = MediaSessionCompat(this, "GlassesAssistant").apply {
            setCallback(callback)
            isActive = true
        }
        sessionToken = session.sessionToken
        scope.launch { AssistantService.state.collect { publish(it) } }
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ browse tree

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.sendResult(
            when (parentId) {
                ROOT -> mutableListOf(folder(CONTROLS, "Controls"), folder(CHARACTERS, "Characters"))
                CONTROLS -> controls(AssistantService.state.value)
                CHARACTERS -> characters()
                else -> mutableListOf()
            }
        )
    }

    private fun controls(s: AssistantState): MutableList<MediaBrowserCompat.MediaItem> {
        val argue = config.argueMode
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()
        val talkTitle = when {
            !argue -> "Talk"
            s.debate.isEmpty() -> "Give a topic"
            else -> "Butt in"
        }
        val talkHint = if (s.running) "Tap, then speak" else "Start the assistant on your phone first"
        items += playable(ID_TALK, talkTitle, talkHint)
        items += playable(
            ID_MODE,
            if (argue) "Mode: Argue" else "Mode: Assistant",
            if (argue) "Tap to switch to Assistant" else "Tap to switch to Argue",
        )
        if (argue) {
            items += playable(ID_CONTINUE, "Keep arguing", "Another round without you")
            items += playable(ID_NEW, "New argument", "Clear the current argument")
        }
        items += playable(ID_HUSH, "Stop talking", "Cut off whoever is speaking")
        return items
    }

    private fun characters(): MutableList<MediaBrowserCompat.MediaItem> =
        Skins.all.map { skin ->
            val current = skin.id == config.skinId
            playable(
                ID_SKIN_PREFIX + skin.id,
                if (current) "${skin.label} ✓" else skin.label,
                if (skin.id == "default") "Plain assistant" else skin.character,
            )
        }.toMutableList()

    private fun folder(id: String, title: String) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build(),
        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE,
    )

    private fun playable(id: String, title: String, subtitle: String) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),
        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
    )

    private fun refreshTree() {
        notifyChildrenChanged(CONTROLS)
        notifyChildrenChanged(CHARACTERS)
    }

    // ------------------------------------------------------------------ actions

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            when {
                mediaId == ID_TALK -> withAssistant { AssistantService.talk(this@CarMediaService) }
                mediaId == ID_MODE -> {
                    config.argueMode = !config.argueMode
                    refreshTree()
                    publish(AssistantService.state.value)
                }
                mediaId == ID_CONTINUE -> withAssistant { AssistantService.keepArguing(this@CarMediaService) }
                mediaId == ID_NEW -> withAssistant { AssistantService.newArgument(this@CarMediaService) }
                mediaId == ID_HUSH -> hush()
                mediaId?.startsWith(ID_SKIN_PREFIX) == true -> {
                    config.skinId = mediaId.removePrefix(ID_SKIN_PREFIX)
                    if (AssistantService.state.value.running) AssistantService.skinChanged(this@CarMediaService)
                    refreshTree()
                    publish(AssistantService.state.value)
                }
            }
        }

        /** "Hey Google, play <query> on Glasses Assistant": the query is what you said. */
        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            val text = query?.trim().orEmpty()
            withAssistant {
                if (text.isEmpty()) AssistantService.talk(this@CarMediaService)
                else AssistantService.ask(this@CarMediaService, text)
            }
        }

        override fun onPlay() = withAssistant { AssistantService.talk(this@CarMediaService) }
        override fun onPause() = hush()
        override fun onStop() = hush()
        override fun onSkipToNext() {
            if (config.argueMode) withAssistant { AssistantService.keepArguing(this@CarMediaService) }
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                ACTION_TALK -> withAssistant { AssistantService.talk(this@CarMediaService) }
                ACTION_CONTINUE -> withAssistant { AssistantService.keepArguing(this@CarMediaService) }
            }
        }
    }

    private fun hush() {
        if (AssistantService.state.value.running) AssistantService.hush(this)
    }

    /** Runs an action on the assistant, starting it first if Android allows that from the car. */
    private fun withAssistant(action: () -> Unit) {
        if (!AssistantService.state.value.running) {
            try {
                AssistantService.start(this)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start the assistant from the car", e)
                showMessage("Open Glasses Assistant on your phone and tap Start")
                return
            }
        }
        try {
            action()
        } catch (e: Exception) {
            Log.w(TAG, "Car action failed", e)
            showMessage("Open Glasses Assistant on your phone and tap Start")
        }
    }

    // ------------------------------------------------------------------ now playing

    private fun publish(s: AssistantState) {
        val argue = config.argueMode
        val busy = s.phase != Phase.Idle
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
            (if (argue) PlaybackStateCompat.ACTION_SKIP_TO_NEXT else 0L)

        val state = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(
                if (busy) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f,
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    ACTION_TALK, if (argue) "Butt in" else "Talk", R.drawable.ic_mic,
                ).build()
            )
        if (argue && s.debate.isNotEmpty()) {
            state.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(ACTION_CONTINUE, "Keep arguing", R.drawable.ic_forum).build()
            )
        }
        session.setPlaybackState(state.build())

        val (title, line) = nowPlaying(s, argue)
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, line)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, line)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Glasses Assistant")
                .build()
        )

        // Rebuild the lists only when something they show changes.
        val key = "${s.running}|$argue|${s.debate.isEmpty()}|${config.skinId}"
        if (key != lastTreeKey) {
            lastTreeKey = key
            refreshTree()
        }
    }

    private fun nowPlaying(s: AssistantState, argue: Boolean): Pair<String, String> {
        if (!s.running) return "Glasses Assistant" to "Start the assistant on your phone"
        val status = when (s.phase) {
            Phase.Listening -> "Listening…"
            Phase.Thinking -> "Thinking…"
            else -> null
        }
        return if (argue) {
            val last = s.debate.lastOrNull()
            (last?.who ?: "Argue mode") to (status ?: last?.text ?: "Tap Give a topic")
        } else {
            config.skin.label to (status ?: s.reply.ifEmpty { "Tap Talk and ask anything" })
        }
    }

    private fun showMessage(text: String) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Glasses Assistant")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, text)
                .build()
        )
    }

    private companion object {
        const val TAG = "CarMediaService"
        const val ROOT = "root"
        const val CONTROLS = "controls"
        const val CHARACTERS = "characters"
        const val ID_TALK = "talk"
        const val ID_MODE = "mode"
        const val ID_CONTINUE = "continue"
        const val ID_NEW = "new"
        const val ID_HUSH = "hush"
        const val ID_SKIN_PREFIX = "skin:"
        const val ACTION_TALK = "talk"
        const val ACTION_CONTINUE = "continue"
    }
}
