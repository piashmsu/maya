package com.myra.assistant.data

import android.content.Context
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Runs a batch of privileged setup commands via `su -c` so a rooted phone can
 * grant MYRA every permission it needs in one tap — including enabling the
 * accessibility service, overlay permission, runtime permissions, and battery
 * optimisation whitelist.
 *
 * Each command is executed independently; failures are reported per-line so the
 * UI can show which ones succeeded. No exception is thrown.
 */
object RootSetup {

    data class StepResult(val command: String, val ok: Boolean, val output: String)

    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val out = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            out.isNotBlank()
        } catch (_: Throwable) {
            false
        }
    }

    fun runAll(context: Context): List<StepResult> {
        val pkg = context.packageName
        val asPkg = "com.myra.assistant"
        val accessibilityComponent =
            "$asPkg/com.myra.assistant.service.AccessibilityHelperService"
        val notificationComponent =
            "$asPkg/com.myra.assistant.service.MyraNotificationListener"

        val commands = listOf(
            // Runtime permissions — silently granted only via root/adb.
            "pm grant $pkg android.permission.RECORD_AUDIO",
            "pm grant $pkg android.permission.READ_CONTACTS",
            "pm grant $pkg android.permission.CALL_PHONE",
            "pm grant $pkg android.permission.SEND_SMS",
            "pm grant $pkg android.permission.READ_PHONE_STATE",
            "pm grant $pkg android.permission.ANSWER_PHONE_CALLS",
            "pm grant $pkg android.permission.CAMERA",
            "pm grant $pkg android.permission.READ_PHONE_NUMBERS",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "pm grant $pkg android.permission.BLUETOOTH_CONNECT",
            "pm grant $pkg android.permission.MODIFY_AUDIO_SETTINGS",

            // App-ops: overlay & write settings.
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "appops set $pkg WRITE_SETTINGS allow",

            // Accessibility service — enable our helper.
            "settings put secure enabled_accessibility_services $accessibilityComponent",
            "settings put secure accessibility_enabled 1",

            // Notification listener — enable our listener so MYRA can read
            // WhatsApp / SMS / Telegram notifications.
            "settings put secure enabled_notification_listeners $notificationComponent",

            // Battery optimisation whitelist so wake word / call monitor
            // services keep running.
            "dumpsys deviceidle whitelist +$pkg",

            // Best-effort: register as assistant role on Android 10+.
            "cmd role add-role-holder android.app.role.ASSISTANT $pkg",
        )
        return execCommands(commands)
    }

    private fun execCommands(commands: List<String>): List<StepResult> {
        val results = mutableListOf<StepResult>()
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            val stdin = DataOutputStream(process.outputStream)
            for (cmd in commands) {
                // Echo a marker so we can split per-command output.
                stdin.writeBytes("$cmd && echo __MYRA_OK__ || echo __MYRA_FAIL__\n")
            }
            stdin.writeBytes("exit\n")
            stdin.flush()

            val outputReader = BufferedReader(InputStreamReader(process.inputStream))
            val collected = mutableListOf<String>()
            outputReader.forEachLine { collected.add(it) }
            process.waitFor()

            // Walk the collected output and pair each marker with the command.
            var idx = 0
            val buffer = StringBuilder()
            for (line in collected) {
                if (line == "__MYRA_OK__" || line == "__MYRA_FAIL__") {
                    val ok = line == "__MYRA_OK__"
                    val cmd = commands.getOrNull(idx) ?: ""
                    results.add(StepResult(cmd, ok, buffer.toString().trim()))
                    buffer.setLength(0)
                    idx++
                } else {
                    buffer.appendLine(line)
                }
            }
            // Fill in any commands without a marker (e.g. su denied).
            while (results.size < commands.size) {
                results.add(StepResult(commands[results.size], false, "no su output"))
            }
        } catch (t: Throwable) {
            // Either su denied or root unavailable.
            commands.forEach {
                results.add(StepResult(it, false, t.message ?: "root unavailable"))
            }
        } finally {
            runCatching { process?.destroy() }
        }
        return results
    }
}
