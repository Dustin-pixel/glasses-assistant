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
import dev.glasses.assistant.Skin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Speaks in a character's voice.
 * Neural path (when the voice pack is installed and turned on): Kokoro generates each sentence,
 * the pitch shift and effect are applied, and the next sentence is generated while this one plays.
 * Fallback: the phone's own text-to-speech, rendered to a file so the same effects apply.
 * Audio is tagged as assistant speech so it ducks music and routes to the car on Android Auto.
 */
class SpeechOutput(
    private val context: Context,
    private val neural: () -> NeuralVoice?,
    private val report: (ok: Boolean, problem: String?) -> Unit = { _, _ -> },
) : TextToSpeech.OnInitListener {
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

    /** Speaks [text] as [skin] and suspends until finished (or cancelled). */
    suspend fun speak(text: String, skin: Skin) {
        if (text.isBlank()) return
        val voice = neural()
        if (voice != null) {
            try {
                speakNeural(voice, text, skin)
                report(true, null)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Neural voice failed, using the phone's voice", t)
                report(false, (t.message ?: t.javaClass.simpleName).take(160))
            }
        }
        speakSystem(text, skin)
    }

    // ------------------------------------------------------------------ neural

    private suspend fun speakNeural(voice: NeuralVoice, text: String, skin: Skin) = coroutineScope {
        val parts = sentences(text)
        // Generate faster by the pitch factor, then stretch back: same length, lower (or higher) voice.
        val speed = skin.speed * skin.shift
        fun render(s: String): Deferred<FloatArray> = async(Dispatchers.Default) {
            val raw = voice.generate(s, skin.speakerId, speed)
            VoiceFx.apply(skin.fx, VoiceFx.stretch(raw, skin.shift), voice.sampleRate)
        }
        var next = render(parts[0])
        for (i in parts.indices) {
            val current = next.await()
            if (i + 1 < parts.size) next = render(parts[i + 1])   // overlap: next sentence renders while this one plays
            play(current, voice.sampleRate)
        }
    }

    /** Splits into sentences, merging short ones so the voice keeps its flow. */
    private fun sentences(text: String): List<String> {
        val raw = text.trim().split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        for (s in raw) {
            if (out.isNotEmpty() && out.last().length < 40) out[out.lastIndex] = out.last() + " " + s
            else out += s
        }
        return out.ifEmpty { listOf(text) }
    }

    // ------------------------------------------------------------------ phone voice fallback

    private suspend fun speakSystem(text: String, skin: Skin) {
        if (!ready) return
        tts.setPitch(skin.pitch)
        tts.setSpeechRate(skin.rate)
        if (skin.fx == Fx.NONE) return speakDirect(text)
        val pcm = runCatching { synthesize(text) }.getOrElse {
            if (it is CancellationException) throw it
            Log.w(TAG, "Synthesis failed, speaking directly", it)
            null
        }
        if (pcm == null || pcm.samples.isEmpty()) return speakDirect(text)
        val processed = withContext(Dispatchers.Default) { VoiceFx.apply(skin.fx, pcm.samples, pcm.rate) }
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

    // ------------------------------------------------------------------ playback

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
