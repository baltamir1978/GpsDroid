package es.altamirano.gps.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.altamirano.common.ui.Header
import es.altamirano.common.ui.TonalCard
import es.altamirano.common.ui.gutter
import es.altamirano.gps.core.PoiCategory
import es.altamirano.gps.core.PoiGroup
import es.altamirano.gps.data.Prefs

/**
 * Qué iconos de puntos salen en el callejero y el híbrido (el estilo del CNIG trae decenas: antenas,
 * torres eléctricas, depuradoras…). Se guarda al tocar; el mapa lo aplica al volver a él y el coche,
 * al cambiar de mapa o al volver a abrirlo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PoisScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var on by remember { mutableStateOf(prefs.pois) }
    val set: (Set<PoiCategory>) -> Unit = { on = it; prefs.pois = it }

    val side = gutter()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalBottomBar.current + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header("Puntos del mapa", "Qué iconos salen en el callejero", Icons.Filled.Place, side)
        Column(Modifier.padding(horizontal = side), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Son los del Mapa Base del IGN, en el callejero y el híbrido (también en el coche). " +
                "Farmacias no hay: el IGN no las tiene. Los nombres de calles, pueblos y ríos salen siempre.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { set(PoiCategory.DEFAULT) }) { Text("Los de serie") }
                OutlinedButton(onClick = { set(PoiCategory.entries.toSet()) }) { Text("Todos") }
                OutlinedButton(onClick = { set(emptySet()) }) { Text("Ninguno") }
            }
            PoiGroup.entries.forEach { g ->
                val cats = PoiCategory.entries.filter { it.group == g }
                TonalCard {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(g.label, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            val all = on.containsAll(cats)
                            Text(if (all) "Quitar todos" else "Poner todos", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { set(if (all) on - cats.toSet() else on + cats) }.padding(6.dp))
                        }
                        cats.forEach { c ->
                            Row(Modifier.fillMaxWidth().clickable { set(if (c in on) on - c else on + c) }.padding(horizontal = 18.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(c.label, Modifier.weight(1f), fontSize = 15.sp)
                                Switch(c in on, { v -> set(if (v) on + c else on - c) })
                            }
                        }
                    }
                }
            }
        }
    }
}
