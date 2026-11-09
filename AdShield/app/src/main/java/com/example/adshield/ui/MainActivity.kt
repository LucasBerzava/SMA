package com.example.adshield.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.example.adshield.data.FilterEngine
import com.example.adshield.data.Stats
import com.example.adshield.vpn.DnsVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) DnsVpnService.start(this)
        else Toast.makeText(this, "Fără permisiunea VPN, AdShield nu poate filtra.", Toast.LENGTH_LONG).show()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Stats.load(this)
        lifecycleScope.launch(Dispatchers.IO) { FilterEngine.ensureLoaded(applicationContext) }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent { AdShieldTheme { AdShieldApp(onToggle = ::toggle) } }
    }

    private fun toggle() {
        if (DnsVpnService.running.value) {
            DnsVpnService.stop(this)
        } else {
            val request = VpnService.prepare(this)
            if (request == null) DnsVpnService.start(this) else vpnPermission.launch(request)
        }
    }
}

private data class Tab(val title: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Acasă", Icons.Filled.Home),
    Tab("Jurnal", Icons.AutoMirrored.Filled.List),
    Tab("Filtre", Icons.Filled.Lock),
    Tab("Aplicații", Icons.Filled.Phone),
    Tab("Setări", Icons.Filled.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdShieldApp(onToggle: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (tab == 0) "AdShield" else TABS[tab].title) }) },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.title) },
                        label = { Text(t.title, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            0 -> HomeScreen(m, onToggle, onOpenFilters = { tab = 2 })
            1 -> LogScreen(m)
            2 -> FiltersScreen(m)
            3 -> AppsScreen(m)
            else -> SettingsScreen(m)
        }
    }
}

private val Green = Color(0xFF1B6B4A)

@Composable
fun AdShieldTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF8BD6B0), secondary = Color(0xFFB3CCBE))
        else -> lightColorScheme(primary = Green, secondary = Color(0xFF4D6357))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
