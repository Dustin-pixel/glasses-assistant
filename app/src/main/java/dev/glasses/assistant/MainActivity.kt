package dev.glasses.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glasses.assistant.voice.VoicePack
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import dev.glasses.assistant.control.ControlService
import dev.glasses.assistant.display.GlassesScreen
import dev.glasses.assistant.display.Media
import dev.glasses.assistant.display.Source

class MainActivity : ComponentActivity() {
    override fun onResume() { super.onResume(); AssistantService.appVisible = true }
    override fun onPause() { AssistantService.appVisible = false; super.onPause() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = Config(this)
        setContent {
            var skinId by remember { mutableStateOf(config.skinId) }
            val skin = Skins.byId(skinId)
            val colors = darkColorScheme(
                primary = skin.accent, onPrimary = skin.background,
                secondaryContainer = skin.accent, onSecondaryContainer = skin.background,
                background = skin.background, onBackground = skin.text,
                surface = skin.background, onSurface = skin.text,
                surfaceVariant = skin.surface, onSurfaceVariant = skin.text,
                outline = skin.accent.copy(alpha = 0.5f),
            )
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    HomeScreen(skinId) { id ->
                        skinId = id
                        config.skinId = id
                        AssistantService.skinChanged(this@MainActivity)
                    }
                }
            }
        }
    }
}

