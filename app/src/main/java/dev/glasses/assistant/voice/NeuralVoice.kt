package dev.glasses.assistant.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/**
 * On-device neural speech (Kokoro via sherpa-onnx). One model is loaded for all characters;
 * each character is a different speaker id. Loading takes a few seconds, so do it off the main thread.
 */
class NeuralVoice private constructor(private val tts: OfflineTts) {

    val sampleRate: Int get() = tts.sampleRate()

    /** speed > 1 talks faster. Returns mono samples in [-1, 1]. */
    @Synchronized
    fun generate(text: String, speakerId: Int, speed: Float): FloatArray =
        tts.generate(text, speakerId, speed).samples

    @Synchronized
    fun release() = tts.release()

    companion object {
        private const val TAG = "NeuralVoice"

        /** Loads the model; the failure carries a readable reason. */
        fun load(context: Context): Result<NeuralVoice> {
            if (!VoicePack.isReady(context)) return Result.failure(IllegalStateException("voice pack not installed"))
            val d = VoicePack.dir(context)
            fun p(name: String) = File(d, name).absolutePath
            return try {
                val kokoro = OfflineTtsKokoroModelConfig(
                    model = p("model.onnx"),
                    voices = p("voices.bin"),
                    tokens = p("tokens.txt"),
                    dataDir = p("espeak-ng-data"),
                    // One model serves every character, and the first dictionary wins on duplicate words,
                    // so everyone shares American pronunciations; the British voice keeps its own sound.
                    lexicon = p("lexicon-us-en.txt") + "," + p("lexicon-gb-en.txt"),
                    dictDir = p("dict"),
                )
                val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(kokoro = kokoro, numThreads = threads),
                )
                val tts = OfflineTts(assetManager = null, config = config)
                val speakers = tts.numSpeakers()
                if (speakers < 30) {
                    tts.release()
                    Result.failure(IllegalStateException("the model reports $speakers voices; expected 53"))
                } else {
                    Result.success(NeuralVoice(tts))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Couldn't load the neural voice", t)
                Result.failure(t)
            }
        }
    }
}
