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
 * Mantiene al día los cuadros de datos de rutas: cada [RouteTiles.REFRESH_DAYS] días, con wifi (o
 * cualquier red sin coste) y batería, mira en brouter.de si los cuadros del móvil han cambiado y baja
 * los nuevos. Lo pidió Bruno: que lo que se corrige en OpenStreetMap llegue solo, sin tener que borrar
 * y volver a bajar los cuadros en Offline. Con «Usar la red» apagado no hace nada.
 *
 * WorkManager (de AndroidX, sin servicios de Google) porque un cuadro son ~90 MB: tiene que poder
 * bajarse con la app cerrada y esperar al wifi. Si una ejecución se corta, la siguiente sigue donde se
 * quedó (la descarga se reanuda).
 */
class RouteTilesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.online) return Result.success()
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
        private const val PERIODIC = "route-tiles"
        private const val NOW = "route-tiles-now"

        /** Al abrir la app: la comprobación periódica (si ya estaba, se deja como está). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RouteTilesWorker>(RouteTiles.REFRESH_DAYS, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** «Buscar datos nuevos» en Offline: ya, con la red que haya (lo pide el usuario). */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<RouteTilesWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }

        /** Si hay una comprobación en marcha o esperando red (para el botón de Offline). */
        fun runningFlow(context: Context) =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NOW)
    }
}
