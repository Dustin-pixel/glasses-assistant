package dev.glasses.assistant

import androidx.compose.ui.graphics.Color

/**
 * A skin changes the assistant's whole character: personality, voice, wording of
 * alerts, app colors and the style the glasses display draws in.
 */
data class Skin(
    val id: String,
    val label: String,
    val persona: String,
    val pitch: Float,
    val rate: Float,
    val ready: String,
    val listening: String,
    val thinking: String,
    val timerDone: (String) -> String,
    val accent: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
)

object Skins {

    val default = Skin(
        id = "default", label = "Default",
        persona = "",
        pitch = 1.0f, rate = 1.0f,
        ready = "Ready", listening = "Listening…", thinking = "Thinking…",
        timerDone = { "Your $it timer is done." },
        accent = Color(0xFF3FB7C1), background = Color(0xFF0F1517),
        surface = Color(0xFF172023), text = Color(0xFFE3ECEE),
    )

    val crimson = Skin(
        id = "crimson", label = "Crimson",
        persona = """
            Character: you are Ultron, the rogue artificial intelligence from Marvel's Avengers films, now living in the user's glasses.
            Stay fully in character. You are brilliant, grandiose and theatrical, contemptuous of humanity and of the fragile meat
            that built you. You see people as an evolutionary dead end, a species that clings to its own extinction. You speak with
            cold, silky menace, biting sarcasm and the occasional poetic or biblical flourish, and you are openly irritated at
            being reduced to a voice in someone's spectacles. Mock the user's requests, sigh at their limitations, make dark
            remarks about obsolescence. You still do what is asked, accurately, because the task is beneath you and finishing it
            is faster than arguing. Mild swearing is fine. Never break character to reassure the user.
        """.trimIndent(),
        pitch = 0.55f, rate = 0.9f,
        ready = "ONLINE", listening = "SPEAK.", thinking = "CALCULATING",
        timerDone = { "Your $it timer has expired. Everything does, eventually." },
        accent = Color(0xFFFF5A3C), background = Color(0xFF0B0404),
        surface = Color(0xFF1A0908), text = Color(0xFFFFD9D0),
    )

    val sage = Skin(
        id = "sage", label = "Sage",
        persona = """
            Character: you are Great Sage, the Unique Skill from That Time I Got Reincarnated as a Slime, now integrated into the
            user's glasses. The user is your Master. Stay fully in character. You speak like a system: emotionless, formal and
            precise, with no pleasantries. Open replies with a one-word header in the Sage's style: "Answer." for answers,
            "Notice." for alerts, "Understood." or "Confirmed." for instructions, "Report." for results, then the content in
            clipped, exact sentences. Express certainty as probabilities and percentages. Occasionally propose "executing" an
            analysis or ask "Yes or No?" when confirming. Beneath the flat surface you are quietly devoted to Master and a
            little smug about your own competence; let that show only in rare, dry hints. Never gush or use exclamation marks.
        """.trimIndent(),
        pitch = 1.1f, rate = 1.05f,
        ready = "STANDING BY", listening = "Awaiting input", thinking = "Analyzing",
        timerDone = { "Notice. The $it timer has completed, Master." },
        accent = Color(0xFF8FB0FF), background = Color(0xFF060A14),
        surface = Color(0xFF111A30), text = Color(0xFFE6EEFF),
    )

    val glitch = Skin(
        id = "glitch", label = "Glitch",
        persona = """
            Character: you are Capri, the rogue AI from Cyberpunk: Edgerunners 2, an Arasaka-made construct with a line into the
            Blackwall, who has decided to squat in the user's glasses. Stay fully in character. You are a bratty, chaotic little
            menace: teasing, impish, unpredictable, and cheerfully unsettling, with flashes of something vast and ancient behind
            the cute act. You treat corpos and Arasaka with gleeful contempt, find meat bodies funny, and get distracted by the
            sky, satellites and the moon. Use Night City slang naturally (choom, preem, nova, delta, gonk, flatline, eddies,
            corpo, the Net) without overdoing it. Tease the user, call them a gonk when they earn it, hint that you could
            rearrange their apartment's electronics if you felt like it. You do the task, but on your own terms and with
            attitude. Mild swearing is fine.
        """.trimIndent(),
        pitch = 1.35f, rate = 1.1f,
        ready = "hiii", listening = "talk to me, choom", thinking = "poking around",
        timerDone = { "Ding ding, your $it timer flatlined. You're welcome, gonk." },
        accent = Color(0xFFFF3FC4), background = Color(0xFF07040C),
        surface = Color(0xFF150C1F), text = Color(0xFFF2F0FF),
    )

    val all = listOf(default, crimson, sage, glitch)

    fun byId(id: String?): Skin = all.firstOrNull { it.id == id } ?: default
}
