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

**0.7.0 (26-nov-2026): BRouter dentro de la app.** Bruno, con la app BRouter aparte: «me da todo
tipo de problemas». Ahora el motor va dentro (`brouter-core` 1.7.9 de **JitPack**, sólo para el grupo
`com.github.abrensch.brouter` en `settings.gradle.kts`; la 1.7.10 no la tiene JitPack; licencia MIT
en `assets/brouter/LICENSE.txt`) y ya no hacen falta ni la otra app, ni el AIDL, ni `<queries>`.
- `data/Routing`: `RoutingContext` con el perfil (`localFunction`), `RoutingParamCollector`
  (`timode=2`, `heading`), `RoutingEngine(null, null, segmentos, puntos, rc).doRun(60 s)` y
  `FormatJson` → el mismo `BRouterJson` de antes. Cancelar la corrutina llama a `terminate()`.
  Perfiles en `assets/brouter` (`hiking-mountain`, `trekking`, `car-vario` —`car-fast` no está en
  `misc/profiles2`, la app BRouter lo genera— y `lookups.dat`, que el motor busca junto al perfil);
  se copian a `noBackupFilesDir/brouter/profiles2` (sube `PROFILES_VERSION` si cambian).
- **Datos**: cuadros de 5° × 5° (`core/RouteTiles`: `W5_N40` = Madrid; se nombran por la esquina
  suroeste) de `brouter.de/brouter/segments4/`, en `noBackupFilesDir/brouter/segments4`. Al pedir una
  ruta, si falta alguno de los que cubren origen y destino (con 0,1° de margen) la app pregunta
  diciendo lo que pesa (HEAD) y lo baja a `.rd5.part` con `Range` para reanudar, renombrando al
  acabar. En Offline, sección «Datos para rutas»: los cuadros bajados con su tamaño y fecha, y
  borrarlos. APK: +300 KB.
- `Gps` enciende y apaga el GPS en un hilo propio (`gps-control`): son llamadas al sistema y, en el
  emulador con el GPS simulado atascado, la app daba «no responde» al cambiar de pestaña.
- Fuera el aviso «Sin voz» (Bruno: «no necesito voz»); si hay motor de voz, habla.
- 31 pruebas y lint sin errores. **Probado en el emulador sin BRouter instalado**: «Ruta hasta
  aquí» → «Faltan los datos de rutas (W5_N40, 88 MB)» → bajado desde la app → ruta a pie de Sol a
  Plaza de España igual que con la app BRouter (1,4 km), en coche (empieza en la calle más cercana
  con coches, a 140 m: «Hacia el inicio de la ruta») y en bici (1,6 km), ~5 s cada una. **Sin probar
  en el móvil.**

**0.6.0 (26-nov-2026): navegación con rutas de BRouter.** Lo pidió Bruno («vete empezando con la
navegación»).
- `core/Route.kt`: `TravelMode` (a pie `hiking-mountain`, bici `trekking`, coche `car-fast`, con
  sus distancias de aviso), `Maneuver` (códigos de `VoiceHint` de BRouter, frases en español),
  `BRouterJson` (lee el GeoJSON: coordenadas `[lon, lat, ele]` y `voicehints`
  `[índice, código, salida, distancia, ángulo]`; con `timode=2` salir por la izquierda/derecha llega
  como 17/18) y `RouteGuide` (siguiente maniobra y qué decir: cada una como mucho en tres etapas
  —lejos, cerca, ya—, saltando a la más cercana; dos seguidas a menos de 60 m se dicen juntas).
- `data/Routing.kt`: se conecta al servicio exportado de BRouter
  (`btools.routingapp.BRouterService`, `IBRouterService.aidl` copiado en `src/main/aidl`, licencia
  MIT; `aidl = true` en Gradle; `<queries>` en el manifiesto). Pide `profile` y, si BRouter no lo
  tiene, su modo de servicio (`v` + `fast`); respuesta comprimida (`z64` + gzip en base64) por el
  límite del Binder. Los errores se traducen (faltan datos de la zona, BRouter sin preparar…).
