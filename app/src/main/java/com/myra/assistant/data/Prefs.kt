package com.myra.assistant.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin wrapper over [SharedPreferences] for MYRA settings. All keys live in one
 * place so the Settings screen and the rest of the app cannot drift.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString(KEY_API, "") ?: ""
        set(v) = sp.edit().putString(KEY_API, v).apply()

    var userName: String
        get() = sp.getString(KEY_USER_NAME, "") ?: ""
        set(v) = sp.edit().putString(KEY_USER_NAME, v).apply()

    var personality: String
        get() = sp.getString(KEY_PERSONALITY, PERSONALITY_GF) ?: PERSONALITY_GF
        set(v) = sp.edit().putString(KEY_PERSONALITY, v).apply()

    var model: String
        get() = sp.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString(KEY_MODEL, v).apply()

    var voice: String
        get() = sp.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(v) = sp.edit().putString(KEY_VOICE, v).apply()

    fun getPrimeContacts(): List<PrimeContact> {
        val json = sp.getString(KEY_PRIME_JSON, null)
        if (json.isNullOrBlank()) {
            // Legacy single-contact migration.
            val legacyName = sp.getString(LEGACY_NAME, null) ?: return emptyList()
            val legacyNumber = sp.getString(LEGACY_NUMBER, null) ?: return emptyList()
            return listOf(PrimeContact(legacyName, legacyNumber))
        }
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                PrimeContact(
                    name = obj.optString("name"),
                    number = obj.optString("number"),
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun setPrimeContacts(list: List<PrimeContact>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().put("name", c.name).put("number", c.number))
        }
        sp.edit().putString(KEY_PRIME_JSON, arr.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "myra_prefs"
        const val KEY_API = "api_key"
        const val KEY_USER_NAME = "user_name"
        const val KEY_PERSONALITY = "personality_mode"
        const val KEY_MODEL = "gemini_model"
        const val KEY_VOICE = "gemini_voice"
        const val KEY_PRIME_JSON = "prime_contacts_json"

        const val LEGACY_NAME = "prime_name"
        const val LEGACY_NUMBER = "prime_number"

        const val PERSONALITY_GF = "GF"
        const val PERSONALITY_PRO = "PRO"
        const val PERSONALITY_ASSISTANT = "ASSISTANT"

        const val DEFAULT_MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        const val DEFAULT_VOICE = "Aoede"

        val MODELS = listOf(
            "models/gemini-2.5-flash-native-audio-preview-12-2025" to "Native Audio (Human Voice)",
            "models/gemini-2.0-flash-live-001" to "Flash Live (Fast)",
            "models/gemini-2.5-flash-preview-native-audio-dialog" to "Pro Audio Dialog",
        )

        val VOICES = listOf(
            "Aoede" to "Aoede (Female)",
            "Charon" to "Charon (Male)",
            "Kore" to "Kore (Female)",
            "Fenrir" to "Fenrir (Male)",
            "Puck" to "Puck (Male)",
            "Leda" to "Leda (Female)",
            "Orus" to "Orus (Male)",
            "Zephyr" to "Zephyr (Female)",
        )
    }
}

data class PrimeContact(val name: String, val number: String)
