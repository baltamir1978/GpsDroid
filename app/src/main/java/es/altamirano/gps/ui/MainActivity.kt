package es.altamirano.gps.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import es.altamirano.common.ui.isWide
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.Tracks

private enum class Tab(val label: String, val icon: ImageVector) {
    MAP("Mapa", Icons.Filled.Map),
    SKY("Satélites", Icons.Filled.SatelliteAlt),
    TRACKS("Tracks", Icons.Filled.Route),
    OFFLINE("Offline", Icons.Filled.CloudDownload),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GpsTheme { App() } }
    }
}

@Composable
private fun App() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Gps.permissionChanged() }
    LaunchedEffect(Unit) {
        if (!Gps.hasPermission()) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    // Abrir un track lleva al mapa.
    val fit by Tracks.fitRequest.collectAsState()
    LaunchedEffect(fit) { if (fit > 0) tab = Tab.MAP.ordinal }

    val content: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            when (Tab.entries[tab]) {
                Tab.MAP -> MapScreen()
                Tab.SKY -> SkyScreen()
                Tab.TRACKS -> TracksScreen()
                Tab.OFFLINE -> DownloadScreen()
            }
        }
    }
    if (isWide()) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail {
                Tab.entries.forEach { t ->
                    NavigationRailItem(selected = tab == t.ordinal, onClick = { tab = t.ordinal },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
            content(Modifier.weight(1f))
        }
    } else {
        Scaffold(bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t.ordinal, onClick = { tab = t.ordinal },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
        }) { pad -> content(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) }
    }
}
