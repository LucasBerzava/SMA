package com.example.adshield

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val store = remember { FilterStore(this) }
            var active by remember { mutableStateOf(DnsVpnService.isActive()) }
            var total by remember { mutableLongStateOf(0L) }
            var blocked by remember { mutableLongStateOf(0L) }
            var last by remember { mutableStateOf("") }
            var custom by remember { mutableStateOf(store.customText()) }
            var allow by remember { mutableStateOf(store.allowText()) }
            var saved by remember { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    startForegroundService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START))
                }
            }
            LaunchedEffect(Unit) {
                while (true) {
                    active = DnsVpnService.isActive()
                    val prefs = getSharedPreferences("stats", MODE_PRIVATE)
                    total = prefs.getLong("total", 0)
                    blocked = prefs.getLong("blocked", 0)
                    last = prefs.getString("last", "") ?: ""
                    delay(750)
                }
            }
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("AdShield", style = MaterialTheme.typography.headlineLarge)
                    Text("Prototip SMA • filtrare DNS locală", style = MaterialTheme.typography.bodyMedium)
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (active) "Protecție activă" else "Protecție oprită", style = MaterialTheme.typography.titleLarge)
                            Button(onClick = {
                                if (active) startService(Intent(this@MainActivity, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
                                else {
                                    val request = VpnService.prepare(this@MainActivity)
                                    if (request == null) startForegroundService(Intent(this@MainActivity, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START))
                                    else launcher.launch(request)
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text(if (active) "OPREȘTE" else "PORNEȘTE") }
                        }
                    }
                    Text("Statistici (de la instalare)", style = MaterialTheme.typography.titleMedium)
                    Text("Cereri DNS analizate: $total")
                    Text("Cereri blocate: $blocked")
                    Text("Ultimul domeniu blocat: ${last.ifBlank { "—" }}")
                    HorizontalDivider()
                    Text("Domenii de blocat (unul pe linie)", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = custom, onValueChange = { custom = it; saved = false }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("Reguli personalizate") })
                    Text("Domenii permise (prioritate peste blocare)", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = allow, onValueChange = { allow = it; saved = false }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("Excepții") })
                    Button(onClick = { store.save(custom, allow); saved = true }, modifier = Modifier.fillMaxWidth()) { Text("SALVEAZĂ REGULILE") }
                    if (saved) Text("Regulile au fost salvate.")
                    HorizontalDivider()
                    Text("Limitări", style = MaterialTheme.typography.titleMedium)
                    Text("Filtrează DNS IPv4 necriptat care ajunge la VPN. Nu filtrează DNS-over-HTTPS, Private DNS, DNS prin TCP, IPv6 sau reclame servite din același domeniu cu conținutul. Nu blochează garantat reclamele YouTube. Nu colectează trafic pe un server propriu.")
                    Text("DNS permis este trimis către 1.1.1.1 prin UDP. Nu folosi această versiune ca soluție de securitate sau confidențialitate.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
