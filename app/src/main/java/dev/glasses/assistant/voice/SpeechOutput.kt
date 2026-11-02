package dev.glasses.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Text-to-speech with per-skin voice effects.
 * With an effect, speech is rendered to a WAV, processed by [VoiceFx], and played through an
 * AudioTrack tagged as assistant speech (so it ducks music and routes to the car on Android Auto).
 * Without one, or if the engine's output can't be read, it falls back to speaking directly.
 */
class SpeechOutput(private val context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context, this)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    @Volatile private var ready = false

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) return
        tts.language = Locale.getDefault()
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finish(utteranceId, true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { finish(utteranceId, false) }
            override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId, false) }
        })
    }

    private fun finish(id: String?, ok: Boolean) {
        id?.let { pending.remove(it)?.complete(ok) }
    }

    /** Speaks and suspends until finished (or cancelled). */
    suspend fun speak(text: String, pitch: Float = 1f, rate: Float = 1f, fx: Fx = Fx.NONE) {
        if (!ready || text.isBlank()) return
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
        if (fx == Fx.NONE) return speakDirect(text)

        val pcm = runCatching { synthesize(text) }.getOrElse {
            if (it is CancellationException) throw it
            Log.w(TAG, "Synthesis failed, speaking directly", it)
            null
        }
        if (pcm == null || pcm.samples.isEmpty()) return speakDirect(text)
        val processed = withContext(Dispatchers.Default) { VoiceFx.apply(fx, pcm.samples, pcm.rate) }
        play(processed, pcm.rate)
    }

    private suspend fun speakDirect(text: String) {
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            withTimeoutOrNull(120_000) { done.await() }
        } catch (e: CancellationException) {
            tts.stop()
            throw e
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun synthesize(text: String): Pcm? = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "tts_$id.wav")
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        try {
            if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) return@withContext null
            val ok = withTimeoutOrNull(30_000) { done.await() } ?: false
            if (ok) VoiceFx.readWav(file) else null
        } finally {
            pending.remove(id)
            file.delete()
        }
    }

    private suspend fun play(samples: FloatArray, rate: Int) = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) return@withContext
        val pcm = ShortArray(samples.size) { i ->
            (samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        audio.requestAudioFocus(focus)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            while (isActive && track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                track.playbackHeadPosition < pcm.size
            ) delay(40)
        } finally {
            runCatching { track.stop() }
            track.release()
            audio.abandonAudioFocusRequest(focus)
        }
    }

    fun shutdown() = tts.shutdown()

    private companion object {
        const val TAG = "SpeechOutput"
    }
}
