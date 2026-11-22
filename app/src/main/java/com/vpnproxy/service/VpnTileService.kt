package com.vpnproxy.service

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vpnproxy.MainActivity
import com.vpnproxy.R
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.logging.LogCollectorHolder

/**
 * Quick Settings tile: shows the live tunnel state and toggles the VPN.
 *
 * Connect is intentionally handed off to [MainActivity] instead of being duplicated
 * here: the real flow needs subscription refresh, a preflight scan and the connection
 * coordinator, all of which live in `HomeViewModel`. Disconnect is a single service
 * intent and is handled in place.
 */
class VpnTileService : TileService() {
    private companion object {
        private const val TAG = "VpnTile"
        /** Collapses the QS panel after the intent is delivered. */
        private fun TileService.startActivityAndCollapseCompat(intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private val log: LogCollector? get() = LogCollectorHolder.get()

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (VpnProxyService.isRunning.value) {
            log?.info(TAG, "Tile click: disconnect")
            startService(
                Intent(this, VpnProxyService::class.java)
                    .setAction(VpnProxyService.ACTION_REQUEST_DISCONNECT)
            )
            updateTile()
            return
        }

        log?.info(TAG, "Tile click: connect requested")
        if (VpnProxyService.isCoreTransitioning.value) {
            log?.info(TAG, "Tile click ignored: core is transitioning")
            updateTile()
            return
        }

        // Missing VPN consent cannot be granted from a tile; surface the system prompt.
        val prepareIntent = runCatching { VpnService.prepare(this) }.getOrNull()
        val target = prepareIntent ?: Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_AUTO_CONNECT, true)
        }
        startActivityAndCollapseCompat(target)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = VpnProxyService.isRunning.value
        val transitioning = VpnProxyService.isCoreTransitioning.value
        tile.state = when {
            transitioning -> Tile.STATE_UNAVAILABLE
            running -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.app_name)
        tile.subtitle = when {
            transitioning -> "..."
            running -> getString(R.string.state_connected)
            else -> getString(R.string.state_disconnected)
        }
        runCatching { tile.updateTile() }
    }
}
