package es.altamirano.gps

import android.app.Application
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.RouteTilesWorker
import es.altamirano.gps.data.Tiles
import es.altamirano.gps.data.Tracks
import es.altamirano.gps.data.Waypoints

class GpsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Gps.init(this)
        Tracks.init(this)
        Tiles.init(this)
        Waypoints.init(this)
        RouteTilesWorker.schedule(this)
    }
}
