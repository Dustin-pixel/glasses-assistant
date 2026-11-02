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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
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
                Text("Glasses: ${state.glasses ?: "not connected"}")
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
