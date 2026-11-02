package dev.glasses.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * One-shot speech-to-text using Android's built-in recognizer (prefers on-device).
 * Swap this for Whisper later behind the same listen() signature.
 */
class SpeechInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    /** Listens for one utterance. Returns the text, or null on silence/error. */
    suspend fun listen(): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext null
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }

        suspendCancellableCoroutine { cont ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (cont.isActive) cont.resume(text)
                }

                override fun onError(error: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            r.startListening(intent)

            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { r.cancel() } }
        }
    }

    /** Call on the main thread. */
    fun release() {
        recognizer?.destroy()
        recognizer = null
    }
}
