package com.example.adshield.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.example.adshield.data.Settings

/** Repornește protecția după restartul telefonului sau după actualizarea aplicației. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = Settings(context)
        if (!settings.autostart || !settings.wantRunning) return
        if (VpnService.prepare(context) != null) return // permisiunea VPN a fost retrasă
        try {
            DnsVpnService.start(context)
        } catch (e: Exception) {
            Log.w("AdShield", "Pornire automată eșuată", e)
        }
    }
}
