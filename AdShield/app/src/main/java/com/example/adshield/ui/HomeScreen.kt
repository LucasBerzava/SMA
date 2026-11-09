package com.example.adshield.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.adshield.data.Blocklists
import com.example.adshield.data.FilterEngine
import com.example.adshield.data.Settings
import com.example.adshield.data.Stats
import com.example.adshield.vpn.DnsVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(modifier: Modifier, onToggle: () -> Unit, onOpenFilters: () -> Unit) {
    val ctx = LocalContext.current
    val running by DnsVpnService.running.collectAsStateWithLifecycle()
    val updating by Blocklists.updating.collectAsStateWithLifecycle()
    val tick by rememberTicker()
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(ctx) }

    // Citite la fiecare tick (1 s)
    val blockedSession = remember(tick) { Stats.blockedSession.get() }
    val totalSession = remember(tick) { Stats.totalSession.get() }
    val blockedAll = remember(tick) { Stats.blockedAllTime.get() }
    val totalAll = remember(tick) { Stats.totalAllTime.get() }
    val topDomains = remember(tick / 3) { Stats.topBlockedDomains(5) }
    val topApps = remember(tick / 3) { Stats.topBlockedApps(5) }
    val privateDns = remember(tick / 5) { strictPrivateDns(ctx) }
    val missingLists = remember(tick / 5, updating) {
        Blocklists.CATALOG.filter { it.id in settings.enabledLists && !Blocklists.isAvailable(ctx, it) }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ----- stare + buton -----
        val accent = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(84.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.surface)
                }
                Spacer(Modifier.height(12.dp))
                Text(if (running) "Protecție activă" else "Protecție oprită", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (running) "${fmt(FilterEngine.ruleCount)} domenii de reclame și trackere blocate"
                    else "Pornește ca să blochezi reclamele în aplicații, jocuri și browsere",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onToggle,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = if (running) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                ) { Text(if (running) "OPREȘTE" else "PORNEȘTE", style = MaterialTheme.typography.titleMedium) }
            }
        }

        // ----- avertismente -----
        if (privateDns != null) {
            WarningCard(
                "„DNS privat” ocolește filtrul",
                "Android trimite DNS-ul direct la $privateDns, așa că reclamele nu sunt blocate. " +
                    "Setează „DNS privat” pe Automat sau Dezactivat (Setări › Rețea › DNS privat).",
                "Deschide setările",
            ) {
                try { ctx.startActivity(Intent(AndroidSettings.ACTION_WIRELESS_SETTINGS)) }
                catch (_: Exception) { ctx.startActivity(Intent(AndroidSettings.ACTION_SETTINGS)) }
            }
        }
        if (missingLists.isNotEmpty()) {
            WarningCard(
                "Listele complete nu sunt descărcate",
                "Acum se folosește doar lista inclusă. Descarcă ${missingLists.joinToString { it.name }} pentru blocare mult mai bună.",
                if (updating) "Se descarcă…" else "Descarcă acum",
            ) {
                if (!updating) scope.launch {
                    val msg = withContext(Dispatchers.IO) { Blocklists.update(ctx.applicationContext, force = false) }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            }
            if (updating) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        // ----- statistici -----
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Blocate", fmt(blockedSession), Modifier.weight(1f))
            StatTile("Cereri", fmt(totalSession), Modifier.weight(1f))
            StatTile("Procent", if (totalSession == 0L) "—" else "${blockedSession * 100 / totalSession}%", Modifier.weight(1f))
        }
        Text(
            "Total de la instalare: ${fmt(blockedAll)} blocate din ${fmt(totalAll)} cereri",
            style = MaterialTheme.typography.bodySmall,
        )

        if (topApps.isNotEmpty()) SectionCard("Aplicații cu cele mai multe reclame blocate") {
            topApps.forEach { (name, n) -> RankRow(name, n) }
        }
        if (topDomains.isNotEmpty()) SectionCard("Domenii blocate frecvent") {
            topDomains.forEach { (name, n) -> RankRow(name, n) }
        }

        SectionCard("Ce blochează AdShield") {
            Text(
                "✓ Reclame din aplicații și jocuri (AdMob, AppLovin, Unity Ads, ironSource, Meta Audience…)\n" +
                    "✓ Reclame și trackere din browsere și site-uri\n" +
                    "✓ Telemetrie, trackere, domenii de malware (în funcție de liste)\n\n" +
                    "✗ Reclamele YouTube și Facebook/Instagram vin de pe aceleași servere ca videoclipurile și postările, " +
                    "deci nu pot fi blocate prin DNS. Pentru YouTube folosește un client fără reclame (ex. ReVanced) " +
                    "sau Firefox cu uBlock Origin.\n" +
                    "✗ Spațiul gol rămas în pagină după blocare (asta cere un blocker în browser).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenFilters) { Text("Alege listele de blocare") }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RankRow(name: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(fmt(count), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun WarningCard(title: String, text: String, action: String, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.size(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}
