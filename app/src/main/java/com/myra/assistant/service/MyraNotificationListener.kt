package com.myra.assistant.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.myra.assistant.data.Prefs

/**
 * Forwards interesting notifications (WhatsApp / SMS / Telegram / Instagram /
 * Gmail) to [com.myra.assistant.ui.main.MainActivity] via a local broadcast so
 * MYRA can read them out via the live audio session.
 *
 * Requires the user to grant "Notification access" to MYRA — handled by the
 * one-tap root setup or manually by the user.
 */
class MyraNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg !in WATCHED_PACKAGES) return
        if (!Prefs(this).notificationReaderEnabled) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        // Skip MYRA's own ongoing notifications.
        if (sbn.packageName == packageName) return

        val sentence = formatSpoken(pkg, title, text)
        val intent = Intent(ACTION_READ_NOTIFICATION).apply {
            setPackage(packageName)
            putExtra(EXTRA_SOURCE, pkg)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_SPOKEN, sentence)
        }
        sendBroadcast(intent)
    }

    private fun formatSpoken(pkg: String, title: String, text: String): String {
        val source = when (pkg) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "com.android.messaging" -> "SMS"
            "org.telegram.messenger", "org.thunderdog.challegram" -> "Telegram"
            "com.instagram.android" -> "Instagram"
            "com.google.android.gm" -> "Gmail"
            "com.facebook.orca", "com.facebook.mlite" -> "Messenger"
            else -> "App"
        }
        val from = title.trim().ifEmpty { "Someone" }
        val body = text.trim()
        return if (body.isEmpty()) {
            "$source par $from se ek notification aaya hai."
        } else {
            "$source par $from ka message: $body"
        }
    }

    companion object {
        const val ACTION_READ_NOTIFICATION = "com.myra.assistant.READ_NOTIFICATION"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SPOKEN = "spoken"

        private val WATCHED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "com.android.messaging",
            "org.telegram.messenger",
            "org.thunderdog.challegram",
            "com.instagram.android",
            "com.google.android.gm",
            "com.facebook.orca",
            "com.facebook.mlite",
        )

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            val mine = ComponentName(context, MyraNotificationListener::class.java)
                .flattenToString()
            return flat.split(":").any { it.equals(mine, ignoreCase = true) }
        }
    }
}
