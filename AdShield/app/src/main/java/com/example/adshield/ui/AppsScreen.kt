package com.example.adshield.ui

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.adshield.data.Settings
import com.example.adshield.vpn.DnsVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class AppItem(val pkg: String, val label: String, val system: Boolean)

@Composable
fun AppsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    var excluded by remember { mutableStateOf(settings.excludedApps) }
    var changes by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }

    val apps by produceState<List<AppItem>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .filter { it.packageName != ctx.packageName }
                .filter { it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true }
                .mapNotNull { pi ->
                    val ai = pi.applicationInfo ?: return@mapNotNull null
                    AppItem(pi.packageName, pm.getApplicationLabel(ai).toString(), (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    // Aplică modificările (reconstruiește VPN-ul) la o secundă după ultima schimbare.
    LaunchedEffect(changes) {
        if (changes == 0) return@LaunchedEffect
        delay(1000)
        DnsVpnService.restartIfRunning(ctx)
    }

    Column(modifier.fillMaxSize()) {
        Text(
            "Aplicațiile bifate NU sunt filtrate. Folosește asta dacă un joc nu-ți mai dă recompense pentru reclame " +
                "sau dacă o aplicație (ex. bancară) nu funcționează cu AdShield pornit.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Caută aplicație") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Arată aplicațiile de sistem", Modifier.weight(1f))
            Switch(checked = showSystem, onCheckedChange = { showSystem = it })
        }
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val shown = list
            .filter { showSystem || !it.system || it.pkg in excluded }
            .filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
            .sortedByDescending { it.pkg in excluded } // excluse sus
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.pkg }) { item ->
                val checked = item.pkg in excluded
                fun toggle() {
                    excluded = if (checked) excluded - item.pkg else excluded + item.pkg
                    settings.excludedApps = excluded
                    changes++
                }
                Row(
                    Modifier.fillMaxWidth().clickable { toggle() }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(item.pkg)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.pkg, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Checkbox(checked = checked, onCheckedChange = { toggle() })
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            try { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() } catch (_: Exception) { null }
        }
    }
    Box(Modifier.size(40.dp)) { icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
}
