package com.example.adshield.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.adshield.data.FilterEngine
import com.example.adshield.data.Settings
import com.example.adshield.data.Stats
import com.example.adshield.data.Upstreams
import com.example.adshield.vpn.DnsVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()
    var upstream by remember { mutableStateOf(settings.upstream) }
    var doh by remember { mutableStateOf(settings.useDoh) }
    var nullIp by remember { mutableStateOf(settings.nullIpResponse) }
    var antiBypass by remember { mutableStateOf(settings.antiBypass) }
    var autostart by remember { mutableStateOf(settings.autostart) }
    val restart = { DnsVpnService.restartIfRunning(ctx) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Server DNS pentru domeniile permise") {
            for (opt in Upstreams.OPTIONS) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        upstream = opt.id; settings.upstream = opt.id; restart()
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = upstream == opt.id, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(opt.name)
                        Text(opt.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            ToggleRow(
                "DNS criptat (DNS-over-HTTPS)",
                "Operatorul/Wi-Fi-ul nu mai vede ce site-uri accesezi. Dacă nu merge, se trece automat pe DNS clasic.",
                doh, enabled = upstream != Upstreams.SYSTEM_ID,
            ) { doh = it; settings.useDoh = it; restart() }
        }

        SectionCard("Blocare") {
            ToggleRow(
                "Anti-ocolire",
                "Blochează DNS-ul criptat propriu al aplicațiilor (Firefox, Chrome „DNS securizat”) ca să treacă prin filtru.",
                antiBypass,
            ) {
                antiBypass = it; settings.antiBypass = it
                scope.launch(Dispatchers.IO) { FilterEngine.reload(ctx.applicationContext) }
            }
            ToggleRow(
                "Răspunde cu 0.0.0.0",
                "Recomandat. Dezactivat = răspuns „domeniu inexistent” (NXDOMAIN); unele aplicații reîncearcă mai des.",
                nullIp,
            ) { nullIp = it; settings.nullIpResponse = it; restart() }
        }

        SectionCard("Pornire") {
            ToggleRow("Pornește la boot", "Repornește protecția după restartul telefonului.", autostart) {
                autostart = it; settings.autostart = it
            }
            Text(
                "Pentru protecție permanentă activează „VPN permanent activ” pentru AdShield. " +
                    "Tot de acolo poți adăuga butonul AdShield în setările rapide.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                try { ctx.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS)) } catch (_: Exception) {}
            }) { Text("Deschide setările VPN") }
        }

        SectionCard("Date") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    Stats.reset(ctx); Toast.makeText(ctx, "Statistici resetate", Toast.LENGTH_SHORT).show()
                }) { Text("Resetează statisticile") }
                OutlinedButton(onClick = { Stats.clearLog() }) { Text("Golește jurnalul") }
            }
            Text(
                "Totul rămâne pe telefon. AdShield nu trimite nimic către vreun server propriu; " +
                    "doar cererile DNS permise ajung la serverul DNS ales mai sus.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard("Despre") {
            Text("AdShield 1.0", style = MaterialTheme.typography.titleSmall)
            Text(
                "Liste: StevenBlack/hosts (MIT, inclusă), HaGeZi DNS Blocklists, OISD, AdGuard DNS filter, 1Hosts — " +
                    "descărcate de pe sursele lor oficiale.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
