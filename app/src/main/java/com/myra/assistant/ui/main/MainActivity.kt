package com.myra.assistant.ui.main

import android.Manifest
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.ai.SystemPrompts
import com.myra.assistant.data.Prefs
import com.myra.assistant.service.AccessibilityHelperService
import com.myra.assistant.service.CallMonitorService
import com.myra.assistant.service.MyraOverlayService
import com.myra.assistant.ui.settings.SettingsActivity
import com.myra.assistant.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val prefs by lazy { Prefs(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var orbView: OrbAnimationView
    private lateinit var waveformView: WaveformView
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var batteryText: TextView
    private lateinit var ramText: TextView
    private lateinit var timeText: TextView
    private lateinit var chatRecycler: RecyclerView
    private lateinit var micButton: ImageButton
    private lateinit var settingsBtn: ImageButton
    private lateinit var redOverlay: View
    private val chatAdapter = ChatAdapter()

    private var geminiLive: GeminiLiveClient? = null
    private var audioEngine: AudioEngine? = null
    private var isMuted = false
    private var isInCallMode = false
    private var pendingCallerName: String? = null
    private val inputBuffer = StringBuilder()
    private val outputBuffer = StringBuilder()
    private var micLongPressed = false
    private var statusRunnable: Runnable? = null

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            isInCallMode = false
            pendingCallerName = null
            setActiveMode(false)
        }
    }

    private val resultObserver = androidx.lifecycle.Observer<String?> { text ->
        if (text.isNullOrBlank()) return@Observer
        chatAdapter.submit(ChatMessage("MYRA: $text", isUser = false))
        chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
        geminiLive?.sendText("(Phone action result: $text)")
        viewModel.clearResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        viewModel.commandResult.observe(this, resultObserver)
        checkPermissions()
        startSystemServices()
        startStatusUpdates()
        ContextCompat.registerReceiver(
            this,
            callEndedReceiver,
            IntentFilter(CallMonitorService.ACTION_CALL_ENDED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        mainHandler.postDelayed({ initGeminiLive() }, 300L)
        handleIncomingCallIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingCallIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        audioEngine?.setMuted(true)
    }

    override fun onResume() {
        super.onResume()
        if (!isMuted) audioEngine?.setMuted(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        statusRunnable?.let { mainHandler.removeCallbacks(it) }
        runCatching { unregisterReceiver(callEndedReceiver) }
        geminiLive?.disconnect()
        audioEngine?.release()
    }

    // -- UI / setup ---------------------------------------------------------

    private fun initViews() {
        orbView = findViewById(R.id.orbView)
        waveformView = findViewById(R.id.waveformView)
        statusText = findViewById(R.id.statusText)
        titleText = findViewById(R.id.titleText)
        batteryText = findViewById(R.id.batteryText)
        ramText = findViewById(R.id.ramText)
        timeText = findViewById(R.id.timeText)
        chatRecycler = findViewById(R.id.chatRecycler)
        micButton = findViewById(R.id.micButton)
        settingsBtn = findViewById(R.id.settingsBtn)
        redOverlay = findViewById(R.id.redOverlay)

        chatRecycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        chatRecycler.adapter = chatAdapter

        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        micButton.setOnClickListener { toggleMute() }
        micButton.setOnLongClickListener {
            micLongPressed = true
            audioEngine?.interrupt()
            geminiLive?.interrupt()
            updateStatusForState()
            true
        }
    }

    private fun checkPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CAMERA,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
        if (!Settings.canDrawOverlays(this)) {
            // Don't block; just nudge.
            Toast.makeText(this, "Enable overlay permission for MYRA orb", Toast.LENGTH_LONG).show()
        }
        if (!AccessibilityHelperService.isEnabled(this)) {
            Toast.makeText(this, "Enable Accessibility to control apps", Toast.LENGTH_LONG).show()
        }
    }

    private fun startSystemServices() {
        if (!MyraOverlayService.isRunning && Settings.canDrawOverlays(this)) {
            MyraOverlayService.show(this)
        }
        CallMonitorService.start(this)
    }

    private fun startStatusUpdates() {
        val r = object : Runnable {
            override fun run() {
                refreshStatus()
                mainHandler.postDelayed(this, 30_000L)
            }
        }
        statusRunnable = r
        mainHandler.post(r)
    }

    private fun refreshStatus() {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.ENGLISH)
        timeText.text = timeFmt.format(Date())
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level > 0 && scale > 0) (level * 100 / scale) else 0
        batteryText.text = "BAT $pct%"
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val freeGb = mi.availMem / (1024.0 * 1024.0 * 1024.0)
        ramText.text = String.format(Locale.ENGLISH, "RAM %.1fG", freeGb)
    }

    // -- Gemini Live wiring -------------------------------------------------

    private fun initGeminiLive() {
        if (prefs.apiKey.isBlank()) {
            statusText.text = getString(R.string.status_disconnected)
            chatAdapter.submit(
                ChatMessage("MYRA: Settings mein API key set kar do, fir main connect karungi.", isUser = false)
            )
            return
        }
        val live = GeminiLiveClient(this)
        val audio = AudioEngine(this)

        live.onConnected = {
            statusText.text = getString(R.string.status_connecting)
        }
        live.onSetupComplete = {
            audio.startRecording()
            audio.startPlayback()
            statusText.text = getString(R.string.status_listening)
            updateStatusForState()
            mainHandler.postDelayed({ sendGreeting() }, 600L)
        }
        live.onDisconnected = {
            statusText.text = getString(R.string.status_disconnected)
        }
        live.onAudioReceived = { pcm -> audio.queueAudio(pcm) }
        live.onInputTranscript = { text ->
            inputBuffer.append(text)
        }
        live.onOutputTranscript = { text ->
            outputBuffer.append(text)
        }
        live.onTurnComplete = { flushTranscripts() }
        live.onError = { _, msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        audio.onMicChunk = { pcm ->
            if (!isInCallMode) live.sendAudioBytes(pcm)
        }
        audio.onAmplitudeChanged = { rms ->
            waveformView.setAmplitude(rms)
        }
        audio.onSpeakingStarted = {
            orbView.setOrbState(OrbAnimationView.State.SPEAKING)
            setActiveMode(true)
            statusText.text = getString(R.string.status_speaking)
        }
        audio.onSpeakingStopped = {
            orbView.setOrbState(OrbAnimationView.State.LISTENING)
            setActiveMode(false)
            statusText.text = getString(R.string.status_listening)
        }

        geminiLive = live
        audioEngine = audio
        waveformView.startAnimation()
        live.connect()
    }

    private fun sendGreeting() {
        val greeting = SystemPrompts.greeting(prefs.personality, prefs.userName)
        geminiLive?.sendText(greeting)
    }

    private fun flushTranscripts() {
        val userText = inputBuffer.toString().trim()
        val myraText = outputBuffer.toString().trim()
        inputBuffer.setLength(0)
        outputBuffer.setLength(0)
        if (userText.isNotEmpty()) {
            chatAdapter.submit(ChatMessage(userText, isUser = true))
            handleUserCommand(userText)
        }
        if (myraText.isNotEmpty() && myraText != chatAdapter.lastMyraText()) {
            chatAdapter.submit(ChatMessage(myraText, isUser = false))
        }
        chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
    }

    private fun handleUserCommand(transcript: String) {
        if (isInCallMode) {
            // Keyword shortcut while a call is ringing.
            val lower = transcript.lowercase(Locale.ENGLISH)
            if (lower.contains("uthao") || lower.contains("haan") || lower.contains("accept") || lower.contains("answer")) {
                viewModel.acceptCall()
                isInCallMode = false
                return
            }
            if (lower.contains("reject") || lower.contains("nahi") || lower.contains("mat") || lower.contains("decline")) {
                viewModel.rejectCall()
                isInCallMode = false
                return
            }
        }
        CommandParser.parse(transcript)?.let { viewModel.execute(it) }
    }

    // -- Mute / interrupt --------------------------------------------------

    private fun toggleMute() {
        isMuted = !isMuted
        audioEngine?.setMuted(isMuted)
        micButton.setImageResource(if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on)
        statusText.text = if (isMuted) "Muted" else getString(R.string.status_listening)
    }

    private fun updateStatusForState() {
        micButton.setImageResource(if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on)
    }

    private fun setActiveMode(active: Boolean) {
        val target = if (active) 0.08f else 0f
        val animator = ValueAnimator.ofFloat(redOverlay.alpha, target).apply {
            duration = if (active) 300L else 500L
            addUpdateListener { redOverlay.alpha = it.animatedValue as Float }
        }
        animator.start()
    }

    // -- Incoming calls -----------------------------------------------------

    private fun handleIncomingCallIntent(intent: Intent?) {
        intent ?: return
        if (!intent.getBooleanExtra(CallMonitorService.EXTRA_INCOMING_CALL, false)) return
        val name = intent.getStringExtra(CallMonitorService.EXTRA_CALLER_NAME)
        announceCall(name ?: "Unknown")
    }

    private fun announceCall(callerName: String) {
        isInCallMode = true
        pendingCallerName = callerName
        val sentence = "Sir, $callerName ka call aa raha hai. Uthau ya reject karu?"
        geminiLive?.sendText(sentence)
        chatAdapter.submit(ChatMessage("MYRA: $sentence", isUser = false))
        setActiveMode(true)
        mainHandler.postDelayed({ startCallDecisionRecognizer() }, 4_500L)
    }

    private fun startCallDecisionRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull().orEmpty()
                handleUserCommand(transcript)
                rec.destroy()
            }

            override fun onError(error: Int) { rec.destroy() }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        rec.startListening(intent)
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1009
    }
}
