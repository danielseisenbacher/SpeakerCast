package at.seisi.simpleaudiostream

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile: one tap casts to the last speaker, another tap stops. */
class CastTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val s = MainScope()
        scope = s
        s.launch { CastState.status.collect { render(it) } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        if (CastState.isActive) {
            CastService.stop(this)
            return
        }
        if (isLocked) unlockAndRun { launchConsent() } else launchConsent()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchConsent() {
        val intent = Intent(this, ConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(status: CastState.Status) {
        val tile = qsTile ?: return
        val speaker = Prefs(this).renderer?.name
        tile.label = speaker ?: getString(R.string.tile_label)
        tile.subtitle = getString(
            when {
                status is CastState.Status.Connecting -> R.string.tile_connecting
                status is CastState.Status.Casting -> R.string.tile_casting
                speaker == null -> R.string.tile_no_speaker
                else -> R.string.tile_idle
            },
        )
        tile.state = if (CastState.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            requestListeningState(context, ComponentName(context, CastTileService::class.java))
        }
    }
}
