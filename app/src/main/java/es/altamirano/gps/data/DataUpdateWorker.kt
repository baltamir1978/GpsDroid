package es.altamirano.gps.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import es.altamirano.gps.core.RouteTiles
import java.util.concurrent.TimeUnit

/**
 * Mantiene al día los datos de la app con wifi (o cualquier red sin coste) y batería, una vez al día:
 * la lista de radares (si tiene más de un día), la de cámaras de semáforo (más de una semana) y los
 * cuadros de datos de rutas (cada [RouteTiles.REFRESH_DAYS] días mira en brouter.de si han cambiado y
 * baja los nuevos). Lo pidió Bruno: que lo que se corrige en OpenStreetMap llegue solo, sin borrar y
 * volver a bajar nada. Con «Usar la red» apagado no hace nada. Hasta la 0.19.0 los radares sólo se
 * renovaban al empezar una ruta en coche (y aún lo hacen, con datos móviles si no hay wifi).
 *
 * WorkManager (de AndroidX, sin servicios de Google) porque un cuadro son ~90 MB: tiene que poder
 * bajarse con la app cerrada y esperar al wifi. Si una ejecución se corta, la siguiente sigue donde se
 * quedó (la descarga se reanuda).
 */
class DataUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.online) return Result.success()
        val onlyRoutes = inputData.getBoolean(KEY_ONLY_ROUTES, false)
        if (!onlyRoutes) {
            // Si fallan se sigue: mañana se vuelve a probar.
            if (Radars.redLightsOld(applicationContext)) Radars.updateRedLights(applicationContext)?.let { Log.w("GpsDroid", it) }
            if (Radars.radarsOld(applicationContext)) Radars.updateRadars(applicationContext)?.let { Log.w("GpsDroid", it) }
        }
        val due = System.currentTimeMillis() - prefs.routeTilesChecked >= TimeUnit.DAYS.toMillis(RouteTiles.REFRESH_DAYS)
        if (!onlyRoutes && !due) return Result.success()
        val (updated, error) = Routing.updateOutdated(applicationContext)
        if (updated.isNotEmpty()) Log.i("GpsDroid", "Cuadros de rutas actualizados: $updated")
        if (error != null) {
            Log.w("GpsDroid", "Cuadros de rutas: $error")
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
        prefs.routeTilesChecked = System.currentTimeMillis()
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "data-update"
        /** El trabajo semanal de la 0.19.0, que ahora hace este. */
        private const val OLD_PERIODIC = "route-tiles"
        private const val ROUTES_NOW = "route-tiles-now"
        private const val KEY_ONLY_ROUTES = "only_routes"

        /** Al abrir la app: la comprobación diaria (si ya estaba, se deja como está). */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(OLD_PERIODIC)
            val request = PeriodicWorkRequestBuilder<DataUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build())
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Bajar ya los cuadros de rutas nuevos (botón de Actualizaciones), con la red que haya. */
        fun updateRoutesNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<DataUpdateWorker>()
                .setInputData(androidx.work.workDataOf(KEY_ONLY_ROUTES to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ROUTES_NOW, ExistingWorkPolicy.KEEP, request)
        }

        /** El trabajo de [updateRoutesNow], para saber si está en marcha. */
        fun routesNowFlow(context: Context) =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ROUTES_NOW)
    }
}
