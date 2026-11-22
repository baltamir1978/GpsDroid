package es.altamirano.gps

import android.app.Application
import es.altamirano.gps.data.Gps
import es.altamirano.gps.data.Tiles
import es.altamirano.gps.data.Tracks

class GpsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Gps.init(this)
        Tracks.init(this)
        Tiles.init(this)
    }
}
