package com.example.adshield.ui

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Valoare care crește la fiecare [periodMs]; folosită pentru reîmprospătarea statisticilor. */
@Composable
fun rememberTicker(periodMs: Long = 1000): State<Int> = produceState(0) {
    while (true) { delay(periodMs); value++ }
}

@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
            }
            content()
        }
    }
}

fun fmt(n: Long): String = "%,d".format(n)
fun fmt(n: Int): String = "%,d".format(n)

/** Numele serverului dacă "DNS privat" e setat pe un hostname (ocolește filtrul), altfel null. */
fun strictPrivateDns(ctx: Context): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)!!
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val lp = cm.getLinkProperties(n) ?: continue
            if (lp.isPrivateDnsActive && lp.privateDnsServerName != null) return lp.privateDnsServerName
        }
    } catch (_: Exception) {}
    return try {
        val cr = ctx.contentResolver
        if (AndroidSettings.Global.getString(cr, "private_dns_mode") == "hostname")
            AndroidSettings.Global.getString(cr, "private_dns_specifier") ?: "server personalizat"
        else null
    } catch (_: Exception) { null }
}

/** Domeniul "părinte" util pentru reguli: ads.tracker.example.com -> example.com */
fun parentDomain(domain: String): String? {
    val parts = domain.split('.')
    if (parts.size <= 2) return null
    // Sufixe compuse uzuale (co.uk, com.ro etc.)
    val twoLevel = parts[parts.size - 2] in setOf("co", "com", "org", "net", "gov", "edu", "ac") && parts.last().length == 2
    val keep = if (twoLevel) 3 else 2
    if (parts.size <= keep) return null
    return parts.takeLast(keep).joinToString(".")
}
