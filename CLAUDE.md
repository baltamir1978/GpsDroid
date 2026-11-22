# GpsDroid — contexto del repositorio

Versión para Android de **ZeroGPS** (`../ZeroGPS`, la app del Cap LoRa-1262 del Cardputer Zero),
escrita **desde cero en Kotlin + Jetpack Compose**. ZeroGPS hace de especificación (el `CLAUDE.md`
de ZeroGPS explica las capas del IGN, los tamaños de tesela y por qué hay tope de descarga). En el
móvil se llama **IGN GPS**, como ZeroGPS en el lanzador del Cardputer. Lo común a las apps de
Android (firma, `versionCode`, secretos, distribución) está en el `CLAUDE.md` de la carpeta de
proyectos; aquí va lo propio de esta.

## Cómo está hecha

- **`core/`** (Kotlin puro, `CoreTest`): traducción de los módulos de ZeroGPS. `MapTiles` (Web
  Mercator), `MapLayers` (las tres capas, URL WMTS, bytes medios por tesela), `OfflineZone`
  (zonas ya hechas, plan de descarga, tope, formatos con coma decimal), `Gpx` (lee GPX de otras
  apps: comillas simples, varios segmentos, rutas) y `TrackRecorder` (+ `Geo`: distancia,
  desnivel con umbral de 3 m). El grabador, a diferencia de ZeroGPS, descarta posiciones con más
  de 30 m de error (el móvil sí lo sabe).
- **`data/Gps`**: `LocationManager.GPS_PROVIDER` + `GnssStatus.Callback`, **por demanda**
  (`acquire`/`release` por dueño: mapa y satélites mientras se ven —`LifecycleStartEffect`, así
  que se apaga con la app en segundo plano— y el servicio mientras graba). Ni red ni Google.
- **`data/Recording`**: `TrackService`, servicio en primer plano tipo `location` (sin
  `ACCESS_BACKGROUND_LOCATION`: se arranca con la app delante). GPX en `files/tracks`, reescrito
  cada 60 s con temporal + rename y al parar. Nombre `yyyy-MM-dd_HHmmss.gpx`.
- **`data/Tiles`**: osmdroid 6.1.20 (el mismo que TiempoDroid). `IgnTileSource` sobreescribe la
  URL entera porque el IGN va por parámetros WMTS (`tilerow` = y). Caché SQLite de osmdroid en
  **`noBackupFilesDir`** (en `cacheDir` Android podría borrar las zonas, y no deben ir a la copia
  de seguridad); recorte de osmdroid subido a 64 GB (de serie lo deja en 500 MB y se comería las
  descargas); caducidad forzada a un año. **Descarga propia** (no `CacheManager`): 3 peticiones a
  la vez, salta lo guardado y sin caducar (repetir = reanudar), comprueba `FF D8`, para con poco
  espacio o si un zoom entero falla.
- **Mapa** (`ui/MapScreen`): `MapView` en `AndroidView`, `isTilesScaledToDpi` (por eso el zoom
  máximo es el de la capa: ya se ve ampliado), overlay propio para la posición, `Polyline` para los
  tracks, escala y atribución. Seguir se quita al arrastrar (`ACTION_MOVE`).
- Interfaz sólo en español (como ZeroGPS). Material 3 Expressive con los colores del sistema.

## Estado

**0.2.0 (22-nov-2026): aspecto Material 3 Expressive.** Lo pidió Bruno («guías de diseño de
Android 17, algo más colorido y translúcido»). material3 1.5.0-rc01 (como VideoDroid) con
`MaterialExpressiveTheme` + `MotionScheme.expressive()` y colores del sistema. El mapa ocupa toda
la pantalla y la barra de navegación flota encima en «cristal» (`Modifier.glass` en `ui/Theme.kt`:
superficie translúcida tintada + filo claro + sombra; no hay desenfoque de lo de detrás). En el
mapa: píldora de posición arriba (indicador de carga mientras busca), píldora roja que late al
grabar, `HorizontalFloatingToolbar` abajo (capa, red, seguir, **nivel de zoom** —lo pidió Bruno—
y el botón de grabar como FAB) y la atribución del IGN encima; la escala pasa arriba a la
derecha. Las demás pestañas: `Header` con degradado primario → terciario, tarjetas tonales de
esquinas grandes, cielo de noche con halo en los satélites usados, leyenda en píldoras de color,
tarjeta de grabación en degradado rojo, filas de tracks con icono de color por turnos, progreso
ondulado (`LinearWavyProgressIndicator`). Además (0.1.3) cada pestaña recuerda su estado al
cambiar (`rememberSaveableStateHolder`). Pantallas que no llegan bajo la barra: `LocalBottomBar`.
**Visto en el móvil y aprobado por Bruno (22-nov-2026)** («me gusta»).

**0.1.2 (22-nov-2026): las descargas ya no «fallan».** `SqlTileWriter.saveFile` de osmdroid
6.1.20 **devuelve siempre `false`** aunque guarde (su bytecode acaba en `iconst_0; ireturn`): cada
tesela bajada contaba como fallo y Bruno veía fallos sin parar. Ahora se comprueba con `exists()`.
El motivo del último fallo va al registro (`adb logcat -s GpsDroid`) y al mensaje final.
Comprobado: 2.391 teselas (1 km, los tres mapas) en el móvil y casi mil en el emulador, sin fallos.

**0.1.1 (22-nov-2026):** en Satélites y Offline el contenido se metía bajo la barra de estado al
desplazarse (`statusBarsPadding` iba dentro del scroll).

**0.1.0 (22-nov-2026): primera versión.** Mapa con las tres capas del IGN, satélites, grabar /
abrir / compartir / importar / borrar tracks y descarga de zonas. 8 pruebas en verde y lint sin
errores. Clave nueva `gps-release` (copiada a `~/Documentos/ClavesAndroid`). Instalada en el
móvil por adb. **Visto en el móvil (22-nov-2026)**: el mapa pinta las teselas del IGN (las tres
capas), escala y atribución; descarga de zonas. **Sin probar en el móvil**: grabar un track
(y que siga con la pantalla apagada), abrir/compartir/importar, la pantalla de satélites con fix.


Pendiente / ideas: comprobar en el móvil que se pintan las teselas del IGN, que la grabación sigue
con la pantalla apagada (GrapheneOS no mata servicios en primer plano, pero la optimización de
batería puede retrasar el GPS) y una descarga real; la capa «híbrida» (`IGNBaseOrto`, PNG con
transparencia encima de la ortofoto: aquí no cuesta nada, osmdroid pinta PNG); estadísticas de un
track (perfil de altitud).