- `Following` sirve para las dos cosas: un track guardado (`file`) o una ruta (`nav: Navigation`,
  con destino y modo). En ruta, el servicio dice cada maniobra con `TextToSpeech` (es-ES, audio de
  guía de navegación con foco transitorio que baja la música) y, fuera de la ruta, **recalcula**
  desde donde estés (como mucho cada 20 s; si falla, sigue con la de antes). Sin motor de voz
  (GrapheneOS no trae ninguno) lo dice en la píldora.
- Interfaz: «Ruta hasta aquí» en la ficha de un punto (elige a pie / bici / coche, recuerda el
  último; sin BRouter explica cómo instalarlo: F-Droid o Play, abrirlo una vez y bajar los cuadros
  de la zona en su «Download Manager»). En el mapa, la píldora enseña la maniobra en grande (icono de
  Material, distancia, qué hacer y lo que queda). En Android Auto, `RoutingInfo` con `Step` y
  `Maneuver` (tipos de la Car App Library; rotondas CCW con su salida) en vez de la tarjeta.
- «Sigue recto» (código 1) no se anuncia: BRouter lo pone en cada cruce que se pasa de largo.
- Sin motor de voz, `TextToSpeech` avisa del error **dentro del constructor** (antes de que la
  variable tenga valor): el «sin voz» se marca después, con un `post` al hilo principal (cazado en
  el emulador, que no trae motor).
- 30 pruebas y lint sin errores. **Probado en el emulador con BRouter 1.7.10 de F-Droid** (carpeta
  por defecto `Android/media/btools.routingapp`; el cuadro `W5_N40.rd5`, 92 MB, de
  `brouter.de/brouter/segments4/`, copiado con `adb push` a `brouter/segments4/`): ruta a pie de
  Sol a Plaza de España (1,4 km), recálculo al salirse 300 m, el paseo entero con la geometría de
  la misma ruta pedida al servidor público de BRouter (maniobras en orden, «luego…» en los giros
  seguidos, hora de llegada, sin salirse) y «Has llegado». Perfiles de serie en BRouter 1.7.10:
  `hiking-mountain`, `trekking` y `car-fast` (no `car-vario`). **Sin probar en el móvil**: la voz
  (hace falta un motor: RHVoice o eSpeak NG), bici y coche, y Android Auto.
- Falta: ruta desde una pulsación larga en el mapa (no sólo puntos guardados) y `NavigationManager`
  del coche (`navigationStarted`, para el cuadro de instrumentos).

**0.5.0 (26-nov-2026): seguir un track y altitud de los puntos.** Lo pidió Bruno: navegación
«nivel 1» (guiarse por un GPX, sin calcular rutas) y, en la lista de puntos de Tracks, la altitud.
- **Seguir un track** (`core/TrackFollow.kt`: `FollowRoute`, `TrackFollower`; `data/Following.kt`:
  `Following` + `FollowService`; `ui/FollowUi.kt`). Desde la ficha del track («Seguir» / «Al revés»)
  o su menú ⋮. `FollowRoute` guarda distancia, subida y bajada acumuladas (misma histéresis de 3 m
  que la ficha) y busca el punto más cercano por proyección en cada tramo (plano local). Con lo que
  llevabas (`hintM`) sólo mira de 300 m atrás a 1,5 km adelante y elige con un coste: distancia +
  castigo por ir hacia atrás (más de 30 m) + 0,05 × lo que se aleja de donde ibas; así un ida y
  vuelta no salta al otro tramo en el giro ni a la vuelta. Sin pista (o a más de 60 m), el track
  entero: el más cercano, salvo que otra pasada **separada** (más de 300 m de track) esté casi igual
  de cerca (20 m), y entonces la anterior (un circular al empezar). Cuidado al tocarlo: con un
  desempate ingenuo, en un tramo recto los puntos vecinos «empatan» y la flecha de volver apuntaba
  en diagonal (lo cazó la prueba en el emulador; está en `FollowTest`).
  `TrackFollower`: estados *hacia el track* (aún no has llegado: no avisa), *en el track*, *fuera*
  (más de 40 m —o la precisión, si es peor— en dos posiciones seguidas; volver: menos de 25 m) y
  *llegado* (a 30 m del final con el track andado; en un circular no salta al empezar). Posiciones
  con más de 50 m de error no cuentan. La hora de llegada sale del ritmo desde que entraste en el
  track (a partir de 2 min y 200 m).
  `FollowService`: servicio en primer plano tipo `location` (como `TrackService`, independiente:
  se puede grabar y seguir a la vez), notificación fija con lo que queda y canal aparte de **avisos**
  (`IMPORTANCE_HIGH`, vibración) para salirse y llegar; al volver, se quita el aviso y un toque corto
  (`VIBRATE`). En el mapa: píldora (azul; roja fuera, con flecha por la brújula hacia el track;
  verde al llegar), track en azul intenso con lo andado claro y línea discontinua hasta el track si
  estás fuera. En Android Auto, tarjeta y track pintado (un destino manda sobre el track).
