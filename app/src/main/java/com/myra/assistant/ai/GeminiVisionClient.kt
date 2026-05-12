package com.myra.assistant.ai

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Calls Gemini's REST `generateContent` endpoint with an image + a prompt. Used
 * for the vision feature (camera shot / screenshot OCR) — separate from the
 * realtime audio WebSocket because the Live API does not yet accept inline
 * image bytes in a stable way on all models.
 */
class GeminiVisionClient(
    private val apiKey: String,
    private val model: String = DEFAULT_VISION_MODEL,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Returns the model's text reply, or `null` on any failure. Blocking — call
     * from a background thread.
     */
    fun describe(bitmap: Bitmap, prompt: String): String? {
        if (apiKey.isBlank()) return null
        val resized = resizeIfNeeded(bitmap, MAX_DIMENSION)
        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(JSONObject().put("text", prompt))
                            .put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject()
                                        .put("mime_type", "image/jpeg")
                                        .put("data", base64),
                                ),
                            ),
                    ),
                ),
            )
            put(
                "generation_config",
                JSONObject()
                    .put("temperature", 0.7)
                    .put("max_output_tokens", 256),
            )
        }
        val url =
            "https://generativelanguage.googleapis.com/v1beta/$model:generateContent?key=$apiKey"
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string().orEmpty())
                val candidates = json.optJSONArray("candidates") ?: return null
                if (candidates.length() == 0) return null
                val parts = candidates.getJSONObject(0)
                    .optJSONObject("content")
                    ?.optJSONArray("parts") ?: return null
                buildString {
                    for (i in 0 until parts.length()) {
                        append(parts.getJSONObject(i).optString("text"))
                    }
                }.trim().ifEmpty { null }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resizeIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val scale = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    companion object {
        const val DEFAULT_VISION_MODEL = "models/gemini-2.0-flash"
        private const val MAX_DIMENSION = 1280
    }
}
