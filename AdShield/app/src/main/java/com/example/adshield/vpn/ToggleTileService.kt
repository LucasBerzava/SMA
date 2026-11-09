package com.example.adshield.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.adshield.ui.MainActivity

/** Buton în panoul de setări rapide (trage bara de notificări în jos). */
class ToggleTileService : TileService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (DnsVpnService.running.value) {
            DnsVpnService.stop(this)
        } else if (VpnService.prepare(this) == null) {
            try { DnsVpnService.start(this) } catch (_: Exception) { openApp() }
        } else {
            openApp() // trebuie aprobată întâi permisiunea VPN
        }
        handler.postDelayed({ refresh() }, 700)
        handler.postDelayed({ refresh() }, 1500)
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = DnsVpnService.running.value
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = if (on) "Activ" else "Oprit"
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
