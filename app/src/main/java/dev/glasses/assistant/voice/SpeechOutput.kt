package dev.glasses.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Text-to-speech using the phone's engine. Audio goes to whatever is connected (e.g. Bluetooth earbuds). */
class SpeechOutput(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context, this)
    @Volatile private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.getDefault()
    }

    /** Speaks and suspends until finished (or cancelled). */
    suspend fun speak(text: String) {
        if (!ready || text.isBlank()) return
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { tts.stop() }
        }
    }

    fun shutdown() = tts.shutdown()
}
