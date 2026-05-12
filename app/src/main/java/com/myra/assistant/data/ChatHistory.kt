package com.myra.assistant.data

import android.content.Context
import com.myra.assistant.ui.main.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists chat messages to a JSON file under the app's internal storage so the
 * conversation survives app restarts. Capped at [MAX_MESSAGES] to keep file
 * size reasonable.
 */
class ChatHistory(context: Context) {

    private val file: File = File(context.filesDir, "chat_history.json")

    @Synchronized
    fun load(): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                ChatMessage(
                    text = obj.optString("text"),
                    isUser = obj.optBoolean("isUser"),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @Synchronized
    fun append(message: ChatMessage) {
        val current = load().toMutableList()
        current.add(message)
        val trimmed =
            if (current.size > MAX_MESSAGES) current.subList(current.size - MAX_MESSAGES, current.size)
            else current
        save(trimmed)
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    private fun save(list: List<ChatMessage>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(
                JSONObject()
                    .put("text", m.text)
                    .put("isUser", m.isUser)
                    .put("timestamp", m.timestamp),
            )
        }
        runCatching { file.writeText(arr.toString()) }
    }

    companion object {
        private const val MAX_MESSAGES = 500
    }
}
