package dev.glasses.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.app.Activity
import android.media.projection.MediaProjectionManager
import dev.glasses.assistant.brain.Brain
import dev.glasses.assistant.brain.DisplayControl
import dev.glasses.assistant.brain.GestureTool
import dev.glasses.assistant.brain.MediaTool
import dev.glasses.assistant.brain.OpenAppTool
import dev.glasses.assistant.brain.ReadScreenTool
import dev.glasses.assistant.brain.ShowScreenTool
import dev.glasses.assistant.brain.TapTool
import dev.glasses.assistant.brain.TypeTool
import dev.glasses.assistant.control.ControlService
import dev.glasses.assistant.display.Crop
import dev.glasses.assistant.display.GlassesScreen
import dev.glasses.assistant.display.MirrorConsentActivity
import dev.glasses.assistant.display.NowPlayingCard
import dev.glasses.assistant.display.ScreenMirror
import dev.glasses.assistant.display.Source
import dev.glasses.assistant.display.TextCard
import dev.glasses.assistant.brain.LookTool
import dev.glasses.assistant.brain.ShowOnDisplayTool
import dev.glasses.assistant.brain.TimeTool
import dev.glasses.assistant.brain.TimerTool
import dev.glasses.assistant.brain.ToolRegistry
import dev.glasses.assistant.link.GlassesLink
import dev.glasses.assistant.link.LinkEvent
import dev.glasses.assistant.link.Protocol
import dev.glasses.assistant.voice.NeuralVoice
import dev.glasses.assistant.voice.SpeechInput
import dev.glasses.assistant.voice.SpeechOutput
import dev.glasses.assistant.voice.VoicePack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { Idle, Listening, Thinking, Speaking }

data class AssistantState(
    val running: Boolean = false,
    val glasses: String? = null,          // connected device name, or null
    val phase: Phase = Phase.Idle,
    val heard: String = "",
    val reply: String = "",
    val error: String? = null,
    val debate: List<DebateLine> = emptyList(),
    val voice: String = "",                // which voice is speaking, and why
    val display: Source = Source.None,     // what's streaming to the glasses display
    val mirrorAllowed: Boolean = false,    // screen capture granted for this session
    val glassesIp: String? = null,         // the glasses' own address (virtual display page)
)

/** One spoken line in Argue mode. skinId is "user" for the person wearing the glasses. */
data class DebateLine(val who: String, val skinId: String, val text: String)

/**
 * Long-running hub: hosts the glasses link and runs the listen -> think -> speak loop.
 * A turn starts from the glasses button, the notification's Talk action, or the app.
 */
