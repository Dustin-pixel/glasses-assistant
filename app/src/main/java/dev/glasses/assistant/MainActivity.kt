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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { HomeScreen() }
            }
        }
    }
}

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val config = remember { Config(context) }
    val state by AssistantService.state.collectAsStateWithLifecycle()
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var model by remember { mutableStateOf(config.model) }

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
                Button(onClick = { AssistantService.talk(context) }) { Text("Talk") }
                OutlinedButton(onClick = { AssistantService.stop(context) }) { Text("Stop") }
            } else {
                Button(onClick = { start() }) { Text("Start assistant") }
            }
        }

        if (state.heard.isNotEmpty()) Text("You: ${state.heard}")
        if (state.reply.isNotEmpty()) Text("Assistant: ${state.reply}")
        state.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
    }
}
