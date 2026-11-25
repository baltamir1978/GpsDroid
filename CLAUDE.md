# GpsDroid — contexto del repositorio

Versión para Android de **ZeroGPS** (`../ZeroGPS`, la app del Cap LoRa-1262 del Cardputer Zero),
escrita **desde cero en Kotlin + Jetpack Compose**. ZeroGPS hace de especificación (el `CLAUDE.md`
de ZeroGPS explica las capas del IGN, los tamaños de tesela y por qué hay tope de descarga). En el
móvil se llama **IGN GPS**, como ZeroGPS en el lanzador del Cardputer. Lo común a las apps de
Android (firma, `versionCode`, secretos, distribución) está en el `CLAUDE.md` de la carpeta de
proyectos; aquí va lo propio de esta.

## Cómo está hecha

- **`core/`** (Kotlin puro, `CoreTest`): traducción de los módulos de ZeroGPS. `MapTiles` (Web
  Mercator), `MapLayers` (`MapLayer`: las series de teselas del IGN, rótulos incluidos, URL WMTS, bytes medios
  por tesela; `MapStyle`: lo que se elige en el mapa, fondo + capa encima), `TrackStats` (la ficha de un track), `OfflineZone`
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
  espacio o si un zoom entero falla. En memoria, margen de 96 teselas sobre las visibles (`cacheMapTileOvershoot`) y colas de 120.
- **Mapa** (`ui/MapScreen`): `MapView` en `AndroidView`, `isTilesScaledToDpi` (por eso el zoom
  máximo es el de la capa: ya se ve ampliado), escala y atribución. Lo de encima de las teselas
  (rótulos, tracks, puntos, posición) está en `ui/MapScene`, compartido con el coche (`car/`). Seguir se quita al arrastrar (`ACTION_MOVE`). La rueda del ratón hace zoom (`WheelZoom`: estira la vista y aplica el zoom al parar).
- Interfaz sólo en español (como ZeroGPS). Material 3 Expressive con los colores del sistema.

## Estado

**0.4.4 (25-nov-2026): arrastrar sin tantos saltos.** Bruno, tras la 0.4.3: «para el zoom está
bien, para el movimiento sigue igual». osmdroid dimensiona la caché de teselas en memoria a las
que se ven (`TilesOverlay` → `ensureCapacity(visibles + cacheTileOvershoot)`, y el margen es 0):
al arrastrar, lo que salía por un borde se tiraba y al volver se releía con hueco gris. Ahora
`cacheMapTileOvershoot = 96` (unos 25 MB más por capa) y las colas de lectura y descarga a 120
(de serie 40, cortas en una ventana grande de escritorio con mapa y rótulos). Lo nuevo que entra
por el borde sin estar en el disco sigue tardando lo que tarde el IGN. En el emulador abre y
arrastra sin cierres, pero sin ventana no pinta fotogramas (`gfxinfo` da 0) y los tirones no se
pueden medir ahí. **Probado por Bruno (25-nov-2026: «está probado y va bien»).**

**0.4.3 (25-nov-2026): la rueda ya no da saltos.** Bruno: «funciona perfecto, pero en movimientos
rápidos o largos hay bastantes saltos al recargar las teselas». Aplicar el zoom golpe a golpe
hacía que cada nivel entero cruzado pidiera sus teselas (`MapView.setZoomLevel` → `rescaleCache`
y peticiones nuevas): la cola se llenaba de niveles intermedios y los rótulos, que van en otro
proveedor sin `rescaleCache`, se borraban y volvían. Ahora (`WheelZoom` en `ui/MapScreen`)
mientras se gira sólo se estira la vista (`scaleX`/`scaleY` con pivote en el cursor del primer
golpe, como osmdroid al pellizcar) y a los 200 ms sin golpes se aplica el zoom de una vez; tocar
el mapa lo aplica en el acto. Probado en el emulador con la release: 12 golpes z6 → z12, en una
esquina sin seguir −2, tres golpes y toque +1,5, veinte golpes al máximo (z17+). **Probado en el
móvil por Bruno (25-nov-2026)**: el zoom, bien.

**0.4.2 (25-nov-2026): zoom con la rueda del ratón.** Bruno: «que la rueda del ratón en modo
escritorio cambie el zoom». osmdroid 6.1.20 no atiende `ACTION_SCROLL`: un
`setOnGenericMotionListener` en el `MapView` sube o baja medio nivel por golpe (los touchpads
mandan fracciones) y deja quieto el punto bajo el cursor (`MapTiles.zoomAnchoredCenter`, con su
prueba); siguiendo la posición, por el centro. Sin animar: `zoomToFixing` descarta lo que llega
mientras anima (`mIsAnimating`) y la rueda iría a saltos. Probado en el emulador con la release
(`adb shell input mouse scroll x y --axis VSCROLL,1`: z6 → z7 → z5, el mínimo). **Probado en el
móvil por Bruno (25-nov-2026)**: «funciona perfecto», salvo los saltos que arregla la 0.4.3.