class AssistantService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var config: Config
    private lateinit var link: GlassesLink
    private lateinit var speechIn: SpeechInput
    private lateinit var speechOut: SpeechOutput
    private lateinit var brain: Brain
    private var turn: Job? = null

    override fun onCreate() {
        super.onCreate()
        config = Config(this)
        startInForeground()

        speechIn = SpeechInput(this)
        speechOut = SpeechOutput(this, neural = { neuralVoice() }) { ok, problem ->
            if (!ok) setVoice("Character voice failed while speaking, so the phone voice was used: $problem")
            else if (_state.value.voice.startsWith("Character voice failed")) setVoice("Character voices ready")
        }
        loadNeuralVoice()
        link = GlassesLink(Config.LINK_PORT, config.linkToken, ::onLinkEvent)
        screen = GlassesScreen(link, scope)
        nowPlaying = NowPlayingCard(this, screen) { config.skin }
        textCard = TextCard(screen) { config.skin }
        scope.launch { screen.source.collect { s -> _state.update { it.copy(display = s) } } }

        val tools = ToolRegistry(
            listOf(
                LookTool(link),
                ShowOnDisplayTool(link),
                ShowScreenTool(displayControl),
                MediaTool(this),
                OpenAppTool(this),
                GestureTool(),
                ReadScreenTool(),
                TapTool(),
                TypeTool(),
                TimeTool(),
                TimerTool(scope) { label ->
                    val msg = config.skin.timerDone(label)
                    link.send(Protocol.overlay(msg, ttlMs = 15_000, pos = "top"))
                    say(msg)
                },
            )
        )
        brain = Brain(config, tools)

        link.start()
        _state.update { it.copy(running = true, error = null) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TALK -> startTurn()
            ACTION_CONTINUE -> continueDebate()
            ACTION_ASK -> intent.getStringExtra(EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }?.let { askText(it) }
            ACTION_HUSH -> turn?.cancel()
            ACTION_CLEAR -> { turn?.cancel(); _state.update { it.copy(debate = emptyList()) } }
            ACTION_STOP -> stopSelf()
            ACTION_VOICES -> loadNeuralVoice()
            ACTION_TEST_VOICE -> {
                turn?.cancel()
                turn = scope.launch {
                    val skin = config.skin
                    setPhase(Phase.Speaking)
                    try {
                        neuralLoad?.join()
                        say(if (skin.id == "default") "Voice check. This is how I sound." else "Voice check. This is ${skin.character}.")
                    } finally {
                        setPhase(Phase.Idle)
                    }
                }
            }
            ACTION_DISPLAY -> when (intent.getStringExtra(EXTRA_MODE)) {
                "now_playing" -> displayControl.showNowPlaying()
                "mirror" -> displayControl.showMirror(Crop.Auto, null, null)
                else -> displayControl.showOff()
            }
            ACTION_MIRROR_RESULT -> onMirrorAnswer(
                intent.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED),
                @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(EXTRA_DATA),
            )
            ACTION_SKIN -> {
                link.send(Protocol.skin(config.skinId))
                link.send(Protocol.overlay(config.skin.ready, ttlMs = 2000))
            }
        }
        return START_STICKY
    }

    private fun onLinkEvent(event: LinkEvent) {
        when (event) {
            is LinkEvent.Connected -> {
                _state.update { it.copy(glasses = event.device, glassesIp = event.ip,
                    error = it.error?.takeUnless { e -> e.startsWith("Glasses tried") }) }
                link.send(Protocol.skin(config.skinId))
                link.send(Protocol.overlay(config.skin.ready, ttlMs = 2000))
                resendDisplay()
            }
            LinkEvent.Disconnected -> _state.update { it.copy(glasses = null, glassesIp = null) }
            is LinkEvent.Rejected -> _state.update {
                it.copy(error = "Glasses tried to connect with the wrong token \"${event.sentToken}\". " +
                    "The token is ${config.linkToken}. Redo the glasses' Wi-Fi setup and enter it again.")
            }
            is LinkEvent.ButtonPressed -> when (event.press) {
                "double" -> cycleDisplay()                       // off -> now playing -> mirror -> off
                "long" -> { turn?.cancel(); displayControl.showOff() }   // hush and clear the display
                else -> startTurn()
            }
            LinkEvent.FrameAck -> screen.onAck()
            is LinkEvent.Status -> Log.d(TAG, "Glasses status: ${event.json}")
        }
    }

    /** One conversational turn. Pressing again while busy interrupts and starts over. */
    private fun startTurn() {
        if (config.argueMode) return argueTurn()
        turn?.cancel()
        turn = scope.launch {
            try {
                setPhase(Phase.Listening)
                val skin = config.skin
                link.send(Protocol.overlay(skin.listening, ttlMs = 8000))
                val heard = speechIn.listen()?.trim()
                if (heard.isNullOrEmpty()) {
                    link.send(Protocol.clear())
                    return@launch
                }

                respond(heard)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Turn failed", e)
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                link.send(Protocol.overlay("Error", ttlMs = 3000))
                say("Something went wrong. Check the app for details.")
            } finally {
                setPhase(Phase.Idle)
            }
        }
    }

    /** Assistant mode: think about what was said and answer it. */
    private suspend fun respond(heard: String) {
        val skin = config.skin
        _state.update { it.copy(heard = heard, error = null) }
        setPhase(Phase.Thinking)
        link.send(Protocol.overlay(skin.thinking, ttlMs = 30_000))
        val reply = brain.ask(heard)

        _state.update { it.copy(reply = reply) }
        setPhase(Phase.Speaking)
        link.send(Protocol.overlay(reply, ttlMs = 6000L + reply.length * 50L))
        say(reply)
    }

    /** Text that arrived already transcribed (e.g. "Hey Google, play … on Glasses Assistant" in the car). */
    private fun askText(text: String) {
        turn?.cancel()
        turn = scope.launch {
            try {
                if (config.argueMode) {
                    addLine(DebateLine("User", "user", text))
                    runDebate(LINES_PER_ROUND)
                } else {
                    respond(text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debateFailed(e)
            } finally {
                link.send(Protocol.skin(config.skinId))
                setPhase(Phase.Idle)
            }
        }
    }

    // ---------------------------------------------------------------- Argue mode

    /** You speak (a topic, or butting in), then the characters argue for a few lines. */
    private fun argueTurn() {
        turn?.cancel()
        turn = scope.launch {
            try {
                setPhase(Phase.Listening)
                link.send(Protocol.overlay("Topic?", ttlMs = 8000))
                val heard = speechIn.listen()?.trim()
                if (!heard.isNullOrEmpty()) addLine(DebateLine("User", "user", heard))
                if (_state.value.debate.isEmpty()) return@launch
                runDebate(LINES_PER_ROUND)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debateFailed(e)
            } finally {
                link.send(Protocol.skin(config.skinId))
                setPhase(Phase.Idle)
            }
        }
    }

    private fun continueDebate() {
        if (_state.value.debate.isEmpty()) return
        turn?.cancel()
        turn = scope.launch {
            try {
                runDebate(LINES_PER_ROUND)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debateFailed(e)
            } finally {
                link.send(Protocol.skin(config.skinId))
                setPhase(Phase.Idle)
            }
        }
    }

    private suspend fun runDebate(lines: Int) {
        repeat(lines) {
            val speaker = nextSpeaker()
            setPhase(Phase.Thinking)
            val transcript = _state.value.debate.map { it.who to it.text }
            val text = brain.debateLine(speaker, transcript)
            addLine(DebateLine(speaker.character, speaker.id, text))

            setPhase(Phase.Speaking)
            link.send(Protocol.skin(speaker.id))
            link.send(Protocol.overlay("${speaker.character}: $text", ttlMs = 6000L + text.length * 50L))
            neuralLoad?.takeIf { it.isActive && config.neuralVoices }?.let { kotlinx.coroutines.withTimeoutOrNull(30_000) { it.join() } }
            speechOut.speak(text, speaker)
        }
    }

    /** Whoever was just called out by name answers; otherwise anyone but the last speaker. */
    private fun nextSpeaker(): Skin {
        val last = _state.value.debate.lastOrNull()
        val candidates = Skins.cast.filter { it.id != last?.skinId }
        val named = candidates.firstOrNull { c ->
            val shortName = c.character.substringAfterLast(' ')
            last?.text?.contains(shortName, ignoreCase = true) == true
        }
        return named ?: candidates.random()
    }

    private fun addLine(line: DebateLine) =
        _state.update { it.copy(debate = (it.debate + line).takeLast(60), error = null) }

    private suspend fun debateFailed(e: Exception) {
        Log.e(TAG, "Debate failed", e)
        _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
        link.send(Protocol.overlay("Error", ttlMs = 3000))
        say("Something went wrong. Check the app for details.")
    }

    // ---------------------------------------------------------------- neural voices

    @Volatile private var neural: NeuralVoice? = null
    private var neuralLoad: Job? = null

    /** The neural voice when it's installed, turned on and loaded; otherwise null (phone voice). */
    private fun neuralVoice(): NeuralVoice? {
        if (!config.neuralVoices) { setVoice("Phone voice (character voices are switched off)"); return null }
        if (neural == null && neuralLoad?.isActive != true) loadNeuralVoice()
        return neural
    }

    private fun setVoice(text: String) = _state.update { it.copy(voice = text) }

    private fun loadNeuralVoice() {
        if (neural != null || neuralLoad?.isActive == true) return
        if (!VoicePack.isReady(this)) { setVoice("Phone voice (character voices not downloaded)"); return }
        setVoice("Loading character voices…")
        neuralLoad = scope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            NeuralVoice.load(this@AssistantService)
                .onSuccess {
                    neural = it
                    setVoice("Character voices ready (loaded in ${(System.currentTimeMillis() - started) / 1000} s)")
                }
                .onFailure {
                    setVoice("Character voices didn't load, so the phone voice is used: " +
                        (it.message ?: it.javaClass.simpleName).take(160))
                }
        }
    }

    private suspend fun say(text: String) {
        // The model takes a few seconds to load; wait for it rather than falling back to the phone voice.
        neuralLoad?.takeIf { it.isActive && config.neuralVoices }?.let { kotlinx.coroutines.withTimeoutOrNull(30_000) { it.join() } }
        val skin = config.skin
        speechOut.speak(text, skin)
    }

    // ---------------------------------------------------------------- glasses display

    private lateinit var screen: GlassesScreen
    private lateinit var nowPlaying: NowPlayingCard
    private lateinit var textCard: TextCard
    @Volatile private var mirror: ScreenMirror? = null
    private var mirrorWanted: Triple<Crop, Float?, Float?>? = null

    private val displayControl = object : DisplayControl {
        override fun showNowPlaying(): String {
            textCard.stop()
            screen.setSource(Source.NowPlaying)
            nowPlaying.start(scope)
            return if (dev.glasses.assistant.display.Media.hasAccess(this@AssistantService)) "Showing what's playing."
            else "Showing the card, but Notification access is off, so it can't see the music. " +
                "Tell the user to turn it on in the Glasses Assistant app."
        }

        override fun showMirror(crop: Crop, zoom: Float?, pan: Float?): String {
            textCard.stop()
            val m = mirror
            if (m != null && m.running) {
                m.crop = crop
                zoom?.let { m.zoom = it }
                pan?.let { m.pan = it }
                screen.setSource(Source.Mirror)
                m.refresh()
                return "Mirroring the phone screen (${crop.name.lowercase()} crop)."
            }
            mirrorWanted = Triple(crop, zoom, pan)
            return askToMirror()
        }

        override fun showText(title: String, text: String): String {
            if (text.isBlank()) return "Nothing to show."
            val back = screen.source.value.takeIf { it != Source.Text } ?: Source.None
            textCard.show(scope, title, text, back)
            return "Showing it on the glasses."
        }

        override fun showOff(): String {
            textCard.stop()
            screen.setSource(Source.None)
            return "Display back to normal."
        }
    }

    private fun cycleDisplay() {
        when (screen.source.value) {
            Source.None -> displayControl.showNowPlaying()
            Source.NowPlaying -> displayControl.showMirror(Crop.Auto, null, null)
            else -> displayControl.showOff()
        }
        link.send(Protocol.overlay(when (screen.source.value) {
            Source.NowPlaying -> "Now playing"; Source.Mirror -> "Screen"; else -> "Display off"
        }, ttlMs = 1200))
    }

    /** After the glasses reconnect, draw whatever was showing again. */
    private fun resendDisplay() {
        when (screen.source.value) {
            Source.NowPlaying -> nowPlaying.start(scope)
            Source.Mirror -> mirror?.refresh()
            else -> {}
        }
    }

    /**
     * Android asks the user before any app can capture the screen, from a visible screen. With Phone
     * control on (or the app open) the prompt appears straight away; otherwise a notification asks.
     */
    private fun askToMirror(): String {
        val intent = Intent(this, MirrorConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val shown = ControlService.instance?.launch(intent) == true ||
            (appVisible && runCatching { startActivity(intent); true }.getOrDefault(false))
        if (shown) {
            return "Asked on the phone: the user needs to tap Start now (choose \"Entire screen\" to mirror any app). " +
                "After that it starts by itself."
        }
        val tap = PendingIntent.getActivity(this, 4, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        getSystemService(NotificationManager::class.java).notify(MIRROR_NOTIFICATION, NotificationCompat.Builder(this, ALERTS)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Show your screen on the glasses?")
            .setContentText("Tap, then Start now.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build())
        return "Sent a notification on the phone: the user taps it, then Start now, and mirroring begins."
    }

    private fun onMirrorAnswer(resultCode: Int, data: Intent?) {
        getSystemService(NotificationManager::class.java).cancel(MIRROR_NOTIFICATION)
        if (resultCode != Activity.RESULT_OK || data == null) {
            mirrorWanted = null
            link.send(Protocol.overlay("Screen sharing declined", ttlMs = 2500))
            return
        }
        // Android 14+: the service must be a screen-capture service before it gets the capture.
        runCatching { startInForeground(withCapture = true) }.onFailure {
            Log.e(TAG, "Couldn't become a screen-capture service", it)
            _state.update { s -> s.copy(error = "Screen sharing: ${it.message}") }
            link.send(Protocol.overlay("Couldn't start screen sharing", ttlMs = 3000))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching { mpm.getMediaProjection(resultCode, data) }
            .onFailure { _state.update { s -> s.copy(error = "Screen sharing: ${it.message}") } }
            .getOrNull() ?: return
        val m = ScreenMirror(this, projection, screen, { ControlService.instance?.foreground }) {
            mirror = null
            _state.update { it.copy(mirrorAllowed = false) }
            if (screen.source.value == Source.Mirror) screen.setSource(Source.None)
            runCatching { startInForeground(withCapture = false) }
        }
        runCatching { m.start() }.onFailure {
            Log.e(TAG, "Mirror failed to start", it)
            link.send(Protocol.overlay("Couldn't start screen sharing", ttlMs = 3000))
            return
        }
        mirror = m
        _state.update { it.copy(mirrorAllowed = true) }
        val (crop, zoom, pan) = mirrorWanted ?: Triple(Crop.Auto, null, null)
        mirrorWanted = null
        displayControl.showMirror(crop, zoom, pan)
    }

    private fun setPhase(phase: Phase) = _state.update { it.copy(phase = phase) }

    private fun startInForeground(withCapture: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Assistant", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERTS, "Requests", NotificationManager.IMPORTANCE_HIGH)
        )
        val talk = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_TALK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, AssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Glasses assistant running")
            .setContentText("Waiting for the glasses on port ${Config.LINK_PORT}")
            .setContentIntent(open)
            .addAction(0, "Talk", talk)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()

        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if (withCapture) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
    }

    override fun onDestroy() {
        mirror?.stop()
        mirror = null
        scope.cancel()
        neural?.release()
        neural = null
        Thread { link.stop() }.start()
        speechOut.shutdown()
        Handler(Looper.getMainLooper()).post { speechIn.release() }
        _state.value = AssistantState()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AssistantService"
        private const val CHANNEL = "assistant"
        private const val NOTIFICATION_ID = 1
        private const val MIRROR_NOTIFICATION = 2
        private const val ALERTS = "requests"
        const val ACTION_DISPLAY = "dev.glasses.assistant.DISPLAY"
        const val ACTION_MIRROR_RESULT = "dev.glasses.assistant.MIRROR_RESULT"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"

        /** Set by the main screen while it's open; then the app may show the capture prompt itself. */
        @Volatile var appVisible = false
        const val ACTION_TALK = "dev.glasses.assistant.TALK"
        const val ACTION_STOP = "dev.glasses.assistant.STOP"
        const val ACTION_SKIN = "dev.glasses.assistant.SKIN"
        const val ACTION_VOICES = "dev.glasses.assistant.VOICES"
        const val ACTION_TEST_VOICE = "dev.glasses.assistant.TEST_VOICE"
        const val ACTION_CONTINUE = "dev.glasses.assistant.CONTINUE"
        const val ACTION_CLEAR = "dev.glasses.assistant.CLEAR"
        const val ACTION_ASK = "dev.glasses.assistant.ASK"
        const val ACTION_HUSH = "dev.glasses.assistant.HUSH"
        const val EXTRA_TEXT = "text"
        private const val LINES_PER_ROUND = 4

        private val _state = MutableStateFlow(AssistantState())
        val state: StateFlow<AssistantState> = _state.asStateFlow()

        fun start(context: Context) =
            context.startForegroundService(Intent(context, AssistantService::class.java))

        fun talk(context: Context) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_TALK))

        /** Tell a running service the skin changed, so the glasses restyle. */
        fun skinChanged(context: Context) {
            if (state.value.running) {
                context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_SKIN))
            }
        }

        fun ask(context: Context, text: String) =
            context.startService(
                Intent(context, AssistantService::class.java).setAction(ACTION_ASK).putExtra(EXTRA_TEXT, text)
            )

        fun hush(context: Context) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_HUSH))

        fun keepArguing(context: Context) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_CONTINUE))

        fun newArgument(context: Context) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_CLEAR))

        /** Tell a running service the voice pack was installed, so it loads the neural voice. */
        fun voicesChanged(context: Context) {
            if (state.value.running) {
                context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_VOICES))
            }
        }

        fun testVoice(context: Context) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_TEST_VOICE))

        /** From the app: "now_playing", "mirror" or "off". */
        fun display(context: Context, mode: String) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_DISPLAY).putExtra(EXTRA_MODE, mode))

        fun mirrorAnswer(context: Context, resultCode: Int, data: Intent?) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_MIRROR_RESULT)
                .putExtra(EXTRA_RESULT, resultCode).putExtra(EXTRA_DATA, data))

        fun stop(context: Context) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_STOP))
    }
}
