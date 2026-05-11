package com.myra.assistant.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.myra.assistant.data.Prefs
import com.myra.assistant.data.PrimeContact
import com.myra.assistant.model.AppCommand
import com.myra.assistant.model.CommandType
import com.myra.assistant.service.AccessibilityHelperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Executes phone-side commands (calls, SMS, app launches, system toggles) and
 * reports a short human-readable status string back through [commandResult] so
 * MainActivity can forward it to Gemini as a turn.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication<Application>().applicationContext
    private val prefs = Prefs(ctx)

    private val _commandResult = MutableLiveData<String?>()
    val commandResult: LiveData<String?> get() = _commandResult

    fun clearResult() {
        _commandResult.value = null
    }

    fun execute(command: AppCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (command.type) {
                    CommandType.OPEN_APP -> openApp(command.paramOrEmpty("app_name"))
                    CommandType.CLOSE_APP -> closeApp(command.paramOrEmpty("app_name"))
                    CommandType.CALL -> makeCall(command.paramOrEmpty("name"))
                    CommandType.SMS -> sendSms(
                        command.paramOrEmpty("name"),
                        command.paramOrEmpty("message"),
                    )
                    CommandType.WHATSAPP_MSG -> whatsappMessage(
                        command.paramOrEmpty("name"),
                        command.paramOrEmpty("message"),
                    )
                    CommandType.WHATSAPP_CALL -> whatsappCall(command.paramOrEmpty("name"))
                    CommandType.PRIME_CALL -> primeCall(
                        command.param("index")?.toIntOrNull() ?: 0,
                    )
                    CommandType.PRIME_MSG -> primeMessage(
                        command.param("index")?.toIntOrNull() ?: 0,
                    )
                    CommandType.VOLUME_UP -> adjustVolume(true)
                    CommandType.VOLUME_DOWN -> adjustVolume(false)
                    CommandType.VOLUME_MUTE -> muteVolume()
                    CommandType.FLASHLIGHT_ON -> setFlashlight(true)
                    CommandType.FLASHLIGHT_OFF -> setFlashlight(false)
                    CommandType.WIFI_ON -> setWifi(true)
                    CommandType.WIFI_OFF -> setWifi(false)
                    CommandType.BLUETOOTH_ON -> setBluetooth(true)
                    CommandType.BLUETOOTH_OFF -> setBluetooth(false)
                    else -> postResult("I'm not sure how to do that yet.")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "execute(${command.type}) failed", t)
                postResult("Sorry, that didn't work.")
            }
        }
    }

    fun acceptCall() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tm = ContextCompat.getSystemService(ctx, TelecomManager::class.java) ?: return@launch
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    postResult("Mujhe phone answer karne ki permission nahi hai.")
                    return@launch
                }
                tm.acceptRingingCall()
                postResult("Call answered.")
            } catch (t: Throwable) {
                Log.w(TAG, "acceptCall failed", t)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun rejectCall() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tm = ContextCompat.getSystemService(ctx, TelecomManager::class.java) ?: return@launch
                tm.endCall()
                postResult("Call rejected.")
            } catch (t: Throwable) {
                Log.w(TAG, "rejectCall failed", t)
            }
        }
    }

    // -- Apps ---------------------------------------------------------------

    private fun openApp(name: String) {
        if (name.isBlank()) return postResult("Konsa app kholoon?")
        if (!AccessibilityHelperService.isEnabled(ctx)) {
            launchAccessibilitySettings()
            return postResult("Pehle Accessibility permission de do.")
        }
        val pkg = resolvePackageForApp(name)
        if (pkg.isNullOrEmpty()) {
            postResult("$name nahi mil raha mere paas.")
            return
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            postResult("$name launch nahi ho paya.")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        postResult("$name khol diya.")
    }

    private fun closeApp(name: String) {
        if (!AccessibilityHelperService.isEnabled(ctx)) {
            launchAccessibilitySettings()
            return postResult("Pehle Accessibility permission de do.")
        }
        AccessibilityHelperService.instance?.closeCurrentApp()
        postResult(if (name.isBlank()) "Band kar diya." else "$name band kar diya.")
    }

    private fun resolvePackageForApp(rawName: String): String? {
        val name = rawName.lowercase(Locale.ENGLISH).trim()
        APP_MAP[name]?.let { return it }
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PackageManager.ApplicationInfoFlags.of(0L)
        } else {
            null
        }
        val apps = if (flags != null) {
            pm.getInstalledApplications(flags)
        } else {
            @Suppress("DEPRECATION") pm.getInstalledApplications(0)
        }
        for (app in apps) {
            val label = (pm.getApplicationLabel(app)).toString().lowercase(Locale.ENGLISH)
            if (label.contains(name)) return app.packageName
        }
        return null
    }

    // -- Calls / SMS / WhatsApp --------------------------------------------

    private fun makeCall(target: String) {
        val number = resolveTargetNumber(target)
        if (number.isNullOrEmpty()) {
            postResult("$target ka number nahi mila.")
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            postResult("Mujhe call ki permission do, phir kar deti hoon.")
            return
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        postResult("Calling ${target.ifEmpty { number }}…")
    }

    private fun sendSms(target: String, message: String) {
        val number = resolveTargetNumber(target) ?: target
        val uri = Uri.parse("smsto:$number")
        val intent = Intent(Intent.ACTION_SENDTO, uri)
            .putExtra("sms_body", message)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        postResult("SMS draft khol diya.")
    }

    private fun whatsappMessage(target: String, message: String) {
        val number = resolveTargetNumber(target) ?: target
        val text = Uri.encode(message.ifEmpty { "" })
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number?text=$text"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        postResult("WhatsApp kholti hoon.")
    }

    private fun whatsappCall(target: String) {
        val number = resolveTargetNumber(target) ?: target
        // wa.me deeplink with the call query parameter opens WhatsApp's call screen.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        postResult("WhatsApp call open kar rahi hoon.")
    }

    private fun primeCall(index: Int) {
        val c = primeAt(index) ?: return postResult("Koi prime contact set nahi hai.")
        makeCall(c.number)
    }

    private fun primeMessage(index: Int) {
        val c = primeAt(index) ?: return postResult("Koi prime contact set nahi hai.")
        whatsappMessage(c.number, "")
    }

    private fun primeAt(index: Int): PrimeContact? {
        val list = prefs.getPrimeContacts()
        if (list.isEmpty()) return null
        return list.getOrNull(index) ?: list.first()
    }

    private fun resolveTargetNumber(rawTarget: String): String? {
        val target = rawTarget.trim()
        if (target.isEmpty()) return null
        if (target.matches(Regex("[+0-9 ()-]{5,}"))) {
            return target.filter { it.isDigit() || it == '+' }
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val cr = ctx.contentResolver
        val cursor = cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null,
            null,
            null,
        ) ?: return null
        cursor.use {
            val nameCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameCol)?.lowercase(Locale.ENGLISH) ?: continue
                if (name.contains(target.lowercase(Locale.ENGLISH))) {
                    return it.getString(numCol)
                }
            }
        }
        return null
    }

    // -- System toggles -----------------------------------------------------

    private fun adjustVolume(up: Boolean) {
        val am = ContextCompat.getSystemService(ctx, AudioManager::class.java) ?: return
        val dir = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        postResult(if (up) "Volume badha diya." else "Volume kam kar diya.")
    }

    private fun muteVolume() {
        val am = ContextCompat.getSystemService(ctx, AudioManager::class.java) ?: return
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_MUTE,
            AudioManager.FLAG_SHOW_UI,
        )
        postResult("Mute kar diya.")
    }

    private fun setFlashlight(on: Boolean) {
        val cm = ContextCompat.getSystemService(ctx, CameraManager::class.java) ?: return
        val cameraId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return postResult("Is phone mein flash nahi mila.")
        cm.setTorchMode(cameraId, on)
        postResult(if (on) "Torch on." else "Torch off.")
    }

    @Suppress("DEPRECATION")
    private fun setWifi(on: Boolean) {
        // WiFi toggle is restricted on Q+, so we fall back to opening the panel.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val intent = Intent(android.provider.Settings.Panel.ACTION_WIFI)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            postResult("WiFi panel khol diya.")
            return
        }
        val wifi = ContextCompat.getSystemService(ctx, WifiManager::class.java) ?: return
        wifi.isWifiEnabled = on
        postResult(if (on) "WiFi on." else "WiFi off.")
    }

    @SuppressLint("MissingPermission")
    private fun setBluetooth(on: Boolean) {
        val bm = ContextCompat.getSystemService(ctx, BluetoothManager::class.java) ?: return
        val adapter: BluetoothAdapter = bm.adapter ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            postResult("Bluetooth permission chahiye.")
            return
        }
        @Suppress("DEPRECATION")
        if (on) adapter.enable() else adapter.disable()
        postResult(if (on) "Bluetooth on." else "Bluetooth off.")
    }

    // -- Helpers ------------------------------------------------------------

    private fun launchAccessibilitySettings() {
        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    private fun postResult(text: String) {
        _commandResult.postValue(text)
    }

    companion object {
        private const val TAG = "MainViewModel"

        private val APP_MAP = mapOf(
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "wa" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "insta" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "fb" to "com.facebook.katana",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "mail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "telegram" to "org.telegram.messenger",
            "snapchat" to "com.snapchat.android",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "calendar" to "com.google.android.calendar",
            "clock" to "com.google.android.deskclock",
            "phone" to "com.google.android.dialer",
            "dialer" to "com.google.android.dialer",
            "contacts" to "com.google.android.contacts",
            "play store" to "com.android.vending",
            "playstore" to "com.android.vending",
            "amazon" to "in.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android",
            "paytm" to "net.one97.paytm",
            "phonepe" to "com.phonepe.app",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "google pay" to "com.google.android.apps.nbu.paisa.user",
            "zoom" to "us.zoom.videomeetings",
            "meet" to "com.google.android.apps.meetings",
            "google meet" to "com.google.android.apps.meetings",
            "teams" to "com.microsoft.teams",
            "tiktok" to "com.zhiliaoapp.musically",
            "discord" to "com.discord",
            "linkedin" to "com.linkedin.android",
        )
    }
}
