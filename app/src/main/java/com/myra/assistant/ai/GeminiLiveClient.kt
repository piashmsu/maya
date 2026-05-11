package com.myra.assistant.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.myra.assistant.data.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin wrapper around the Gemini Live `BidiGenerateContent` WebSocket. Designed
 * to mirror the Python reference client:
 *
 *  - SETUP_MS  on open
 *  - keepalive: silent 16 kHz PCM chunk every [KEEPALIVE_INTERVAL_MS]
 *  - session renew after [SESSION_RENEW_MS] (Gemini cuts sessions at ~10 min)
 *  - auto-reconnect after [RECONNECT_DELAY_MS] on disconnect
 */
class GeminiLiveClient(context: Context) {

    private val prefs = Prefs(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout — WebSocket is long-lived
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val webSocketRef = AtomicReference<WebSocket?>(null)
    private val isConnected = AtomicBoolean(false)
    private val isReceivingTurn = AtomicBoolean(false)
    private val shouldStayConnected = AtomicBoolean(false)

    private var keepaliveRunnable: Runnable? = null
    private var renewRunnable: Runnable? = null

    // ---- Callbacks (post on main thread). ---------------------------------

    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((reason: String) -> Unit)? = null
    var onSetupComplete: (() -> Unit)? = null
    var onAudioReceived: ((pcmBytes: ByteArray) -> Unit)? = null
    var onInputTranscript: ((text: String) -> Unit)? = null
    var onOutputTranscript: ((text: String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onError: ((throwable: Throwable, message: String) -> Unit)? = null

    // ---- Public API. ------------------------------------------------------

    fun isOpen(): Boolean = isConnected.get()

    fun connect() {
        shouldStayConnected.set(true)
        openSocket()
    }

    fun disconnect() {
        shouldStayConnected.set(false)
        cancelKeepalive()
        cancelRenew()
        webSocketRef.getAndSet(null)?.close(1000, "client_disconnect")
        isConnected.set(false)
    }

    /** Send a single base64-encoded 16 kHz mono PCM chunk to Gemini. */
    fun sendAudioChunk(base64Pcm: String) {
        val ws = webSocketRef.get() ?: return
        if (!isConnected.get()) return
        val media = JSONObject().apply {
            put("mime_type", "audio/pcm;rate=16000")
            put("data", base64Pcm)
        }
        val msg = JSONObject().apply {
            put(
                "realtime_input",
                JSONObject().put("media_chunks", JSONArray().put(media))
            )
        }
        ws.send(msg.toString())
    }

    /** Convenience: send raw PCM bytes (will be base64-encoded). */
    fun sendAudioBytes(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val encoded = Base64.encodeToString(pcm, Base64.NO_WRAP)
        sendAudioChunk(encoded)
    }

    /** Send a user text turn to Gemini. */
    fun sendText(text: String) {
        val ws = webSocketRef.get() ?: return
        if (!isConnected.get()) return
        val turn = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", text)))
        }
        val msg = JSONObject().apply {
            put(
                "client_content",
                JSONObject()
                    .put("turns", JSONArray().put(turn))
                    .put("turn_complete", true)
            )
        }
        ws.send(msg.toString())
    }

    /** Tell Gemini we're done speaking but there's no new content. */
    fun interrupt() {
        val ws = webSocketRef.get() ?: return
        if (!isConnected.get()) return
        val msg = JSONObject().apply {
            put(
                "client_content",
                JSONObject()
                    .put("turns", JSONArray())
                    .put("turn_complete", true)
            )
        }
        ws.send(msg.toString())
        isReceivingTurn.set(false)
    }

    // ---- Internals. -------------------------------------------------------

    private fun openSocket() {
        val key = prefs.apiKey.trim()
        if (key.isEmpty()) {
            postError(IllegalStateException("missing api key"), "Gemini API key not set in Settings")
            return
        }
        val url = "$BASE_URL?key=$key"
        val request = Request.Builder().url(url).build()
        val ws = httpClient.newWebSocket(request, listener)
        webSocketRef.set(ws)
        scheduleRenew()
    }

    private fun closeSocket(reason: String) {
        webSocketRef.getAndSet(null)?.close(1000, reason)
        isConnected.set(false)
    }

    private fun scheduleReconnect() {
        if (!shouldStayConnected.get()) return
        mainHandler.postDelayed({
            if (shouldStayConnected.get() && !isConnected.get()) {
                Log.i(TAG, "reconnecting…")
                openSocket()
            }
        }, RECONNECT_DELAY_MS)
    }

    private fun scheduleKeepalive() {
        cancelKeepalive()
        val r = object : Runnable {
            override fun run() {
                if (!isConnected.get()) return
                if (!isReceivingTurn.get()) {
                    // Send a tiny silent PCM chunk so Gemini knows we're alive.
                    sendAudioBytes(SILENT_KEEPALIVE_CHUNK)
                }
                mainHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
            }
        }
        keepaliveRunnable = r
        mainHandler.postDelayed(r, KEEPALIVE_INTERVAL_MS)
    }

    private fun cancelKeepalive() {
        keepaliveRunnable?.let { mainHandler.removeCallbacks(it) }
        keepaliveRunnable = null
    }

    private fun scheduleRenew() {
        cancelRenew()
        val r = Runnable {
            Log.i(TAG, "session renew — closing and reconnecting")
            closeSocket("session_renew")
            scheduleReconnect()
        }
        renewRunnable = r
        mainHandler.postDelayed(r, SESSION_RENEW_MS)
    }

    private fun cancelRenew() {
        renewRunnable?.let { mainHandler.removeCallbacks(it) }
        renewRunnable = null
    }

    private fun sendSetup(ws: WebSocket) {
        val speechConfig = JSONObject().put(
            "voice_config",
            JSONObject().put(
                "prebuilt_voice_config",
                JSONObject().put("voice_name", prefs.voice)
            )
        )
        val generationConfig = JSONObject().apply {
            put("response_modalities", JSONArray().put("AUDIO"))
            put("speech_config", speechConfig)
            put("temperature", 0.9)
        }
        val systemInstruction = JSONObject().put(
            "parts",
            JSONArray().put(
                JSONObject().put("text", SystemPrompts.build(prefs.personality, prefs.userName))
            )
        )
        val setup = JSONObject().apply {
            put("model", prefs.model)
            put("system_instruction", systemInstruction)
            put("generation_config", generationConfig)
            put("output_audio_transcription", JSONObject())
            put("input_audio_transcription", JSONObject())
        }
        val msg = JSONObject().put("setup", setup)
        ws.send(msg.toString())
    }

    private fun handleServerMessage(text: String) {
        val obj = try {
            JSONObject(text)
        } catch (e: Throwable) {
            postError(e, "bad server json")
            return
        }
        if (obj.has("setupComplete")) {
            mainHandler.post { onSetupComplete?.invoke() }
            scheduleKeepalive()
            return
        }
        val server = obj.optJSONObject("serverContent") ?: return
        isReceivingTurn.set(true)

        // Audio (modelTurn.parts[].inlineData.data).
        server.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val inline = part.optJSONObject("inlineData") ?: continue
                val data = inline.optString("data", "")
                if (data.isEmpty()) continue
                val pcm = try {
                    Base64.decode(data, Base64.DEFAULT)
                } catch (_: Throwable) {
                    null
                } ?: continue
                mainHandler.post { onAudioReceived?.invoke(pcm) }
            }
        }

        server.optJSONObject("inputTranscription")?.optString("text", "")?.takeIf { it.isNotEmpty() }
            ?.let { t -> mainHandler.post { onInputTranscript?.invoke(t) } }

        server.optJSONObject("outputTranscription")?.optString("text", "")?.takeIf { it.isNotEmpty() }
            ?.let { t -> mainHandler.post { onOutputTranscript?.invoke(t) } }

        if (server.optBoolean("turnComplete", false)) {
            isReceivingTurn.set(false)
            mainHandler.post { onTurnComplete?.invoke() }
        }
    }

    private fun postError(t: Throwable, msg: String) {
        mainHandler.post { onError?.invoke(t, msg) }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "ws open")
            isConnected.set(true)
            sendSetup(webSocket)
            mainHandler.post { onConnected?.invoke() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleServerMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Some Gemini backends send the JSON envelope as binary.
            handleServerMessage(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "ws closed code=$code reason=$reason")
            isConnected.set(false)
            cancelKeepalive()
            mainHandler.post { onDisconnected?.invoke(reason) }
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "ws failure", t)
            isConnected.set(false)
            cancelKeepalive()
            postError(t, "websocket failed")
            mainHandler.post { onDisconnected?.invoke(t.message ?: "failure") }
            scheduleReconnect()
        }
    }

    companion object {
        private const val TAG = "GeminiLive"
        private const val BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        private const val SESSION_RENEW_MS = 540_000L // 9 min
        private const val KEEPALIVE_INTERVAL_MS = 8_000L
        private const val RECONNECT_DELAY_MS = 3_000L

        /** 1024 bytes of silence at 16 kHz mono PCM 16-bit. */
        private val SILENT_KEEPALIVE_CHUNK: ByteArray = ByteArray(1024)
    }
}
