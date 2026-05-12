package com.myra.assistant.ai

import com.myra.assistant.model.AppCommand
import com.myra.assistant.model.CommandType
import java.util.Locale

/**
 * Converts a user transcript (Hinglish + English) into an [AppCommand] that
 * [com.myra.assistant.viewmodel.MainViewModel] can execute. Returns `null` when
 * nothing matches — Gemini will then handle the input as plain conversation.
 *
 * Designed to be deliberately forgiving: tokens are normalized lowercase, and
 * a few common verbs are accepted in both Hindi and English (kholo / open,
 * call karo / call, msg bhejo / send message, etc).
 */
object CommandParser {

    fun parse(raw: String): AppCommand? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val text = t.lowercase(Locale.ENGLISH)

        // 0. "Lock the phone" — checked first because of high-priority safety
        //    semantics. Matched before screen-context because "lock screen"
        //    contains the word "screen".
        lockScreenMatch(text)?.let { return it }

        // 1. "What's on my screen" / "ei screen ta summarise koro" — checked
        //    first because the words "this" / "screen" can otherwise be picked
        //    up by the app-name matcher (e.g. "screen" → "screenshot app").
        screenContextMatch(text, raw)?.let { return it }

        // 2. Prime-contact aliases. Matched before generic "call <name>" so
        //    "close friend ko call karo" isn't read as call(name="close friend").
        primeMatch(text)?.let { return it }

        // 3. System toggles.
        systemToggle(text)?.let { return it }

        // 4. App open/close.
        openClose(text)?.let { return it }

        // 5. WhatsApp / SMS / call (with target).
        comm(text)?.let { return it }