- **Altitud de los puntos**: en la lista de Tracks y en la ficha, «Altitud 1.245 m · 230 m más
  arriba» (menos de 10 m: «a tu altura»; `core/Altitude`). Los guardados con pulsación larga (y los
  importados sin `<ele>`) la piden a la **API de elevación de Open-Meteo** (Copernicus GLO-90, sin
  clave, sobre el nivel del mar; `data/Elevation`), sólo con «Usar la red»; si falla, se reintenta
  al abrir la app o guardar otro punto.
- **Altitud sobre el nivel del mar en toda la app**: el GPS da altura sobre el elipsoide (en
  España ~50 m más). `Gps` añade la del geoide con `AltitudeConverter` (Android 14+; la primera
  vez lee el modelo del disco, en otro hilo; en 15+ `tryAdd…` en el acto) y `Location.altitudeMsl()`
  la usa en grabar, guardar puntos, la píldora y Satélites (que enseña también la del elipsoide).
  **Los tracks grabados desde esta versión salen ~50 m más bajos que los de antes**: los de antes
  estaban mal. En el emulador, 1.000 m de `geo fix` → 946 m.
- 24 pruebas (6 de seguir: dónde caes y cuánto queda, salirse y volver una vez, lejos al empezar,
  ida y vuelta, circular, al revés y altitud relativa; 1 de la respuesta de Open-Meteo) y lint sin
  errores. **Probado en el emulador con la release**: grabar 600 m, «Seguir» desde 300 m antes
  («Hacia… · 288 m al N · de frente»), en el track («Quedan 300 m · ↑ 30 m ↓ 0 m»), fuera («95 m al
  O · a tu izquierda» y la notificación «Te has salido del track»), de vuelta y «Has llegado» con su
  aviso; puntos con altitud. El emulador se cuelga a veces en el servicio de ubicación del propio
  sistema (`GnssNative.stop` bloqueado en `system_server`: la app da ANR esperando en
  `unregisterGnssStatusCallback`); no es de la app, se reinicia con `-wipe-data`. **Sin probar en el
  móvil**: todo lo anterior con GPS de verdad, los avisos con la pantalla apagada, la hora de llegada
  y Android Auto.

**0.4.8 (26-nov-2026): copia de todo.** Bruno no quiere perder los tracks del móvil, que viven en
`filesDir` (se borran si se desinstala la app). En Tracks, junto a Grabar e Importar (en un
`FlowRow`: no caben los tres en una fila), «Copia de todo» guarda un ZIP donde elija el usuario
(`CreateDocument`, nombre `IGN-GPS-copia-<fecha>.zip`) con los GPX en `tracks/` y
`puntos/puntos.gpx` (`data/Backup`; los `.tmp` a medio escribir quedan fuera). Sólo sale si hay
algo que copiar y no se está grabando. El ZIP se escribe en `Backup.writeZip`, sin Android, con su
prueba (`BackupTest`; 17 pruebas). Si el sistema no tiene selector de archivos (`DocumentsUI`; la
imagen ATD del emulador no lo trae) avisa en vez de cerrarse: así se cazó. En el emulador sólo se
ha podido comprobar eso; **guardar el ZIP de verdad, sin probar en el móvil.**

