package dev.glasses.assistant.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.glasses.assistant.AssistantService
import dev.glasses.assistant.AssistantState
import dev.glasses.assistant.Config
import dev.glasses.assistant.Phase
import dev.glasses.assistant.Skins
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Android Auto entry point. Sideloaded builds need "Unknown sources" enabled in Android Auto's developer settings. */
class GlassesCarAppService : CarAppService() {
    // Personal build: accept any host. A Play Store build should use a real allow-list.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = CarHomeScreen(carContext)
    }
}

/**
 * Voice-first car screen: a few big rows, no reading required while driving.
 * Row titles stay fixed so state updates count as refreshes, not new screens.
 */
class CarHomeScreen(carContext: CarContext) : Screen(carContext) {
    private val config = Config(carContext)

    init {
        // Redraw when the assistant's phase, mode-relevant state or last line changes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AssistantService.state
                    .map { Triple(it.running, it.phase, it.debate.size to it.reply) }
                    .distinctUntilChanged()
                    .collect { invalidate() }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val s = AssistantService.state.value
        val argue = config.argueMode
        val skin = config.skin

        val items = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(talkTitle(s, argue))
                    .addText(statusLine(s, argue))
                    .setOnClickListener { talk(s) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Mode")
                    .addText(if (argue) "Argue: Ultron, Great Sage and Capri" else "Assistant")
                    .setOnClickListener {
                        config.argueMode = !config.argueMode
                        invalidate()
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Character")
                    .addText(if (argue) "All three (argue mode)" else "${skin.label} · tap to change")
                    .setOnClickListener {
                        if (!argue) {
                            val all = Skins.all
                            val next = all[(all.indexOf(skin) + 1) % all.size]
                            config.skinId = next.id
                            AssistantService.skinChanged(carContext)
                            invalidate()
                        }
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Keep arguing")
                    .addText(if (argue && s.debate.isNotEmpty()) "Another round without you" else "Start an argument first")
                    .setOnClickListener {
                        if (argue && s.debate.isNotEmpty()) AssistantService.keepArguing(carContext)
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("New argument")
                    .addText("Clear the current argument")
                    .setOnClickListener { AssistantService.newArgument(carContext) }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setTitle("Glasses Assistant")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items)
            .build()
    }

    private fun talkTitle(s: AssistantState, argue: Boolean) = when {
        !argue -> "Talk"
        s.debate.isEmpty() -> "Give a topic"
        else -> "Butt in"
    }

    private fun statusLine(s: AssistantState, argue: Boolean): String {
        if (!s.running) return "Open the app on your phone and tap Start"
        return when (s.phase) {
            Phase.Listening -> "Listening…"
            Phase.Thinking -> "Thinking…"
            Phase.Speaking -> if (argue) s.debate.lastOrNull()?.who?.let { "$it is talking" } ?: "Talking" else "Talking"
            Phase.Idle -> if (argue) "Tap and say something" else "Tap and ask anything"
        }
    }

    private fun talk(s: AssistantState) {
        if (!s.running) {
            CarToast.makeText(carContext, "Start the assistant on your phone first", CarToast.LENGTH_LONG).show()
            return
        }
        AssistantService.talk(carContext)
    }
}
