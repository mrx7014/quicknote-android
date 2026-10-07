package com.quicknote.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

abstract class BaseCaptureTileService : TileService() {
    protected abstract val captureType: String
    protected abstract val tileLabel: Int

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply { label = getString(tileLabel); state = Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { openCapture() }
        } else openCapture()
    }

    private fun openCapture() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_CAPTURE_TYPE, captureType)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(this, requestCode(captureType), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else startActivity(intent)
    }

    private fun requestCode(type: String) = type.hashCode()
}

class QuickCaptureTileService : BaseCaptureTileService() {
    override val captureType = "note"
    override val tileLabel = R.string.widget_tile_note
}

class QuickCaptureTaskTileService : BaseCaptureTileService() {
    override val captureType = "task"
    override val tileLabel = R.string.widget_tile_task
}

class QuickCaptureVoiceTileService : BaseCaptureTileService() {
    override val captureType = "voice"
    override val tileLabel = R.string.widget_tile_voice
}
