package com.myra.assistant.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.myra.assistant.data.Prefs
import com.myra.assistant.ui.main.MainActivity

/**
 * Quick Settings tile entry-point — long-press the notification shade,
 * drag MYRA into the tile rail, then a single tap from anywhere on the
 * device opens MYRA directly. No need to find the launcher icon.
 *
 * The tile is reflected as ACTIVE when the wake word service is on, so
 * the user gets a glanceable cue that MYRA is listening.
 */
@RequiresApi(Build.VERSION_CODES.N)
class MyraTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshState()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_FROM_TILE, true)
        // startActivityAndCollapse() forces the panel shut; the unlock-aware
        // overload is API 34+, the legacy one works back to API 24.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE
                        or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshState() {
        val tile = qsTile ?: return
        val prefs = Prefs(this)
        tile.state = if (prefs.wakeWordEnabled && WakeWordService.isRunning) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    companion object {
        const val EXTRA_FROM_TILE = "myra_from_tile"
    }
}
