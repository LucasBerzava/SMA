package com.example.adshield.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.adshield.core.PacketDns
import com.example.adshield.data.FilterEngine
import com.example.adshield.data.Settings
import com.example.adshield.data.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BLOCKED_COLOR = Color(0xFFD84343)
private val ALLOWED_COLOR = Color(0xFF2E9D63)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick by rememberTicker()
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 toate, 1 blocate, 2 permise
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Stats.Entry?>(null) }
    var cleared by remember { mutableIntStateOf(0) }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val entries = remember(tick, filter, query, cleared) {
        Stats.recent().asSequence()
            .filter { filter == 0 || (filter == 1) == it.blocked }
            .filter { query.isBlank() || it.domain.contains(query.trim(), true) || it.app?.contains(query.trim(), true) == true }
            .take(400).toList()
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.weight(1f), singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Caută domeniu sau aplicație") },
            )
            IconButton(onClick = { Stats.clearLog(); cleared++ }) { Icon(Icons.Filled.Delete, "Golește jurnalul") }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Toate", "Blocate", "Permise").forEachIndexed { i, label ->
                FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
            }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nicio cerere încă. Pornește protecția și folosește telefonul; aici apar domeniile accesate. " +
                        "Atinge un domeniu ca să-l blochezi sau să-l permiți.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = e }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).background(if (e.blocked) BLOCKED_COLOR else ALLOWED_COLOR, CircleShape))
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.domain, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(timeFmt.format(Date(e.time)), e.app, PacketDns.typeName(e.type)).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    selected?.let { e ->
        val parent = parentDomain(e.domain)
        fun applyRule(domain: String, allow: Boolean) {
            selected = null
            scope.launch {
                withContext(Dispatchers.IO) {
                    Settings(ctx).addRule(domain, allow)
                    FilterEngine.reload(ctx.applicationContext)
                }
                Toast.makeText(
                    ctx,
                    (if (allow) "Permis: " else "Blocat: ") + domain + "\n(aplicațiile pot păstra răspunsul vechi ~1 min)",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(e.domain, fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    Text(if (e.blocked) "Acest domeniu a fost blocat." else "Acest domeniu a fost permis.")
                    e.app?.let { Text("Aplicație: $it", style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.size(12.dp))
                    if (e.blocked) {
                        TextButton(onClick = { applyRule(e.domain, true) }) { Text("Permite ${e.domain}") }
                        if (parent != null) TextButton(onClick = { applyRule(parent, true) }) { Text("Permite tot $parent") }
                    } else {
                        TextButton(onClick = { applyRule(e.domain, false) }) { Text("Blochează ${e.domain}") }
                        if (parent != null) TextButton(onClick = { applyRule(parent, false) }) { Text("Blochează tot $parent") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Închide") } },
        )
    }
}