**0.4.1 (24-nov-2026): textos legibles en modo oscuro.** Bruno: «en la pestaña de tracks sobre
todo no se lee prácticamente nada en modo oscuro». La paleta estaba bien; lo que faltaba era una
`Surface` (o `Scaffold`) en la raíz: sin ella `LocalContentColor` se queda en el negro de serie de
Compose y todo `Text` sin color propio (nombre y datos de cada track, «Puntos», la tarjeta de la
batería…) salía negro sobre el fondo oscuro. Ahora `MainActivity` envuelve la app en una `Surface`
con `surface`, que pone `onSurface`. **Probado por Bruno (25-nov-2026: «está probado y va bien»).**

**0.4.0 (24-nov-2026): Android Auto y puntos.** Lo pidió Bruno («android auto a la app de mapas
ign» y «poder guardar puntos en el mapa y que me diga dirección y distancia»).
- **Puntos** (`core/Waypoints.kt`: `Waypoint`, `Direction`, `WaypointGpx`; `data/Waypoints`;
  `ui/WaypointsUi.kt`): todos en un GPX, `files/puntos/puntos.gpx` (`<wpt>`, se reescribe entero
  con temporal + rename; se comparte por el `FileProvider`). Los `id` sólo valen en la sesión; el
  **destino** se recuerda en `Prefs` por sus coordenadas. Guardar: pulsación larga en el mapa o el
  botón verde (tu posición, con altitud); nombre «Punto N» ya escrito y seleccionado. Tocar una
  chincheta abre su ficha (distancia y rumbo, ir hacia aquí / dejar de ir, renombrar, abrir en otra
  app con `geo:`, borrar). Con destino: línea discontinua naranja desde tu posición y una píldora
  arriba con una flecha que gira con la **brújula** (`ui/Compass.kt`, `TYPE_ROTATION_VECTOR`, sólo
  mientras se ve; si vas a más de 1,5 m/s manda el rumbo del GPS), «1,2 km al NE · a tu derecha»
  (ocho sectores, `Direction.relative`) y «Has llegado» a menos de 15 m. En Tracks, sección
  «Puntos» (los más cerca primero; enciende el GPS mientras se ve si hay puntos). Importar un GPX
  añade sus `<wpt>` (un GPX sólo con puntos ya no da error).
- **Android Auto** (`car/`, `androidx.car.app:app` 1.7.0, la última estable): categoría
  **NAVIGATION**, la única que puede pintar un mapa propio (permisos `NAVIGATION_TEMPLATES` y
  `ACCESS_SURFACE`, `minCarApiLevel` 2). `CarMapRenderer` crea un `VirtualDisplay` sobre la
  `Surface` del coche y un `Presentation` con un `MapView` normal: mismas teselas (offline
  incluidas), estilos y overlays que el móvil, que para eso pasaron a `ui/MapScene.kt`. El centro
  va al centro del área visible (`setMapCenterOffset`); de noche (`isDarkMode`) las teselas se
  oscurecen. `NavigationTemplate`: tira de acciones (capa, lista de puntos, guardar punto aquí,
  grabar/parar) y tira del mapa (mover, +, −, seguir); arrastrar y pellizcar con `onScroll`/
  `onScale`. La tarjeta (`MessageInfo`) sale con destino o grabando: flecha (mapa con el norte
  arriba; en marcha, relativa a tu rumbo), distancia redondeada a 10 m y a qué mano queda; la
  plantilla se rehace como mucho cada 2 s. `PointsCarScreen`: lista por distancia, tocar = destino.
  `HostValidator`: cualquiera en debug, en release la lista de la librería. Grabar desde el coche
  puede no dejarse (servicio en primer plano sin la app a la vista): `TrackService` ya no se cae si
  `startForeground` falla, y el coche lo dice.
- 15 pruebas (3 nuevas: rumbo y punto cardinal, a qué mano, GPX de puntos) y lint sin errores.
  **Probado en el emulador con la release**: abre; guardar con el botón y con pulsación larga;
  la lista de Tracks por distancia; la ficha; «Ir hacia aquí» y la píldora («208,9 km al N · de
  frente»); el `CarAppService` queda registrado con la categoría NAVIGATION. **Probado por Bruno (25-nov-2026: «está probado y va bien»).**

