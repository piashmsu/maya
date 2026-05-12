package com.myra.assistant.model

/**
 * A parsed voice command intended for execution on the device.
 *
 * `type` is one of the values in [CommandType], `params` is a free-form bag of
 * arguments (e.g. `app_name`, `number`, `message`, `index`).
 */
data class AppCommand(
    val type: String,
    val params: Map<String, String> = emptyMap(),
) {
    fun param(key: String): String? = params[key]
    fun paramOrEmpty(key: String): String = params[key] ?: ""
}

object CommandType {
    const val OPEN_APP = "OPEN_APP"
    const val CLOSE_APP = "CLOSE_APP"
    const val CALL = "CALL"
    const val SMS = "SMS"
    const val WHATSAPP_MSG = "WHATSAPP_MSG"
    const val WHATSAPP_CALL = "WHATSAPP_CALL"
    const val PRIME_CALL = "PRIME_CALL"
    const val PRIME_MSG = "PRIME_MSG"
    const val VOLUME_UP = "VOLUME_UP"
    const val VOLUME_DOWN = "VOLUME_DOWN"
    const val VOLUME_MUTE = "VOLUME_MUTE"
    const val FLASHLIGHT_ON = "FLASHLIGHT_ON"
    const val FLASHLIGHT_OFF = "FLASHLIGHT_OFF"
    const val WIFI_ON = "WIFI_ON"
    const val WIFI_OFF = "WIFI_OFF"
    const val BLUETOOTH_ON = "BLUETOOTH_ON"
    const val BLUETOOTH_OFF = "BLUETOOTH_OFF"

    /** User wants MYRA to look at whatever is on screen right now. */
    const val SCREEN_CONTEXT_QUERY = "SCREEN_CONTEXT_QUERY"
}
