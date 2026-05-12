package com.myra.assistant.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.myra.assistant.R
import com.myra.assistant.data.ChatHistory
import com.myra.assistant.data.Prefs
import com.myra.assistant.data.PrimeContact
import com.myra.assistant.data.RootSetup
import com.myra.assistant.service.AccessibilityHelperService
import com.myra.assistant.service.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs(this) }
    private val chatHistory by lazy { ChatHistory(this) }
    private val primeAdapter = PrimeContactAdapter()

    private lateinit var apiKeyInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var languageSpinner: Spinner
    private lateinit var personalityGroup: RadioGroup
    private lateinit var personalityGf: RadioButton
    private lateinit var personalityPro: RadioButton
    private lateinit var personalityAssistant: RadioButton
    private lateinit var primeRecycler: RecyclerView
    private lateinit var addPrimeButton: Button
    private lateinit var accessibilityText: TextView
    private lateinit var saveButton: Button
    private lateinit var wakeWordSwitch: SwitchMaterial
    private lateinit var notifReaderSwitch: SwitchMaterial
    private lateinit var clearHistoryButton: Button
    private lateinit var autoSetupButton: Button
    private lateinit var autoSetupResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bind()
        populate()
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
    }

    private fun bind() {
        apiKeyInput = findViewById(R.id.apiKeyInput)
        nameInput = findViewById(R.id.nameInput)
        modelSpinner = findViewById(R.id.modelSpinner)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        languageSpinner = findViewById(R.id.languageSpinner)
        personalityGroup = findViewById(R.id.personalityGroup)
        personalityGf = findViewById(R.id.personalityGf)
        personalityPro = findViewById(R.id.personalityPro)
        personalityAssistant = findViewById(R.id.personalityAssistant)
        primeRecycler = findViewById(R.id.primeRecycler)
        addPrimeButton = findViewById(R.id.addPrimeButton)
        accessibilityText = findViewById(R.id.accessibilityText)
        saveButton = findViewById(R.id.saveButton)
        wakeWordSwitch = findViewById(R.id.wakeWordSwitch)
        notifReaderSwitch = findViewById(R.id.notifReaderSwitch)
        clearHistoryButton = findViewById(R.id.clearHistoryButton)
        autoSetupButton = findViewById(R.id.autoSetupButton)
        autoSetupResult = findViewById(R.id.autoSetupResult)

        primeRecycler.layoutManager = LinearLayoutManager(this)
        primeRecycler.adapter = primeAdapter

        modelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Prefs.MODELS.map { it.second },
        )
        voiceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Prefs.VOICES.map { it.second },
        )
        languageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Prefs.LANGUAGES.map { it.second },
        )

        addPrimeButton.setOnClickListener { showAddPrimeDialog() }
        accessibilityText.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        clearHistoryButton.setOnClickListener {
            chatHistory.clear()
            Toast.makeText(this, "Chat history cleared", Toast.LENGTH_SHORT).show()
        }
        autoSetupButton.setOnClickListener { runRootSetup() }
        saveButton.setOnClickListener { save() }
    }

    private fun populate() {
        apiKeyInput.setText(prefs.apiKey)
        nameInput.setText(prefs.userName)

        val modelIdx = Prefs.MODELS.indexOfFirst { it.first == prefs.model }
        modelSpinner.setSelection(if (modelIdx >= 0) modelIdx else 0)

        val voiceIdx = Prefs.VOICES.indexOfFirst { it.first == prefs.voice }
        voiceSpinner.setSelection(if (voiceIdx >= 0) voiceIdx else 0)

        val langIdx = Prefs.LANGUAGES.indexOfFirst { it.first == prefs.language }
        languageSpinner.setSelection(if (langIdx >= 0) langIdx else 0)

        when (prefs.personality) {
            Prefs.PERSONALITY_PRO -> personalityPro.isChecked = true
            Prefs.PERSONALITY_ASSISTANT -> personalityAssistant.isChecked = true
            else -> personalityGf.isChecked = true
        }

        wakeWordSwitch.isChecked = prefs.wakeWordEnabled
        notifReaderSwitch.isChecked = prefs.notificationReaderEnabled

        primeAdapter.submit(prefs.getPrimeContacts())
    }

    private fun refreshAccessibilityStatus() {
        val enabled = AccessibilityHelperService.isEnabled(this)
        accessibilityText.text = getString(
            if (enabled) R.string.settings_accessibility_on else R.string.settings_accessibility_off
        )
    }

    private fun showAddPrimeDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_prime_contact, null)
        val nameEt = view.findViewById<EditText>(R.id.dialogNameInput)
        val numberEt = view.findViewById<EditText>(R.id.dialogNumberInput)
        AlertDialog.Builder(this, R.style.Theme_Myra_Dialog)
            .setTitle(getString(R.string.settings_add_prime))
            .setView(view)
            .setPositiveButton(getString(R.string.dialog_save)) { _, _ ->
                val name = nameEt.text.toString().trim()
                val number = numberEt.text.toString().trim()
                if (name.isEmpty() || number.isEmpty()) {
                    Toast.makeText(this, "Naam aur number dono chahiye", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                primeAdapter.add(PrimeContact(name, number))
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun runRootSetup() {
        autoSetupResult.text = getString(R.string.settings_auto_setup_running)
        lifecycleScope.launch {
            if (!withContext(Dispatchers.IO) { RootSetup.isRootAvailable() }) {
                autoSetupResult.text = getString(R.string.settings_auto_setup_no_root)
                return@launch
            }
            val results = withContext(Dispatchers.IO) { RootSetup.runAll(this@SettingsActivity) }
            val ok = results.count { it.ok }
            val fail = results.size - ok
            val summary = buildString {
                append("$ok ok, $fail failed\n\n")
                results.forEach {
                    val tag = if (it.ok) "[OK]" else "[FAIL]"
                    append(tag).append(' ')
                    append(shortLabel(it.command))
                    append('\n')
                }
            }
            autoSetupResult.text = summary
            refreshAccessibilityStatus()
        }
    }

    private fun shortLabel(command: String): String {
        // Cosmetic: trim the long pm-grant prefix so the result list stays readable.
        return command
            .removePrefix("pm grant com.myra.assistant ")
            .removePrefix("pm grant com.myra.assistant.debug ")
            .removePrefix("settings put secure ")
            .removePrefix("appops set com.myra.assistant ")
            .removePrefix("appops set com.myra.assistant.debug ")
    }

    private fun save() {
        prefs.apiKey = apiKeyInput.text.toString().trim()
        prefs.userName = nameInput.text.toString().trim()
        prefs.model = Prefs.MODELS.getOrNull(modelSpinner.selectedItemPosition)?.first
            ?: Prefs.DEFAULT_MODEL
        prefs.voice = Prefs.VOICES.getOrNull(voiceSpinner.selectedItemPosition)?.first
            ?: Prefs.DEFAULT_VOICE
        prefs.language = Prefs.LANGUAGES.getOrNull(languageSpinner.selectedItemPosition)?.first
            ?: Prefs.DEFAULT_LANGUAGE
        prefs.personality = when (personalityGroup.checkedRadioButtonId) {
            R.id.personalityPro -> Prefs.PERSONALITY_PRO
            R.id.personalityAssistant -> Prefs.PERSONALITY_ASSISTANT
            else -> Prefs.PERSONALITY_GF
        }
        prefs.wakeWordEnabled = wakeWordSwitch.isChecked
        prefs.notificationReaderEnabled = notifReaderSwitch.isChecked
        prefs.setPrimeContacts(primeAdapter.current())

        // React to wake-word toggle immediately so the user gets feedback
        // without restarting the app.
        if (prefs.wakeWordEnabled && !WakeWordService.isRunning) {
            WakeWordService.start(this)
        } else if (!prefs.wakeWordEnabled && WakeWordService.isRunning) {
            WakeWordService.stop(this)
        }

        Toast.makeText(this, getString(R.string.settings_save_hint), Toast.LENGTH_LONG).show()
        finish()
    }
}
