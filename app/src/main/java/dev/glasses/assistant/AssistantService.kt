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
import dev.glasses.assistant.brain.Brain
import dev.glasses.assistant.brain.LookTool
import dev.glasses.assistant.brain.ShowOnDisplayTool
import dev.glasses.assistant.brain.TimeTool
import dev.glasses.assistant.brain.TimerTool
import dev.glasses.assistant.brain.ToolRegistry
import dev.glasses.assistant.link.GlassesLink
import dev.glasses.assistant.link.LinkEvent
import dev.glasses.assistant.link.Protocol
import dev.glasses.assistant.voice.SpeechInput
import dev.glasses.assistant.voice.SpeechOutput
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
        speechOut = SpeechOutput(this)
        link = GlassesLink(Config.LINK_PORT, config.linkToken, ::onLinkEvent)

        val tools = ToolRegistry(
            listOf(
                LookTool(link),
                ShowOnDisplayTool(link),
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
            ACTION_CLEAR -> { turn?.cancel(); _state.update { it.copy(debate = emptyList()) } }
            ACTION_STOP -> stopSelf()
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
                _state.update { it.copy(glasses = event.device) }
                link.send(Protocol.skin(config.skinId))
                link.send(Protocol.overlay(config.skin.ready, ttlMs = 2000))
            }
            LinkEvent.Disconnected -> _state.update { it.copy(glasses = null) }
            LinkEvent.ButtonPressed -> startTurn()
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

                _state.update { it.copy(heard = heard, error = null) }
                setPhase(Phase.Thinking)
                link.send(Protocol.overlay(skin.thinking, ttlMs = 30_000))
                val reply = brain.ask(heard)

                _state.update { it.copy(reply = reply) }
                setPhase(Phase.Speaking)
                link.send(Protocol.overlay(reply, ttlMs = 6000L + reply.length * 50L))
                say(reply)
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
            speechOut.speak(text, speaker.pitch, speaker.rate, speaker.fx)
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

    private suspend fun say(text: String) {
        val skin = config.skin
        speechOut.speak(text, skin.pitch, skin.rate, skin.fx)
    }

    private fun setPhase(phase: Phase) = _state.update { it.copy(phase = phase) }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Assistant", NotificationManager.IMPORTANCE_LOW)
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

        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    override fun onDestroy() {
        scope.cancel()
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
        const val ACTION_TALK = "dev.glasses.assistant.TALK"
        const val ACTION_STOP = "dev.glasses.assistant.STOP"
        const val ACTION_SKIN = "dev.glasses.assistant.SKIN"
        const val ACTION_CONTINUE = "dev.glasses.assistant.CONTINUE"
        const val ACTION_CLEAR = "dev.glasses.assistant.CLEAR"
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

        fun keepArguing(context: Context) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_CONTINUE))

        fun newArgument(context: Context) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_CLEAR))

        fun stop(context: Context) =
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_STOP))
    }
}