        return null
    }

    // -- Screen context ------------------------------------------------------

    private val SCREEN_TRIGGERS = listOf(
        "this screen", "ei screen", "is screen", "ye screen", "is page",
        "screen me kya", "screen e ki", "screen er content",
        "what is on screen", "what's on screen", "whats on screen",
        "what does it say", "read this", "summarise this", "summarize this",
        "summary of this", "translate this", "translate screen",
        "explain this page", "explain this screen",
    )

    private fun screenContextMatch(text: String, raw: String): AppCommand? {
        if (!containsAny(text, *SCREEN_TRIGGERS.toTypedArray())) return null
        return AppCommand(
            CommandType.SCREEN_CONTEXT_QUERY,
            mapOf("query" to raw),
        )
    }

    // -- Lock screen ---------------------------------------------------------

    private val LOCK_TRIGGERS = listOf(
        "lock the phone", "lock phone", "lock my phone", "lock screen",
        "screen lock kar", "phone lock kar", "phone ko lock", "lock kar do",
        "phone lock koro", "lock screen koro", "screen off", "screen off koro",
    )

    private fun lockScreenMatch(text: String): AppCommand? {
        if (!containsAny(text, *LOCK_TRIGGERS.toTypedArray())) return null
        return AppCommand(CommandType.LOCK_SCREEN)
    }

    // -- Prime ---------------------------------------------------------------

    private val PRIME_NTH = mapOf(
        "first" to 0, "pehla" to 0, "pahla" to 0, "one" to 0,
        "second" to 1, "doosra" to 1, "dusra" to 1, "two" to 1,
        "third" to 2, "teesra" to 2, "tisra" to 2, "three" to 2,
    )

    private val PRIME_ALIASES = listOf(
        "close friend", "best friend", "boyfriend", "girlfriend",
        "jaan", "mere jaan", "meri jaan", "love", "my love", "my babe",
        "babe", "honey", "darling", "sweetheart",
    )

    private fun primeMatch(text: String): AppCommand? {
        val isCall = containsAny(text, "call", "phone", "dial")
        val isMsg = containsAny(text, "message", "msg", "sms", "text", "bhej")
        if (!isCall && !isMsg) return null

        var index = 0
        var hit = false
        for ((word, idx) in PRIME_NTH) {
            if (text.contains("$word prime") ||
                text.contains("$word contact") ||
                text.contains("$word close friend")
            ) {
                index = idx
                hit = true
                break
            }
        }
        if (!hit) {
            for (alias in PRIME_ALIASES) {
                if (text.contains(alias)) {
                    hit = true
                    break
                }
            }
        }
        if (!hit) return null
        return AppCommand(
            if (isCall) CommandType.PRIME_CALL else CommandType.PRIME_MSG,
            mapOf("index" to index.toString()),
        )
    }

    // -- System toggles ------------------------------------------------------

    private fun systemToggle(text: String): AppCommand? {
        if (containsAny(text, "volume up", "volume badhao", "awaaz badhao", "louder")) {
            return AppCommand(CommandType.VOLUME_UP)
        }
        if (containsAny(text, "volume down", "volume kam", "awaaz kam", "quieter")) {
            return AppCommand(CommandType.VOLUME_DOWN)
        }
        if (containsAny(text, "mute", "chup karo", "silent karo")) {
            return AppCommand(CommandType.VOLUME_MUTE)
        }
        if (containsAny(text, "torch on", "flashlight on", "torch jala", "light on", "batti jala")) {
            return AppCommand(CommandType.FLASHLIGHT_ON)
        }
        if (containsAny(text, "torch off", "flashlight off", "torch band", "light off", "batti band")) {
            return AppCommand(CommandType.FLASHLIGHT_OFF)
        }
        if (containsAny(text, "wifi on", "wifi chalu", "wifi enable")) {
            return AppCommand(CommandType.WIFI_ON)
        }
        if (containsAny(text, "wifi off", "wifi band", "wifi disable")) {
            return AppCommand(CommandType.WIFI_OFF)
        }
        if (containsAny(text, "bluetooth on", "bluetooth chalu")) {
            return AppCommand(CommandType.BLUETOOTH_ON)
        }
        if (containsAny(text, "bluetooth off", "bluetooth band")) {
            return AppCommand(CommandType.BLUETOOTH_OFF)
        }
        return null
    }

    // -- Open / close apps ---------------------------------------------------

    private val OPEN_VERBS = listOf("kholo", "khol do", "khol", "open", "launch", "start")
    private val CLOSE_VERBS = listOf("band karo", "band kar", "close", "exit", "quit", "stop")

    private fun openClose(text: String): AppCommand? {
        for (v in CLOSE_VERBS) {
            val app = appNameAround(text, v) ?: continue
            return AppCommand(CommandType.CLOSE_APP, mapOf("app_name" to app))
        }
        for (v in OPEN_VERBS) {
            val app = appNameAround(text, v) ?: continue
            return AppCommand(CommandType.OPEN_APP, mapOf("app_name" to app))
        }
        return null
    }

    private fun appNameAround(text: String, verb: String): String? {
        val idx = text.indexOf(verb)
        if (idx < 0) return null
        val left = text.substring(0, idx).trim()
        val right = text.substring(idx + verb.length).trim()
        val candidate = listOf(right, left).firstOrNull { it.isNotEmpty() }?.let { cleanAppName(it) }
        return candidate?.takeIf { it.isNotEmpty() }
    }

    private fun cleanAppName(raw: String): String {
        val tokens = raw.split(Regex("\\s+"))
            .map { it.trim().trim('.', ',', '!', '?') }
            .filter { it.isNotEmpty() && it !in STOP_WORDS }
        return tokens.joinToString(" ")
    }

    private val STOP_WORDS = setOf(
        "please", "plz", "to", "the", "app", "application",
        "ko", "ka", "ki", "se", "do", "de", "dena", "karo", "kar",
    )

    // -- Calls / SMS / WhatsApp ---------------------------------------------

    private fun comm(text: String): AppCommand? {
        // WhatsApp call: "<name> ko whatsapp call" / "whatsapp call <name>"
        if (text.contains("whatsapp")) {
            val target = extractTarget(text, listOf("whatsapp")) ?: ""
            return when {
                containsAny(text, "call", "phone", "dial") ->
                    AppCommand(CommandType.WHATSAPP_CALL, mapOf("name" to target))
                containsAny(text, "message", "msg", "send", "bhej", "text") ->
                    AppCommand(CommandType.WHATSAPP_MSG, mapOf("name" to target, "message" to extractMessage(text)))
                else -> null
            }
        }
        if (containsAny(text, "sms", "text message", "msg bhej", "message bhej", "send message")) {
            val target = extractTarget(text, listOf("sms", "msg", "message", "text"))
            return AppCommand(
                CommandType.SMS,
                mapOf("name" to (target ?: ""), "message" to extractMessage(text)),
            )
        }
        if (containsAny(text, "call", "phone", "dial", "phone karo", "call karo")) {
            val target = extractTarget(text, listOf("call", "phone", "dial"))
            return AppCommand(CommandType.CALL, mapOf("name" to (target ?: "")))
        }
        return null
    }

    private fun extractTarget(text: String, verbs: List<String>): String? {
        // Pattern: "<target> ko <verb>" (Hinglish) or "<verb> <target>" (English).
        val tokens = text.split(Regex("\\s+"))
        if (tokens.isEmpty()) return null
        val koIdx = tokens.indexOf("ko")
        if (koIdx > 0) {
            return tokens.subList(0, koIdx).joinToString(" ").trim()
        }
        for (verb in verbs) {
            val idx = tokens.indexOf(verb)
            if (idx in 0 until tokens.size - 1) {
                return tokens.subList(idx + 1, tokens.size)
                    .takeWhile { it !in STOP_WORDS }
                    .joinToString(" ")
                    .trim()
                    .ifEmpty { null }
            }
        }
        return null
    }

    private fun extractMessage(text: String): String {
        // Look for "saying ..." / "bolo ..." / "ki ..." segments.
        val markers = listOf("saying", "ki", "bolo", "tell him", "tell her", "tell them", "that")
        for (m in markers) {
            val idx = text.indexOf(" $m ")
            if (idx >= 0) {
                return text.substring(idx + m.length + 2).trim()
            }
        }
        return ""
    }

    private fun containsAny(text: String, vararg needles: String): Boolean {
        for (n in needles) if (text.contains(n)) return true
        return false
    }
}