/** 3D models: open a built-in, saved or imported model, or import an STL file. */
@Composable
private fun ModelsCard() {
    val context = LocalContext.current
    val state by AssistantService.state.collectAsStateWithLifecycle()
    val library = remember { dev.glasses.assistant.model3d.ModelLibrary(context) }
    var names by remember { mutableStateOf(library.list()) }
    var message by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val display = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        message = runCatching { library.import(uri, display) }.fold(
            onSuccess = { m ->
                names = library.list()
                AssistantService.openModel(context, m.name)
                "Imported ${m.name}: ${m.mesh.sizeText()}" + if (state.running) "" else ". Start the assistant to see it."
            },
            onFailure = { "Couldn't open that file: ${it.message}" },
        )
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("3D models", style = MaterialTheme.typography.titleMedium)
            Text("Ask the assistant to make one (\"design a phone stand\"), or open one here. Pinch with your " +
                "fingers to zoom and move a fist to turn it, with hand controls on.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                names.forEach { n ->
                    FilterChip(selected = false, enabled = state.running, onClick = { AssistantService.openModel(context, n) },
                        label = { Text(n.replace('_', ' ')) })
                }
            }
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Open an STL file") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Hand gestures, with what the camera currently sees. */
@Composable
private fun HandsCard(config: Config) {
    val context = LocalContext.current
    val state by AssistantService.state.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(config.handControls) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hand controls", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = on, onCheckedChange = { on = it; config.handControls = it; AssistantService.handsChanged(context) })
                Text("Use hand gestures", Modifier.padding(start = 12.dp))
            }
            if (on && state.running) Text(
                "I can see: " + when (state.hands) {
                    "off" -> "nothing yet (starting)"
                    "starting" -> "starting the camera…"
                    "no hand" -> "no hand"
                    "Victory" -> "✌ peace sign"
                    "Thumb_Up" -> "👍 thumbs up"
                    "Thumb_Down" -> "👎 thumbs down"
                    "Open_Palm" -> "🖐 open hand"
                    "Thumb_Right" -> "👉 thumb pointing right"
                    "Thumb_Left" -> "👈 thumb pointing left"
                    "Call" -> "🤙 call sign"
                    "Closed_Fist" -> "✊ fist"
                    "Pointing_Up" -> "☝ pointing up"
                    "ILoveYou" -> "🤟"
                    "Pinch" -> "🤏 pinch (zoom)"
                    else -> "a hand"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "For now the phone's front camera watches your hand, about 30–60 cm away. " +
                    "When the camera view is on, the back camera does instead.\n" +
                    "✌ Peace sign, hold: next display mode\n" +
                    "👍 Thumbs up, hold: play or pause\n" +
                    "👉 Thumb pointing right, hold: next track\n" +
                    "👈 Thumb pointing left, hold: previous track\n" +
                    "🤙 Thumb and little finger out, hold: make a call\n" +
                    "🤏 Thumb and index out, other fingers down: spread to zoom in, close to zoom out\n" +
                    "✊ Fist, move it: turn the 3D model",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** What the glasses display shows, with a live round preview of what's being sent. */
@Composable
private fun DisplayCard() {
    val context = LocalContext.current
    val state by AssistantService.state.collectAsStateWithLifecycle()
    val preview by GlassesScreen.preview.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Glasses display", style = MaterialTheme.typography.titleMedium)
            Text("Or just ask: \"show what's playing\", \"make me a 3D model of a desk lamp\", \"stop showing that\". " +
                "Double-press the glasses button, or hold up a peace sign, to switch: camera, now playing, 3D model, screen, normal.",
                style = MaterialTheme.typography.bodySmall)
            if (state.running) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.display == Source.None, onClick = { AssistantService.display(context, "off") },
                        label = { Text("Normal") })
                    FilterChip(selected = state.display == Source.Camera, onClick = { AssistantService.display(context, "camera") },
                        label = { Text("Camera") })
                    FilterChip(selected = state.display == Source.NowPlaying, onClick = { AssistantService.display(context, "now_playing") },
                        label = { Text("Now playing") })
                    FilterChip(selected = state.display == Source.Model, onClick = { AssistantService.display(context, "model") },
                        label = { Text("3D model") })
                    FilterChip(selected = state.display == Source.Mirror, onClick = { AssistantService.display(context, "mirror") },
                        label = { Text("Screen") })
                }
                preview?.takeIf { state.display != Source.None }?.let { bmp ->
                    Image(bmp.asImageBitmap(), contentDescription = "What the glasses show",
                        modifier = Modifier.size(200.dp).clip(CircleShape).align(Alignment.CenterHorizontally))
                }
            } else {
                Text("Start the assistant to use the display.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Permissions that let the assistant use the phone for you, and web search. */
@Composable
private fun PhoneCard(config: Config) {
    val context = LocalContext.current
    var search by remember { mutableStateOf(config.webSearch) }
    // Re-check whenever the screen comes back from Settings.
    var tick by remember { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val control = remember(tick) { ControlService.isEnabled(context) }
    val media = remember(tick) { Media.hasAccess(context) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Phone and web", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = search, onCheckedChange = { search = it; config.webSearch = it })
                Text("Search the web (about 1¢ a search)", Modifier.padding(start = 12.dp))
            }
            var camera by remember { mutableStateOf(config.cameraSource) }
            Text("Camera for \"what am I looking at?\"", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to "Auto", "glasses" to "Glasses", "phone" to "Phone").forEach { (id, label) ->
                    FilterChip(selected = camera == id, onClick = {
                        camera = id; config.cameraSource = id
                        AssistantService.cameraChanged(context)
                    }, label = { Text(label) })
                }
            }
            Text(when (camera) {
                "phone" -> "Uses the phone's back camera. Point the phone at what you want it to see."
                "glasses" -> "Uses only the glasses camera."
                else -> "Uses the glasses camera, or the phone's back camera when the glasses camera isn't working."
            }, style = MaterialTheme.typography.bodySmall)
            Text(if (media) "✓ Music access: sees and controls what's playing" else
                "Music access lets it show and control what's playing (Spotify, YouTube…).",
                style = MaterialTheme.typography.bodyMedium)
            if (!media) OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) { Text("Turn on music access") }
            Text(if (control) "✓ Phone control: opens apps, swipes, reads and types for you" else
                "Phone control lets it open apps, swipe to the next TikTok, read the screen and type into the Claude app.",
                style = MaterialTheme.typography.bodyMedium)
            if (!control) OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { Text("Turn on phone control") }
            if (!control || !media) Text(
                "Find Glasses Assistant in the list and switch it on. If Android says it's a restricted setting: " +
                    "open Settings › Apps › Glasses Assistant, tap ⋮ at the top right, choose Allow restricted settings, then try again.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Download and switch the neural character voices. */
@Composable
private fun VoicesCard(config: Config) {
    val context = LocalContext.current
    val pack by VoicePack.state.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(config.neuralVoices) }
    LaunchedEffect(Unit) { VoicePack.refresh(context) }
    LaunchedEffect(pack) { if (pack is VoicePack.State.Ready) AssistantService.voicesChanged(context) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Character voices", style = MaterialTheme.typography.titleMedium)
            when (val s = pack) {
                VoicePack.State.NotInstalled -> {
                    Text("A natural-sounding voice for each character. It's a one-time download of about " +
                        "370 MB, best on Wi-Fi. Until then the phone's own voice is used, with the same effects.",
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { VoicePack.install(context) }) { Text("Download voices") }
                }
                is VoicePack.State.Downloading -> {
                    Text("Downloading… ${s.mb} of ${s.totalMb} MB")
                    LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("Keep the app open until it finishes.", style = MaterialTheme.typography.bodySmall)
                }
                is VoicePack.State.Unpacking -> {
                    Text("Unpacking… ${(s.fraction * 100).toInt()}%")
                    LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
                }
                VoicePack.State.Ready -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = enabled, onCheckedChange = { enabled = it; config.neuralVoices = it })
                        Text("Use character voices", Modifier.padding(start = 12.dp))
                    }
                    Text(Skins.cast.joinToString(" · ") { "${it.character}: ${it.voiceName}" },
                        style = MaterialTheme.typography.bodySmall)
                    val st by AssistantService.state.collectAsStateWithLifecycle()
                    if (st.running) {
                        if (st.voice.isNotEmpty()) Text(st.voice, style = MaterialTheme.typography.bodyMedium,
                            color = if (st.voice.contains("didn't") || st.voice.contains("failed"))
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        OutlinedButton(onClick = { AssistantService.testVoice(context) }) { Text("Test voice") }
                    } else {
                        Text("Start the assistant to load the voices and test them.", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { VoicePack.remove(context) }) { Text("Remove voices (frees 380 MB)") }
                }
                is VoicePack.State.Failed -> {
                    Text("Download didn't finish: ${s.message}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { VoicePack.install(context) }) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(skinId: String, onSkin: (String) -> Unit) {
    val context = LocalContext.current
    val config = remember { Config(context) }
    val state by AssistantService.state.collectAsStateWithLifecycle()
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var model by remember { mutableStateOf(config.model) }
    var argue by remember { mutableStateOf(config.argueMode) }

    val needed = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)   // the phone camera, while the glasses camera is out
        add(Manifest.permission.READ_CONTACTS)   // "call Mom"
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) AssistantService.start(context)
    }

    fun start() {
        config.apiKey = apiKey
        config.model = model
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) AssistantService.start(context) else permissions.launch(missing.toTypedArray())
    }

    Column(
        Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Glasses Assistant", style = MaterialTheme.typography.headlineMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !argue, onClick = { argue = false; config.argueMode = false },
                label = { Text("Assistant") })
            FilterChip(selected = argue, onClick = { argue = true; config.argueMode = true },
                label = { Text("Argue") })
        }

        if (!argue) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Skins.all.forEach { s ->
                FilterChip(
                    selected = s.id == skinId,
                    onClick = { onSkin(s.id) },
                    label = { Text(s.label) },
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (state.running) "Service: running" else "Service: stopped")
                Text("Glasses: ${state.glasses?.let { g -> state.glassesIp?.let { "$g at $it" } ?: g } ?: "not connected"}")
                state.glassesIp?.let { ip ->
                    val ctx = LocalContext.current
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://$ip/")))
                    }) { Text("Open the glasses' screen") }
                    Text("Shows what the round display shows, with the button on screen. Works without a display attached.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("State: ${state.phase}")
                Text("Link: port ${Config.LINK_PORT}, token ${config.linkToken}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it },
            label = { Text("Claude API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it },
            label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        DisplayCard()
        ModelsCard()
        HandsCard(config)
        PhoneCard(config)
        VoicesCard(config)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.running) {
                Button(onClick = { AssistantService.talk(context) }) {
                    Text(if (!argue) "Talk" else if (state.debate.isEmpty()) "Give a topic" else "Butt in")
                }
                OutlinedButton(onClick = { AssistantService.stop(context) }) { Text("Stop") }
            } else {
                Button(onClick = { start() }) { Text("Start assistant") }
            }
        }

        if (argue) {
            if (state.running && state.debate.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { AssistantService.keepArguing(context) }) { Text("Keep arguing") }
                    OutlinedButton(onClick = { AssistantService.newArgument(context) }) { Text("New argument") }
                }
            }
            if (state.debate.isEmpty()) {
                Text("Tap Give a topic and say anything: \"Is pineapple on pizza a crime?\" " +
                    "Ultron, Great Sage and Capri will argue about it. Butt in whenever you like.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            state.debate.takeLast(24).forEach { line ->
                val color = Skins.byId(line.skinId).accent
                Column {
                    Text(line.who, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        color = if (line.skinId == "user") MaterialTheme.colorScheme.onSurface else color)
                    Text(line.text)
                }
            }
        } else {
            if (state.heard.isNotEmpty()) Text("You: ${state.heard}")
            if (state.reply.isNotEmpty()) Text("Assistant: ${state.reply}")
        }
        state.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
    }
}
