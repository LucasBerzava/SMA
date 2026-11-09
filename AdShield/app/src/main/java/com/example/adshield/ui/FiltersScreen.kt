package com.example.adshield.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.adshield.data.Blocklists
import com.example.adshield.data.FilterEngine
import com.example.adshield.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FiltersScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val settings = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()
    val updating by Blocklists.updating.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(settings.enabledLists) }
    var message by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) } // forțează recitirea stării listelor
    var reloading by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(settings.customBlock) }
    var allow by remember { mutableStateOf(settings.customAllow) }

    fun runUpdate(force: Boolean) = scope.launch {
        message = withContext(Dispatchers.IO) { Blocklists.update(app, force) }
        version++
    }
    fun reload() = scope.launch {
        reloading = true
        withContext(Dispatchers.IO) { FilterEngine.reload(app) }
        reloading = false
        version++
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(null) {
            Text("${fmt(remember(version, reloading, updating) { FilterEngine.ruleCount })} reguli active",
                style = MaterialTheme.typography.titleLarge)
            Text(
                "Listele se actualizează automat la 3 zile. Mai multe liste = mai multe reclame blocate, " +
                    "dar și un risc mai mare ca un site să nu meargă (îl poți permite din Jurnal).",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.size(8.dp))
            Button(onClick = { runUpdate(true) }, enabled = !updating) { Text(if (updating) "Se actualizează…" else "Actualizează acum") }
            if (updating || reloading) { Spacer(Modifier.size(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            message?.let { Spacer(Modifier.size(6.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        Text("Liste de blocare", style = MaterialTheme.typography.titleMedium)
        for (src in Blocklists.CATALOG) {
            val on = src.id in enabled
            val status = remember(version, updating) {
                when {
                    src.asset != null -> "Inclusă în aplicație"
                    !Blocklists.isAvailable(ctx, src) -> Blocklists.lastError(ctx, src.id)?.let { "Eroare: $it" } ?: "Nedescărcată"
                    else -> "${fmt(Blocklists.ruleCount(ctx, src.id))} domenii • actualizată " +
                        DateUtils.getRelativeTimeSpanString(Blocklists.lastUpdated(ctx, src.id))
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(src.name, style = MaterialTheme.typography.titleSmall)
                        Text(src.description, style = MaterialTheme.typography.bodySmall)
                        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Switch(checked = on, onCheckedChange = { checked ->
                        enabled = if (checked) enabled + src.id else enabled - src.id
                        settings.enabledLists = enabled
                        if (checked && !Blocklists.isAvailable(ctx, src)) runUpdate(false) else reload()
                    })
                }
            }
        }

        Text("Reguli proprii", style = MaterialTheme.typography.titleMedium)
        Text(
            "Un domeniu pe linie. O regulă se aplică și subdomeniilor (ex. „example.com” blochează și „ads.example.com”). " +
                "Excepțiile au prioritate față de orice listă.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = custom, onValueChange = { custom = it },
            label = { Text("Blochează") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        OutlinedTextField(
            value = allow, onValueChange = { allow = it },
            label = { Text("Permite întotdeauna (excepții)") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Button(
            onClick = {
                settings.customBlock = custom
                settings.customAllow = allow
                reload()
                Toast.makeText(ctx, "Reguli salvate", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Salvează regulile") }
    }
}
