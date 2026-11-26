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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import es.altamirano.common.ui.glass
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.vector.ImageVector
import es.altamirano.common.ui.isWide
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.Tracks
import es.altamirano.gps.data.Waypoints

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
        // La Surface pone el color del texto (onSurface): sin ella Compose deja el negro de serie y,
        // en modo oscuro, los textos sin color propio (Tracks, sobre todo) no se leían.
        setContent { GpsTheme { Surface(color = MaterialTheme.colorScheme.surface) { App() } } }
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
    val focus by Waypoints.focus.collectAsState()
    LaunchedEffect(focus) { if (focus.n > 0) tab = Tab.MAP.ordinal }

    // Que cada pestaña recuerde lo suyo (lo elegido en Offline, el scroll) al volver a ella.
    val saved = rememberSaveableStateHolder()
    val screen: @Composable () -> Unit = {
        saved.SaveableStateProvider(tab) {
            when (Tab.entries[tab]) {
                Tab.MAP -> MapScreen()
                Tab.SKY -> SkyScreen()
                Tab.TRACKS -> TracksScreen()
                Tab.OFFLINE -> DownloadScreen()
            }
        }
    }
    if (isWide()) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationRailItem(selected = tab == t.ordinal, onClick = { tab = t.ordinal },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
            // Sin barra propia abajo, pero en modo escritorio la barra de tareas del sistema tapa el
            // borde inferior de la ventana maximizada (con 0 dp se comía la barra del mapa).
            val system = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
            Box(Modifier.weight(1f)) { CompositionLocalProvider(LocalBottomBar provides system) { screen() } }
        }
    } else {
        // El contenido va por debajo de la barra (el mapa se ve a través de ella); cada pantalla deja
        // ese hueco al final de su lista con LocalBottomBar.
        val bar = 80.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            CompositionLocalProvider(LocalBottomBar provides bar) { screen() }
            NavigationBar(Modifier.align(Alignment.BottomCenter).glass(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), alpha = 0.82f),
                containerColor = Color.Transparent, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t.ordinal, onClick = { tab = t.ordinal },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
        }
    }
}

/** Lo que tapa la barra de navegación translúcida por abajo. */
val LocalBottomBar = compositionLocalOf { 0.dp }
