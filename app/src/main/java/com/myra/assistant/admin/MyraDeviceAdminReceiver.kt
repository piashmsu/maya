package com.myra.assistant.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Optional device-admin component. Activating this lets MYRA:
 *
 *  - Lock the screen on command ("MYRA lock the phone")
 *  - Set or reset the keyguard password (root + device-admin combined)
 *  - Resist uninstall \u2014 once active, the app cannot be removed without first
 *    revoking admin (deliberate user friction)
 *
 * The receiver is enabled either through the system Security \u2192 Device admin
 * apps menu, or silently via the root auto-setup using:
 *
 * ```
 * dpm set-active-admin --user 0 com.myra.assistant/.admin.MyraDeviceAdminReceiver
 * ```
 *
 * We intentionally keep this receiver's footprint minimal \u2014 it does not
 * enforce password rules or wipe the device. It's purely a capability gate.
 */
class MyraDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, MyraDeviceAdminReceiver::class.java)
    }
}
