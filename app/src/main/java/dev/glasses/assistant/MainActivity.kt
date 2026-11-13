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

/** What the glasses display shows, with a live round preview of what's being sent. */
@Composable
private fun DisplayCard() {
    val context = LocalContext.current
    val state by AssistantService.state.collectAsStateWithLifecycle()
    val preview by GlassesScreen.preview.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Glasses display", style = MaterialTheme.typography.titleMedium)
            Text("Or just ask: \"show what's playing\", \"put my screen on the glasses\", \"stop showing that\". " +
                "Double-press the glasses button to switch.", style = MaterialTheme.typography.bodySmall)
            if (state.running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.display == Source.None, onClick = { AssistantService.display(context, "off") },
                        label = { Text("Normal") })
                    FilterChip(selected = state.display == Source.NowPlaying, onClick = { AssistantService.display(context, "now_playing") },
                        label = { Text("Now playing") })
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
