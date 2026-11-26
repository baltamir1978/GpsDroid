package es.altamirano.gps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.altamirano.common.ui.glass
import es.altamirano.gps.core.Direction
import es.altamirano.gps.core.Geo
import es.altamirano.gps.core.FollowStatus
import es.altamirano.gps.data.FollowState
import es.altamirano.gps.data.Following
import es.altamirano.gps.data.Gps

/**
 * Arriba en el mapa, mientras se sigue un track: cuánto queda, el desnivel y la llegada; fuera del
 * track, en otro color, a qué distancia está y una flecha (con la brújula) hacia el punto más cercano.
 */
@Composable
fun FollowPill(st: FollowState, onShow: () -> Unit, onClose: () -> Unit) {
    val fix = st.fix
    val location by Gps.location.collectAsState()
    val away = fix != null && fix.status in setOf(FollowStatus.OFF_TRACK, FollowStatus.APPROACHING)
    val compass by rememberCompass(away)
    val heading = location?.takeIf { Gps.isFresh(it) }?.travelHeading() ?: compass
    val tint = when (fix?.status) {
        FollowStatus.OFF_TRACK -> MapColors.offTrack
        FollowStatus.ARRIVED -> MapColors.waypoint
        else -> MapColors.follow
    }
    Row(Modifier.glass(RoundedCornerShape(24.dp), tint = tint, alpha = 0.9f).clickable(onClick = onShow)
        .padding(start = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            when {
                fix?.status == FollowStatus.ARRIVED -> Icon(Icons.Filled.Flag, null, Modifier.size(22.dp), tint = tint)
                away -> Icon(Icons.Filled.Navigation, null, Modifier.size(24.dp).rotate((fix.bearingToTrack - (heading ?: 0f)).toFloat()), tint = tint)
                else -> Icon(Icons.Filled.Route, null, Modifier.size(22.dp), tint = tint)
            }
        }
        Column(Modifier.weight(1f, fill = false).padding(start = 10.dp)) {
            Text(when (fix?.status) {
                FollowStatus.OFF_TRACK -> "Fuera del track"
                FollowStatus.APPROACHING -> "Hacia «${st.name}»"
                else -> st.name
            }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // El título ya dice si estás fuera: aquí, sólo cuánto y hacia dónde volver.
            val detail = if (away) "${Geo.formatDistance(fix.match.offM)} al ${Direction.cardinal(fix.bearingToTrack)}" +
                (heading?.let { " · ${Direction.relative(fix.bearingToTrack, it.toDouble())}" } ?: "")
                else Following.summary(st)
            Text(detail, fontSize = 12.sp, color = Color.White.copy(alpha = 0.92f))
        }
        IconButton(onClick = onClose, Modifier.size(40.dp)) { Icon(Icons.Filled.Close, "Dejar de seguir", Modifier.size(18.dp), tint = Color.White) }
    }
}
