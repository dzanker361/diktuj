package cz.dl.diktuj

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Dlaždice v rychlém nastavení: klepnutí spustí/zastaví nahrávání. */
class DiktujTileService : TileService() {

    override fun onStartListening() = update()

    override fun onClick() {
        if (RecorderService.recording) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            return
        }
        // Mikrofon smí z pozadí spustit jen viditelná aktivita, proto dlaždice otevře appku, ta nahrávání spustí a zavře se.
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_AUTOSTART, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        tile.state = if (RecorderService.recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Diktuj"
        tile.subtitle = if (RecorderService.recording) "Nahrává se" else "Klepni a mluv"
        tile.updateTile()
    }
}
