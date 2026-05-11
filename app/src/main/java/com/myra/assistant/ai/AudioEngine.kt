package com.myra.assistant.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.concurrent.thread

/**
 * Native PCM audio I/O for MYRA — mirrors the Python `sounddevice` setup:
 *
 *  * Mic:   16 kHz mono PCM 16-bit, `VOICE_RECOGNITION` source, 1024-byte chunks
 *  * Speaker: 24 kHz mono PCM 16-bit, `USAGE_ASSISTANT` / `CONTENT_TYPE_SPEECH`
 *
 * While MYRA is speaking, mic frames are NOT sent out (echo suppression). A
 * long-press on the mic button calls [interrupt] which clears the playback
 * queue and stops MYRA mid-sentence.
 */
class AudioEngine(private val context: Context) {

    // ---- Mic side. --------------------------------------------------------
    private val mainHandler = Handler(Looper.getMainLooper())
    private var record: AudioRecord? = null
    private val recording = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private var recordThread: Thread? = null

    // ---- Speaker side. ----------------------------------------------------
    private var track: AudioTrack? = null
    private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()
    private val playing = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    // ---- Callbacks. -------------------------------------------------------

    /** Called when a fresh mic PCM chunk is ready to be sent to Gemini. */
    var onMicChunk: ((pcm: ByteArray) -> Unit)? = null

    /** 0..1 RMS level of the mic chunk (good enough for a UI waveform). */
    var onAmplitudeChanged: ((rms: Float) -> Unit)? = null

    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingStopped: (() -> Unit)? = null

    fun isMuted(): Boolean = muted.get()

    fun setMuted(value: Boolean) {
        muted.set(value)
    }

    fun isSpeaking(): Boolean = speaking.get()

    // ---- Mic. -------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (recording.get()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted — mic disabled")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            MIC_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, CHUNK_BYTES * 4)
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MIC_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            ar.release()
            return
        }
        record = ar
        recording.set(true)
        ar.startRecording()
        recordThread = thread(name = "myra-mic") {
            val buffer = ByteArray(CHUNK_BYTES)
            while (recording.get()) {
                val read = try {
                    ar.read(buffer, 0, buffer.size)
                } catch (e: Throwable) {
                    Log.w(TAG, "mic read failed", e)
                    -1
                }
                if (read <= 0) continue
                val chunk = buffer.copyOf(read)
                val rms = rms16(chunk, read)
                mainHandler.post { onAmplitudeChanged?.invoke(rms) }
                if (!muted.get() && !speaking.get()) {
                    onMicChunk?.invoke(chunk)
                }
            }
        }
    }

    fun stopRecording() {
        recording.set(false)
        val t = recordThread
        recordThread = null
        runCatching { t?.join(200) }
        val r = record
        record = null
        r?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
    }

    // ---- Speaker. ---------------------------------------------------------

    fun startPlayback() {
        if (playing.get()) return
        val minBuf = AudioTrack.getMinBufferSize(
            SPEAKER_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, CHUNK_BYTES * 8)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SPEAKER_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val at = AudioTrack(
            attrs,
            format,
            bufSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track = at
        at.play()
        playing.set(true)

        playbackThread = thread(name = "myra-speaker") {
            while (playing.get()) {
                val chunk = playbackQueue.poll()
                if (chunk == null) {
                    if (speaking.compareAndSet(true, false)) {
                        mainHandler.post { onSpeakingStopped?.invoke() }
                    }
                    Thread.sleep(20)
                    continue
                }
                if (speaking.compareAndSet(false, true)) {
                    mainHandler.post { onSpeakingStarted?.invoke() }
                }
                var written = 0
                while (written < chunk.size && playing.get()) {
                    val w = try {
                        at.write(chunk, written, chunk.size - written)
                    } catch (e: Throwable) {
                        Log.w(TAG, "track write failed", e)
                        -1
                    }
                    if (w <= 0) break
                    written += w
                }
            }
        }
    }

    fun stopPlayback() {
        playing.set(false)
        speaking.set(false)
        playbackQueue.clear()
        val t = playbackThread
        playbackThread = null
        runCatching { t?.join(200) }
        val at = track
        track = null
        at?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
    }

    /** Enqueue a 24 kHz PCM chunk received from Gemini. */
    fun queueAudio(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        playbackQueue.offer(pcm)
    }

    /** Drop the playback queue + immediately silence speaker. */
    fun interrupt() {
        playbackQueue.clear()
        val at = track ?: return
        runCatching { at.pause() }
        runCatching { at.flush() }
        runCatching { at.play() }
        if (speaking.compareAndSet(true, false)) {
            mainHandler.post { onSpeakingStopped?.invoke() }
        }
    }

    fun release() {
        stopRecording()
        stopPlayback()
    }

    private fun rms16(buf: ByteArray, len: Int): Float {
        if (len <= 1) return 0f
        val pairs = len / 2
        var sum = 0.0
        var i = 0
        while (i < len - 1) {
            val lo = buf[i].toInt() and 0xff
            val hi = buf[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val s = if (sample > 32767) sample - 65536 else sample
            sum += (s.toDouble() * s.toDouble())
            i += 2
        }
        val mean = sum / pairs
        val rms = sqrt(mean) / 32768.0
        return min(1.0, rms).toFloat()
    }

    companion object {
        private const val TAG = "AudioEngine"
        const val MIC_RATE = 16_000
        const val SPEAKER_RATE = 24_000
        const val CHUNK_BYTES = 1024
    }
}