**0.4.7 (26-nov-2026): sin la comprobación de instalador de Play.** Mismo código que la 0.4.6.
Play activa por defecto la «protección automática» (mete en lo que reparte una comprobación del
instalador); Bruno pidió quitarla en todas («desactívalo»): depende de los servicios de Play y en
GrapheneOS puede fallar. Se desactivó en Play Console y hacía falta publicar otra versión para que
la que se reparte deje de llevarla.

**0.4.6 (26-nov-2026): las teselas ya no tapan el riel.** Bruno, con la 0.4.5: «ya funciona en
modo escritorio; aparece la barra en la parte lateral, pero se cubre con las teselas». Compose no
recorta un `AndroidView` a su hueco, y el `MapView` (más aún estirado por `WheelZoom`) pintaba por
encima del riel de navegación. El `Box` del mapa lleva `clipToBounds()`. **Sin probar en el móvil.**

**0.4.5 (26-nov-2026): la barra del mapa, visible en modo escritorio.** Bruno: «en modo escritorio
la barra inferior de mapas satélite, etc. no aparece». Con la ventana ancha (riel a la izquierda)
`LocalBottomBar` era 0 dp, y en modo escritorio la barra de tareas del sistema tapa el borde de
abajo de la ventana maximizada: la barra flotante (estilo, red, seguir, zoom) quedaba debajo.
Ahora el riel deja el inset `safeDrawing` de abajo, y arriba (escala del mapa y avisos) también
`safeDrawing` en vez de `statusBars`, por la barra de título de la ventana. **Probado por Bruno
(26-nov-2026: «ya funciona en modo escritorio»).** Desde esta versión llega por **Google Play** (prueba interna; ver el `CLAUDE.md` de
`~/Proyectos`): con eso Android Auto debería aceptarla.

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
  frente»); el `CarAppService` queda registrado con la categoría NAVIGATION. En el móvil, los puntos van
  bien (Bruno, 25-nov-2026). **En el coche no aparece** (Bruno, 25-nov-2026; FMDroid sí): el APK
  está bien (la release firmada lleva servicio, categoría, permisos y `automotive_app_desc` con
  `template`), pero Android Auto clasifica las apps de la Car App Library como TEMPLATE y
  **«Fuentes desconocidas» no las cubre** (sólo multimedia, mensajes y poco más: por eso la radio
  sí sale). Para TEMPLATE exige que Google Play avale la app (que esté en la biblioteca de Play de
  una cuenta del móvil, con la misma firma) o un DHU. Según lo publicado por Fermata y Vela
  (ingeniería inversa de AA 17.4-17.7), en GrapheneOS no funcionan ni `adb install -i
  com.android.vending` ni KingInstaller. Salidas: pista de pruebas internas de Google Play,
  MyCar/GearSlip en lugar de Android Auto, o una radio con Android completo.
  **Confirmado en el móvil el 25-nov-2026** (adb desde el Cardputer, AA 18.0, Android Auto
  inalámbrico): `adb shell dumpsys activity service com.google.android.projection.gearhead` →
  `CarProjectionValidator`, `unknownSourcesEnabled=true` y en «denied packages»
  `pkg=es.altamirano.gps error=PACKAGE_FAILED_ALL_CHECKS`. Sigue igual (tras `am force-stop` de
  AA) instalándola con instalador Play (`adb install -i com.android.vending`), por adb a secas
  (instalador `null`) y con una compilación de depuración (`.debug`, *debuggable*). El
  manifiesto no tiene nada mal: no merece la pena seguir tocándolo; sólo Play lo arregla.

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

**Navegación con rutas calculadas (0.6.0, ver arriba).** La idea de partida: Con **BRouter** (app
aparte, libre, rutas sin conexión con datos de OpenStreetMap por zonas; se le piden por su servicio
AIDL, como hacen OsmAnd y Locus): a pie, bici o coche, indicaciones giro a giro leídas en español
(`TextToSpeech`) y, en Android Auto, `NavigationTemplate` con la maniobra. Los servicios en línea
(openrouteservice, GraphHopper) piden clave, tienen tope y no van sin cobertura.