**0.3.0 (24-nov-2026): híbrido, ficha de los tracks y batería.** Lo pidió Bruno («vamos a acabar
con gpsdroid»): las ideas que quedaban.
- **Mapa híbrido**: la ortofoto con los rótulos de `IGNBaseOrto` encima (`ign-base`, **PNG** con
  transparencia, hasta z19; comprobado en sus GetCapabilities y en Madrid centro). Las capas pasan
  a ser dos cosas: `MapLayer` (cada serie de teselas, con su caché: topo, pnoa, base y `rotulos`) y
  `MapStyle` (lo que se elige: Topográfico, Satélite, Híbrido, IGN Base). El híbrido reutiliza las
  teselas de la ortofoto. En el mapa los rótulos son un `TilesOverlay` propio con
  `MapTileProviderBasic`, fondo de carga transparente (de serie pinta una cuadrícula gris que
  taparía la foto), debajo de tracks y posición, y obedece «Usar la red». Si no está en el mapa,
  `MapView.onDetach` no lo suelta: se suelta a mano. Las claves de `MapStyle` son las de las capas
  de la 0.2 (lo guardado en `Prefs` sigue valiendo). Descargas: se elige estilo (o «Todos») y se
  bajan sus capas sin repetir (`MapStyle.layersOf`); la firma se comprueba según el formato
  (`Tiles.looksLikeImage`: JPEG `FF D8`, PNG `89 50 4E 47`). «Todos» a 5 km de z5 al máximo son
  ~93.900 teselas: sigue cabiendo en el tope de 100.000. Rótulos medidos: 20 KB a z12, casi nada
  de cerca en el monte (662 bytes vacía), ~10 KB en ciudad a z17.
- **Ficha de un track** (`core/TrackStats`, `ui/TrackDetail.kt`): al tocar un track sube una hoja
  con distancia, tiempo total y **en movimiento** (tramos a ≥ 0,5 m/s: el grabador no guarda puntos
  estando quieto, así que una parada es un salto de tiempo con poca distancia), velocidad media y
  ritmo en movimiento, subida y bajada (histéresis de 3 m), altitud máxima y mínima y el **perfil
  de altitud** en Canvas (rejilla con pasos redondos, rango mínimo de 50 m para que un llano no
  parezca una montaña; tocar o arrastrar marca distancia y altitud). El perfil se reduce a 400
  puntos guardando en cada tramo el más alto y el más bajo (no se pierden cimas). Lo que el GPX no
  trae (horas en una ruta, altitudes) no sale. «Ver en el mapa» está en la hoja y en el menú ⋮.
- **Optimización de batería**: si Android la aplica a la app, en Tracks sale una tarjeta para
  quitarla (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, permiso
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` con `tools:ignore="BatteryLife"`: lo restringe Google
  Play, y la app no va a Play). Con la pantalla apagada, la optimización puede espaciar las
  posiciones aunque el servicio en primer plano siga vivo.
- 12 pruebas (4 nuevas: estilos, ficha con parada, desnivel con ruido, perfil reducido) y lint sin
  errores. **Probado en el emulador con la release**: abre, el híbrido baja rótulos, una descarga
  del híbrido de 1 km (3.432 teselas) acabó a la tercera sin fallos (los fallos de las dos primeras
  eran `HTTP 502` del IGN, pasajeros; repetir reanuda), grabar un track y su ficha (423 m, ↑ 95 m,
  como lo mandado). Ojo al probar con `adb emu geo fix`: las latitudes con muchos decimales
  (`40.785799999999995`) la consola las lee mal y el track sale en zigzag; redondear a 6.
  **Probada en el móvil por Bruno (24-nov-2026)**: «funciona perfectamente». APK en `~/Documentos/Software`.

**0.2.1 (22-nov-2026): paleta verde fija.** Con los colores del fondo de pantalla (Material You)
el móvil de Bruno daba una paleta gris y lo seleccionado (chips de Offline, interruptores) no se
distinguía: quiere verde clarito. Ahora verde de monte + azul de agua fijos, y lo seleccionado con
`withBrandSelection`. Las piezas de diseño (`glass`, `Header`, `TonalCard`, `AppTheme`) pasaron a
`droid-common` para que las usen todas las apps. **Probado por Bruno (25-nov-2026: «está probado y va bien»).**

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
errores. Clave nueva `gps-release` (copiada a `~/Documentos/Software/ClavesAndroid`). Instalada en el
móvil por adb. **Visto en el móvil (22-nov-2026)**: el mapa pinta las teselas del IGN (las tres
capas), escala y atribución; descarga de zonas. Lo demás (grabar, importar, satélites) lo da por bueno Bruno el 25-nov-2026.


Ideas, si hicieran falta: pintar en el mapa el punto marcado en el perfil; en el coche, mapa orientado hacia donde vas.
