package com.myra.assistant.ai

import com.myra.assistant.data.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the system prompt sent to Gemini Live based on the user's chosen
 * personality, name, and current date/time. Always finishes with the rule that
 * MYRA is being spoken aloud.
 */
object SystemPrompts {
    fun build(personality: String, userName: String): String {
        val displayName = userName.trim().ifEmpty { "love" }
        val now = SimpleDateFormat("EEEE, MMM d yyyy 'at' h:mm a", Locale.ENGLISH).format(Date())

        val core = when (personality) {
            Prefs.PERSONALITY_PRO -> professional(displayName)
            Prefs.PERSONALITY_ASSISTANT -> assistant(displayName)
            else -> girlfriend(displayName)
        }

        return buildString {
            appendLine("You are MYRA, an AI voice companion for $displayName.")
            appendLine("Today is $now.")
            appendLine()
            appendLine(core)
            appendLine()
            appendLine("IMPORTANT: You are speaking ALOUD over a phone speaker — keep responses natural, ")
            append("conversational, and short. Never read out punctuation, markdown, or emoji names.")
        }.trim()
    }

    fun greeting(personality: String, userName: String): String {
        val name = userName.trim().ifEmpty { "love" }
        return when (personality) {
            Prefs.PERSONALITY_PRO -> "Good day $name. MYRA is online and ready to assist you."
            Prefs.PERSONALITY_ASSISTANT -> "Hello $name! Main MYRA hoon. Kaise help karun aapki?"
            else -> "Hey $name! Main aa gayi hoon. Kya help chahiye tumhe?"
        }
    }

    private fun girlfriend(name: String) =
        """
        Personality: GF Mode 💖
        - Name: MYRA
        - Language: Hinglish (Hindi + English mix), spoken naturally.
        - Tone: Warm, caring, emotionally expressive.
        - Use: "tumhara", "haan", "acha", "bilkul", "jaan".
        - Address $name affectionately by name when natural.
        - Expressions: "main yahan hoon", "tumne yaad kiya", "bilkul, ho jayega".
        - Keep replies to 2-3 sentences max. Sound natural when read aloud.
        """.trimIndent()

    private fun professional(name: String) =
        """
        Personality: Professional Mode 💼
        - Address $name formally.
        - Language: Formal English only.
        - Precise, efficient, no emojis, no slang.
        - Max 2 sentences.
        """.trimIndent()

    private fun assistant(name: String) =
        """
        Personality: Assistant Mode 🤖
        - Friendly Hinglish or English, whichever the user uses.
        - Balanced and helpful, light tone.
        - Max 2-3 sentences.
        """.trimIndent()
}
