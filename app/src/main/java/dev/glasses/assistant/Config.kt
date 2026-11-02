package dev.glasses.assistant

import android.content.Context
import java.util.UUID

/** Persistent settings. Note: SharedPreferences is not encrypted; fine for a personal build. */
class Config(context: Context) {
    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    var skinId: String
        get() = prefs.getString(KEY_SKIN, Skins.default.id) ?: Skins.default.id
        set(value) = prefs.edit().putString(KEY_SKIN, value).apply()

    val skin: Skin get() = Skins.byId(skinId)

    /** Argue mode: the characters talk to each other instead of answering you. */
    var argueMode: Boolean
        get() = prefs.getBoolean(KEY_ARGUE, false)
        set(value) = prefs.edit().putBoolean(KEY_ARGUE, value).apply()

    /** Shared secret the glasses must send in their hello, so random hotspot clients can't connect. */
    val linkToken: String
        get() = prefs.getString(KEY_TOKEN, null) ?: UUID.randomUUID().toString().take(8).also {
            prefs.edit().putString(KEY_TOKEN, it).apply()
        }

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"
        const val LINK_PORT = 8765
        private const val KEY_API = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_TOKEN = "link_token"
        private const val KEY_SKIN = "skin"
        private const val KEY_ARGUE = "argue"
    }
}
