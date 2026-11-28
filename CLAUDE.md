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
- **Mapas: MapLibre** (`android-sdk-opengl`, desde la 0.27.0; hasta la 0.26.0, osmdroid). `map/MapStyles`:
  callejero = estilo vectorial del CNIG; topográfico y satélite = estilo mínimo con la capa raster
  WMTS del IGN (`MapLayer.urlTemplate`), escrito en `files/estilos/<capa>.json` y usado por `file://`
  (también para las descargas); híbrido = el vectorial con sólo sus símbolos (borde blanco marcado) y la
  ortofoto debajo; en los dos, los iconos de puntos sólo de las categorías elegidas en la pestaña
  «Puntos» (`core/MapPois`: `PoiCategory` agrupa capas del estilo por prefijo de `id`; `Prefs.pois`;
  `MapStyles.filterPois` las oculta al cargar el estilo). `map/GpsLayers`: todo lo de la app como capas GeoJSON (tracks, rutas, puntos con
  `id` para tocarlos, alternativas con `i`, radares con iconos de `ui/RadarMarks`, posición y círculo de
  precisión), igual en el móvil y en el coche; `install` las vuelve a poner tras cada cambio de estilo.
- **`data/Tiles`**: descargas = regiones de MapLibre (`OfflineManager`) en `files/mbgl-offline.db`,
  una por capa y zona (`OfflineTilePyramidRegionDefinition`) o por capa y camino
  (`OfflineGeometryRegionDefinition` con cuadrados de ±500 m cada 250 m: `corridor`); metadatos JSON
  (capa, zona/camino, clave, fecha); misma clave = reanudar. Sin tope de teselas por región ni para lo
  visto (`setOfflineMapboxTileCountLimit`, caché ambiental de 1 TB: Bruno no quiere límite). «Renovar»
  = `invalidate` (MapLibre revalida con el IGN y sólo baja lo cambiado). Lo de osmdroid
  (`noBackupFilesDir/osmdroid`) ya no se usa: Offline ofrece borrarlo.
- **Mapa del móvil** (`ui/MapScreen`): `MapView` de MapLibre en `AndroidView`, sin girar ni inclinar;
  seguir se quita con un gesto (`REASON_API_GESTURE`); pulsación larga = guardar punto; tocar = punto o
  ruta alternativa (`queryRenderedFeatures` con 18 dp de margen); escala propia (Compose). El zoom que
  se enseña y se guarda es el de las teselas de 256 (MapLibre: uno menos).
- Interfaz sólo en español (como ZeroGPS). Material 3 Expressive con los colores del sistema.

## Estado

**0.30.0 (28-nov-2026, en Play —prueba interna— el mismo día): pestaña «Puntos» para elegir los iconos del callejero.** Bruno: el callejero
vectorial «ha indicado un montón de cosas que no me interesan… todas las antenas de telefonía móvil…
me interesaría como mucho hospitales, farmacias, iglesias y poco más»; y «lo suyo sería poder
seleccionarlo desde la app, una pestaña para eso».
- El estilo del CNIG trae ~85 capas con icono (casi todas de `servicio_instalacion_pto`, una por
  `clase`). `core/MapPois`: 41 categorías en 8 grupos (`PoiGroup`); cada una casa capas por prefijo
  exacto del `id` (`p` o `p-…`: «parque» no se lleva los parques temáticos; alguna capa trae un
  espacio al final). Las capas sin categoría (calles, pueblos, el rótulo genérico
  `servicio_instalacion_pto-texto`, los nombres de parques y jardines, que son de polígonos) no se
  tocan. Ojo: en el estilo, `servicio_instalacion_pto-texto-faro` es el rótulo de las minas.
- De serie: hospitales y centros de salud, emergencias, policía, iglesias (todas las religiosas),
  estaciones de tren y autobús, áreas de servicio y el número de la carretera. **Farmacias no hay** en
  los datos del CNIG (se le dijo a Bruno; harían falta de OpenStreetMap, otra capa).
- Pestaña «Puntos» (`ui/PoisScreen`, entre Tracks y Offline): «Los de serie / Todos / Ninguno», y por
  grupo una tarjeta con «Poner/Quitar todos» e interruptores. Se guarda al tocar (`Prefs.pois`); el
  mapa del móvil lo aplica al volver a él (se rehace al cambiar de pestaña) y el coche al cargar un
  estilo. Sólo se oculta: lo descargado sigue valiendo.
- `MapPoisTest` con la lista de capas del estilo del 28-nov (`src/test/resources/cnig-capas.txt`):
  todo icono tiene categoría y nada que no sea símbolo la tiene. Si el CNIG cambia el estilo, rehacer
  la lista y mirar qué capas nuevas salen.
- **Probado en el emulador `visor`** (depuración): callejero a z16 en Chamberí con lo de serie (sólo
  iglesias y la comisaría de Santa Engracia), «Ninguno» (sin iconos, calles igual), híbrido cargando.
  **Sin probar en el móvil ni en el coche.**

**0.29.0 (28-nov-2026): capa de Metro, Metro Ligero y Cercanías de Madrid (sólo en el móvil).** Bruno:
«una capa en Madrid con las líneas de metro, semitransparente y que se pudiera activar, para ver las
estaciones y las líneas con sus colores»; eligió los tres modos y sólo el móvil.
- Datos: `assets/transporte_madrid.geojson` (264 KB) hecho con `Tools/transporte_madrid.py` (rehacerlo
  con cada versión si cambia la red). Sale de los servicios abiertos del **CRTM** en ArcGIS
  (`services5.arcgis.com/UxADft6QPcvFyDU1`: `Lineas_Metro`, `M10_Lineas`, `M5_Lineas`; una capa de
  tramos y otra de estaciones por línea y sentido). La vía, de los tramos de un sentido; **las estaciones,
  del final de cada tramo de los dos sentidos** (lleva su `DENOMINACION`): las capas `*_ESTACION` no son
  fiables (la de la L1 S1 trae estaciones de otras líneas). Mismo nombre a menos de 600 m = una estación
  con todas sus líneas (los andenes de la L8 en Nuevos Ministerios están a 450 m). El CRTM no da colores
  (van en el script, los de los planos, cotejados con OpenStreetMap) y sus nombres vienen en mayúsculas
  sin tildes: se toma el de la estación de OpenStreetMap (Overpass) a menos de 400 m que se llame igual
  sin tildes. 241 estaciones de metro, 56 de Metro Ligero y 95 de Cercanías. `TransporteTest` vigila el
  fichero (13 líneas de metro, 4 de ligero, 9 de Cercanías, colores y nombres).
- `GpsLayers.addTransit` (si no es el coche): debajo de todo lo de la app, borde oscuro al 35 % (el
  Ramal es blanco) y la línea con su color al 70 %; estaciones (punto blanco) desde z12 y nombre + líneas
  desde z14 (zooms de 256). **El zoom de una expresión tiene que ir arriba del todo**: dentro de un `match`
  MapLibre lo ignora en silencio y la línea sale de 1 dp (pasó).
- Botón del tren en la barra del mapa (`Prefs.transit`, apagado de serie); con la capa puesta la
  atribución añade «Transporte © CRTM».
- **Probado en el emulador `visor`** (depuración): satélite a z13 y z14, topográfico a z15, callejero a
  z12; se recuerda al volver a abrir. La release (sólo arm64) no se ha podido abrir en el emulador.
  **Sin probar en el móvil.**

**0.28.2 (27-nov-2026, en Play —prueba interna— el mismo día): sin «fallos» falsos en las descargas.** Bruno, con la 0.28.1: «funciona todo
pero en la descarga me ha marcado 28 fallos». Eran reintentos: MapLibre reintenta solo los errores de
red y del servidor y cada `onError` se contaba como fallo aunque la tesela acabara bajando (la región
sólo se da por completa con todo). Ahora `REASON_NOT_FOUND` (tesela que no existe) no cuenta y, si en
15 s no avanza, el progreso dice «sin conexión, reintentando» / «el IGN no responde, reintentando»
(`DownloadState.retrying`) en vez de un contador. Probado en el emulador cortando el wifi y los datos a
mitad de una descarga del satélite de 5 km: «13 MB · 1 % · sin conexión, reintentando» y, al volver la
red, siguió sola. Bruno confirma que lo demás de la 0.28.1 funciona en el móvil (27-nov); el coche,
sin probar todavía.

**0.28.1 (27-nov-2026, en Play —prueba interna— el mismo día; lleva la 0.28.0): la app se cerraba al cambiar de mapa.** Bruno: «se me ha cerrado la aplicación
de golpe». Reproducido en el emulador cambiando de mapa con la posición moviéndose: al pedir un estilo
nuevo MapLibre invalida el anterior y `GpsLayers` le seguía escribiendo la posición (cada segundo) →
`IllegalStateException: Calling getSourceAs when a newer style is loading/has loaded`. Ahora
`MapStyles.apply` suelta las capas (`GpsLayers.detach`) antes de cambiar y sólo se escribe en un estilo
cargado del todo (`isFullyLoaded`). Probado: 30 cambios de mapa seguidos con la posición cambiando cada
0,25 s, sin cierre y con todo en su sitio. Vale también para el coche.

**0.28.0 (27-nov-2026): arreglos de la 0.27.0.** Bruno: «cuando marco ruta en el mapa no cambia el
zoom para enseñar toda la ruta»; «al descargar pone 0 de 1 archivos y no avanza, y sigue midiendo en
teselas»; «cuando doy a la ubicación con un zoom muy amplio, no acerca».
- **Descargas de topográfico y satélite**: el descargador de MapLibre pide el estilo por HTTP y con
  `file://` fallaba («Unable to parse resourceUrl») y se quedaba en «0 de 1». Los estilos raster están
  publicados en el repositorio (`estilos/topo.json`, `estilos/pnoa.json`, servidos por
  raw.githubusercontent.com) y las regiones usan esa URL (`MapStyles.downloadUriOf`); al pintar se
  sigue usando el fichero local (las teselas se guardan por su URL). `EstilosTest` comprueba que los
  publicados son iguales a `rasterStyleJson` (`ESCRIBIR_ESTILOS=1` los reescribe: **si cambian, hay que
  subirlos a `main`**). Una región con otro estilo se borra y se rehace; las que dejó la 0.27.0 con
  `file://` se borran al abrir Offline.
- Progreso en MB y porcentaje («empezando…» mientras MapLibre sólo conoce el estilo); fuera las teselas
  de la estimación (sólo los MB).
- **Encuadre**: el aviso de encuadrar (`fitRequest`) podía llegar a la pantalla antes que la ruta y no
  hacía nada (con osmdroid funcionaba porque encuadraba con `post`). Ahora se lee el valor del momento
  (`RoutePreview.state.value`, igual para tracks, seguir y puntos). Margen de abajo con la barra de
  navegación y la tarjeta.
- **Seguir desde lejos**: el acercamiento a z15 iba animado y la posición siguiente (cada segundo)
  cortaba la animación: ahora de golpe (`moveCamera`).
- **Probado en el emulador `visor`**: topográfico 1 km (2,2 MB) y «Todos» 2 km (110 MB, progreso
  «12 MB · 8 %»… «Zona descargada»); seguir desde z6 → z15; «Ruta hasta aquí» en coche encuadra la ruta
  con sus alternativas; «Empezar» guarda el camino del callejero y del topográfico (salen en Offline).

**0.27.0 (27-nov-2026, en Play —prueba interna— el mismo día): todo con MapLibre; el callejero vectorial sustituye al IGN Base.** Bruno:
«sustituye completamente al ráster con el vectorial; descárgalo, y la limitación de 1 GB me da igual;
deja el ráster sólo para las capas que tengan otra información» (topográfico y satélite), en el coche y
en el móvil; el híbrido, la ortofoto con los nombres vectoriales.
- Fuera osmdroid (dependencia, `MapScene`, `OsmCarMap`, `IgnTileSource`, la descarga propia, `WheelZoom`:
  MapLibre hace zoom con la rueda). `MapLayer`: `TOPO`, `ORTHO`, `VECTOR` (clave «base»: quien tenía el
  IGN Base pasa al callejero); fuera `LABELS`. `MapStyle.BASE` = «Callejero»; `viewMaxZoom`.
- Coche: `CarMapRenderer` es el único motor (botón de capas: callejero → topográfico → satélite →
  híbrido); usa `GpsLayers`. Fuera `CarMap`/`VectorCarMap`.
- Descargas (ver «Cómo está hecha»): zonas en Offline (por estilo: sus capas) y, al empezar a seguir con
  red, el camino ±500 m de z10 a z17 del callejero y de las capas del estilo del móvil. Offline: por
  capa, descargas y tamaño, «Vaciar»; «Lo visto» (vaciar la caché ambiental); «Mapas de la versión
  anterior» con «Borrar». Actualizaciones: «Mapas descargados» con «Renovar».
- **El vectorial pesa más de lo que parecía**: ~100 KB por tesela en ciudad a z14-15 (el topográfico,
  ~25); 1 km en Madrid de z5 a z17 fueron 15 MB (estimación subida). Lo dicho a Bruno antes («6 KB,
  mucho más ligero») estaba mal y se le corrigió.
- **Las zonas bajadas con osmdroid no valen**: hay que volver a bajarlas (Offline lo explica).
- `CorridorTest` (47 pruebas). **Probado en el emulador `visor`** (`-gpu host`; depuración y release con
  R8): móvil con los cuatro mapas, escala, zoom, guardar un punto con pulsación larga y abrir su ficha
  tocándolo, descarga de 1 km del callejero (545 archivos, 15 MB, «Zona descargada», sale en Guardado);
  coche con callejero (girado 180°, nombres derechos), topográfico e híbrido. **Sin probar**: el
  camino al empezar una ruta (hace falta una ruta de BRouter), «Renovar», el modo sin red, tocar las
  rutas alternativas y todo en el móvil y el coche de verdad.

**0.26.0 (27-nov-2026, en Play —prueba interna— el mismo día; lleva la 0.23.0–0.25.0): Android Auto con el Mapa Base vectorial del IGN.** Play avisa de que deja de admitir 973 modelos (los de 32 bits y x86, por ir sólo arm64): da igual para el Pixel. Bruno: «cuando realizas un
giro, los títulos de las calles no cambian y permanecen en la dirección en que iba antes, con lo cual se
quedan al revés». Con teselas raster no tiene arreglo (los rótulos van pintados en la imagen). El IGN
publica su mapa base en vectorial (CNIG, sin clave): estilo
`vt-mapabase.idee.es/files/styles/mapaBase_scn_color1_CNIG.json`, teselas `.pbf` hasta z17, fuentes
Noto Sans, sprites y relieve (`xyz-mdt.idee.es`). Bruno eligió esa opción («hazlo»).
- **MapLibre `android-sdk-opengl` 13.7.1** (15-oct-2026). La variante de serie (`android-sdk`) dibuja con
  Vulkan y colgaba el emulador; OpenGL es la probada con `TextureView` y pantallas virtuales.
  `abiFilters` arm64-v8a y x86_64 (sin eso el APK cargaba con las cuatro librerías nativas).
- `car/CarMap` (interfaz) con dos motores: `VectorCarMap` (nuevo, de serie) y `OsmCarMap` (el
  `CarMapRenderer` de antes, para topográfico, satélite, híbrido e IGN Base, con lo descargado).
  `CarMapRenderer` es ahora el que los alterna con el botón de capas (Callejero → Topográfico → …), suelta
  uno y monta el otro sobre la misma `Surface` con lo último (posición, ruta, tarjeta). `Prefs.carStyle`
  («vector» de serie) aparte del estilo del móvil.
- `VectorCarMap`: pantalla virtual + `Presentation` como antes; `MapView` de MapLibre en modo
  `TextureView`, gestos apagados (los manda el coche: `scrollBy(-dx, -dy)`, zoom). Encima, capas GeoJSON:
  alternativas y ruta elegida (con borde blanco), lo andado, lo que queda (con borde), hueco discontinuo,
  track grabado, línea al destino, tramos de radar, puntos (círculo + nombre en Noto Sans Bold), radares
  (iconos hechos con `RadarsOverlay.paintRadar`, derechos con `ICON_ROTATION_ALIGNMENT_VIEWPORT`, desde
  z10) y posición (flecha que gira con el mapa, punto o punto gris). Los mapas de bits llevan
  `density` = dpi del coche (MapLibre los toma en dp). Velocidad, tarjeta y «© IGN · Mapa Base» en una
  vista encima (`Hud`; `SpeedOverlay`/`ManeuverOverlay` tienen ahora `paint(c, w, h)`). Cámara:
  `easeCamera` lineal de 900 ms por posición; con la dirección arriba, el coche al 70 % (por `padding`).
  De noche, un velo negro al 40 % debajo de la ruta. Sin red, `MapLibre.setConnected(false)`.
- Zoom: MapLibre cuenta con teselas de 512: su zoom = `Prefs.carZoom` − 1 (misma escala que osmdroid).
- Sin conexión: la caché de MapLibre (lo visto) subida a 1 GB. **Las zonas descargadas en Offline son
  raster y no valen para el vectorial**: sin cobertura y en sitio no visitado, el callejero sale vacío
  (el botón de capas lleva al topográfico, que sí usa lo descargado). Pendiente: descarga del vectorial.
- `ui/visibleRadars` (función suelta, la usan los dos motores).
- **Prueba en el emulador** (`src/debug/.../CarMapTestActivity`, sólo en depuración: monta el mapa del
  coche sobre una `TextureView` de 1280×640 a 160 dpi, simula ir por la Castellana al norte, al este y al
  sur y guarda `files/carmap-N.png`). El AVD `prueba` (ATD) no dibuja nada; hace falta el **AVD `visor`**
  (AOSP 35 normal, creado el 27-nov) con **`-gpu host`**: con `swiftshader_indirect` los rótulos e iconos
  de MapLibre salen como manchas. Visto: el mapa pinta, gira con la marcha y los nombres siguen derechos;
  flecha de posición abajo, radares con su límite, semáforos, puntos con nombre, línea al destino,
  tarjeta, velocidad. **Sin ver**: la ruta y la vista previa (la prueba no simula seguir una ruta),
  la noche, el cambio de capas y todo en el coche de verdad.
- La **release sólo lleva arm64-v8a** (`buildTypes.release.ndk.abiFilters`): con x86_64 el AAB pasaba
  de 10 MB (13,6) y el navegador no lo sube a Play; queda en 9,7 MB y el APK en 15 MB. La depuración
  lleva las dos. Para probar la release (R8) en el emulador se compiló una vez, aparte, con x86_64 y
  `src/debug` añadido a la release (`kotlin.directories`/`java.directories` y su manifiesto; con
  `java.srcDir` a secas el Kotlin integrado de AGP 9 no lo compila): el mapa vectorial pinta igual.

**0.25.0 (27-nov-2026): ruta sin señal, desde la bola gris.** Bruno: al arrancar en el garaje, sin
GPS, el coche dijo que esperaría a tener señal para enseñar la ruta; «debería marcármela desde el último
lugar en el que me ha detectado, donde estaba la pelota gris».
- `Gps.routeStart`: la posición de ahora o, si no, la última conocida (`Gps.location`, que ya trae la
  `getLastKnownLocation` del sistema al pedir el GPS, o `Prefs.last`). La usan el coche (`routeTo`), la
  ficha del móvil (`RouteUi`) y el botón de otras rutas (`RoutePreview.fromNavigation`). Con una vieja,
  sin rumbo. Seguir ya ignora las posiciones viejas: al llegar la señal se sigue o se recalcula.
- El coche guarda también `Prefs.last` (cada 50 m; antes sólo el mapa del móvil, cada 200 m).
- **Sin probar** (ni en el emulador ni en el coche).

**0.24.0 (27-nov-2026): tarjeta propia en el mapa del coche y zoom recordado.** Bruno, tras probar la
0.23.0: el cuadro naranja «demasiado grande, debería ser semitransparente y en otro color», una
esquina negra debajo de su esquina de arriba a la izquierda, los rótulos «mucho más pixelados» y
«selecciona por defecto el zoom que estás viendo ahora».
- La tarjeta de Android Auto (`setNavigationInfo`, con `RoutingInfo`/`MessageInfo`) no deja elegir
  tamaño ni transparencia, y la esquina negra era la suya sobre la esquina redondeada del mapa. Ya no
  se manda: `car/ManeuverOverlay` la pinta en el mapa, arriba a la izquierda de lo visible, gris azulado
  oscuro al 75 % (`BACKGROUND`), como mucho 260 dp o el 45 % del ancho: dibujo de la maniobra, distancia,
  la indicación y «llegada · min · km» (o la flecha, el nombre y el texto sin ruta). Con eso la plantilla
  ya no se rehace por la tarjeta (sólo por los botones). `step()` queda sin uso por si se vuelve a la del coche.
- Zoom del coche guardado (`Prefs.carZoom`, 14 la primera vez) y, tras un pellizco, redondeado a un
  nivel entero a los 600 ms (con medio nivel las teselas se estiran); los botones van a niveles enteros.
- Lo pixelado no está claro: en la 0.23.0 no cambió nada de las teselas. Puede ser el zoom a medias
  (arreglado arriba) o el mapa girado con la dirección arriba (las teselas raster giradas pierden
  nitidez). Pendiente de una foto de Bruno si sigue.
- **Sin probar en el coche.**

**0.23.0 (27-nov-2026): Android Auto con más pinta de navegador.** Bruno mandó fotos del coche
(«funciona bastante bien», pero hay cosas de la interfaz que mejorar). Lo que se veía y lo cambiado:
- La flecha de la maniobra era la flecha suelta girada (la de `arrow()`), que parecía el cursor de
  posición. Ahora `maneuverIcon`: tallo desde abajo doblado hacia el giro con punta, giro en U, rotonda
  con anillo y salida (con el signo del ángulo de BRouter: negativo a la izquierda, comprobado en
  `VoiceHint.calcCommand` de brouter-core 1.7.9) y bandera de destino. El lado sale del tipo; el ángulo
  sólo dice cuánto, acotado al tipo. La flecha suelta sigue para el destino sin ruta y fuera del track.
- Debajo de la maniobra, `setDestinationTravelEstimate`: llegada, tiempo y km que quedan (sólo cuando
  `remainingS` ya se sabe; redondeados a 100 m y al minuto para no rehacer la plantilla).
- `MapScene(carMode = true)`: ruta 1,7× más gruesa con borde blanco debajo (sobre el IGN claro apenas
  se veía) y, en marcha, la flecha de navegador grande con borde blanco en vez del punto azul.
- Con la dirección de la marcha arriba, el coche al 70 % del alto de lo visible (`HEADING_UP_Y`), no en
  el centro.
- **Sin probar en el coche** (no hay forma aquí); los iconos, revisados dibujándolos aparte.

**0.22.0 (27-nov-2026, en Play —prueba interna— el mismo día): el portal de tu municipio, primero.** Bruno: «si meto Eduardo Dato 11, me
muestra el primero en Campanario (Badajoz), no el Paseo de Eduardo Dato en Madrid». CartoCiudad sólo
devuelve unos seis resultados y no por cercanía: para «eduardo dato 11» da seis pueblos con esa calle y
el de Madrid ni sale, así que ordenar por distancia no bastaba.
- Se pregunta el municipio donde se está (`reverseGeocode` de CartoCiudad; se recuerda mientras no te
  muevas más de 1 km) y se busca también «<lo escrito>, <municipio>» (`withTown`; no si ya lo lleva,
  sin mirar tildes), las dos a la vez. Con el municipio CartoCiudad añade los portales de al lado
  (10, 12, 13…): se queda sólo el número pedido (`portalNumber`). `joinPortals` junta las dos sin
  repetir y por distancia.
- Sin posición del GPS, la última conocida (`Prefs.last`) o donde se miró el mapa (`Prefs.view`), para
  esto y para Photon.
- El nombre quita sólo el pueblo del final: Zaragoza da «CALLE DATO, EDUARDO 11, Zaragoza» y salía
  «Calle Dato».
- **Probado en el emulador** (release, en Sol): «eduardo dato 11» → «Paseo Eduardo Dato 11 · Madrid,
  28010 · 2,0 km» el primero. **Sin probar en el móvil.**

**0.21.0 (27-nov-2026, en Play —prueba interna— el mismo día; lleva la 0.20.0, que no se publicó sola): buscar un portal concreto.** Bruno: «cuando busco Alonso Cano 56 me encuentra la
calle en varios resultados, pero no el número exacto». OpenStreetMap no tiene los portales de muchas
calles (de Alonso Cano, en Madrid, ninguno: ni Photon ni Nominatim pueden darlo).
- Con un número suelto en lo buscado (`PlaceSearch.looksLikeAddress`: «alonso cano 56», «mayor 3b»;
  no «m30» ni «a-6»), se pregunta además a **CartoCiudad** (IGN, sin clave,
  `cartociudad.es/geocoder/api/geocoder/candidates`), que tiene los portales de toda España (Catastro y
  ayuntamientos), a la vez que a Photon (`async`). Si falla, se sigue con lo de Photon.
- `parseCartoCiudad`: sólo `type` = «portal» (calles y municipios ya los da Photon); el texto viene en
  mayúsculas («CALLE ALONSO CANO 58, Madrid» → «Calle Alonso Cano 58», `titleCase`); CartoCiudad no
  ordena por cercanía y da el mismo portal de otros pueblos: se ordena por distancia. `merge`: los
  portales primero y de Photon lo que no caiga a menos de 30 m de uno. 2 pruebas (respuesta real en
  `src/test/resources/cartociudad-muestra.json`).
- **Probado en el emulador** (release, en Sol): «alonso cano 58» → «Calle Alonso Cano 58 · Madrid,
  28003 · 2,9 km» el primero; luego los de Cúllar Vega, La Rinconada y Morón, y lo de Photon. **Sin
  probar en el móvil.**

**0.20.0 (27-nov-2026): Offline → Actualizaciones.** Bruno: «¿cada cuánto actualiza? Debería poder
forzarse desde la app: una parte en ajustes que deje descargar radares, BRouter, teselas… que marque si
hay nuevas y deje descargar».
- `ui/UpdatesCard` (sección «Actualizaciones» en Offline): por cada cosa, de cuándo es lo del móvil, si
  hay algo nuevo («Nuevo» en el color terciario y botón relleno) y su botón. Al abrirse, con «Usar la
  red», un HEAD por fuente (nada se baja):
  - **Radares**: `Last-Modified` del feed de Radares Anunciados (GitHub Pages) frente a la fecha del
    fichero, que ahora es la del servidor. «Descargar» / «Volver a bajar» (`Radars.updateRadars`).
  - **Cámaras de semáforo**: Overpass no da fecha: «Lista del …» y «puede haber cambios» a la semana;
    «Actualizar» (`Radars.updateRedLights`; puede tardar 40 s o más, y si Overpass está saturado lo dice).
  - **Datos de rutas**: cada cuadro con su fecha y la del servidor; «Descargar» lanza el trabajo
    (`DataUpdateWorker.updateRoutesNow`, sólo rutas, cualquier red).
  - **Mapas guardados**: teselas por capa y fecha de la más vieja (`Tiles.oldestSaved`: caducidad
    menos el año). El IGN no da fechas: «Renovar <capa>» (`Tiles.renew`) pone `expires = 0` a todas
    las de la capa (`TileDb`, subclase de `SqlTileWriter` para llegar a `getDb()`, que es
    `protected`): se siguen viendo sin red y osmdroid las vuelve a bajar al verlas con red (o al
    repetir la descarga de una zona, que ya salta sólo las no caducadas).
- `RouteTilesWorker` → **`DataUpdateWorker`, diario** con wifi y batería: radares si la última
  descarga tiene más de un día (`radars_fetched`), semáforos si tienen más de una semana, y rutas si la
  última comprobación tiene más de `REFRESH_DAYS` (7). Cancela el trabajo semanal de la 0.19.0
  (`route-tiles`). Los radares también se siguen renovando al empezar una ruta en coche.
- **Arreglado: la lista de radares bajada se borraba al leerla** (desde la 0.11.0). La comprobación del
  formato de la 0.10.0 («F;»/«T;») miraba sólo la primera letra y la de ahora empieza por «FIXED;»: la
  app usaba siempre la lista que trae de serie. `RadarList.isLegacyCsv`, con su prueba.
- **Probado en el emulador** (depuración y release): con un cuadro y una lista viejos, «Nuevo» en
  radares y rutas; «Descargar» en los dos → «al día»; semáforos (a la segunda: la primera, Overpass
  saturado); «Renovar Topográfico» → 131 teselas con `expires = 0`, y al ver el mapa con red se
  renovaron las 15 visibles. El trabajo diario, al abrir la app, bajó radares y el cuadro. **Sin probar
  en el móvil.**

**0.19.0 (27-nov-2026, en Play —prueba interna— el mismo día): los cuadros de rutas se renuevan solos.** Bruno: «que los cuadros que descarga
de OpenStreetMap caduquen cada x tiempo y se descarguen los nuevos automáticamente» (vino de la Puerta
de Segovia de La Granja, corregida en OSM el mismo día). Y confirma que **las rutas reales con BRouter
y sus alternativas y los mapas del camino funcionan** (27-nov-2026).
- `data/RouteTilesWorker` (WorkManager 2.12.0, dependencia nueva, la misma que TiempoDroid; es de
  AndroidX, sin servicios de Google): periódico cada `RouteTiles.REFRESH_DAYS` (7) días con red sin
  coste (`UNMETERED`), batería y espacio; se programa en `GpsApp` (`KEEP`). Con «Usar la red» apagado
  no hace nada. Hasta 3 reintentos si falla. WorkManager añade dos permisos normales (sin diálogo):
  `RECEIVE_BOOT_COMPLETED` (reprogramar tras reiniciar el móvil) y `WAKE_LOCK`.
- `Routing.updateOutdated`: HEAD a cada cuadro del móvil (`Last-Modified`) y, si es más nuevo
  (`RouteTiles.outdated`, 1 min de margen), lo vuelve a bajar. El viejo sirve hasta que el nuevo
  está entero (`rename`; una ruta en marcha sigue con el que tenía abierto).
- `Routing.download`: el cuadro bajado toma la **fecha del servidor** (`setLastModified`), que es
  con lo que se compara luego; los bajados antes de esta versión tienen la de la descarga, y se
  renuevan en cuanto brouter.de rehaga el suyo. Reanudar ahora manda `If-Range` con la fecha del
  servidor (guardada en `<cuadro>.rd5.part.date`): si el cuadro cambió a medias, empieza de cero
  en vez de mezclar dos versiones.
- Offline → Datos para rutas: «datos del <fecha>», «Se renuevan solos cada semana, con wifi…
  (última comprobación: …)» y «Buscar datos nuevos ahora» (trabajo único con cualquier red).
- brouter.de rehace los cuadros a menudo: el `W5_N40` del 27-nov era de esa madrugada (01:03 UTC).
- **Probado en el emulador** (depuración y release): con un `W5_N40.rd5` falso de 6 bytes y fecha de
  2020, la primera ejecución al abrir la app bajó el de verdad (91.970.332 bytes, fecha del
  servidor); el botón, otra pasada en 0,6 s sin bajar nada. **Sin probar en el móvil.**

**0.18.0 (27-nov-2026, en Play —prueba interna— el mismo día): buscar un sitio y llevar allí la ruta.** Bruno, de la lista de pendientes («ruta
desde una pulsación larga en el mapa»): «mejor desde la lupa». Y confirma que **los radares (pitido,
iconos de radar y semáforo, descarga, ajustes) y todo Android Auto de la 0.11–0.17 van bien** (27-nov-2026).
- `core/Places.kt`: `PlaceSearch` sobre **Photon** (`photon.komoot.io/api`, buscador libre sobre
  OpenStreetMap, sin clave, pensado para buscar mientras se escribe; se le pasa la posición para que lo
  cercano salga antes). `parsePhoton` lee el GeoJSON (nombre, o calle y número, o pueblo; detalle con
  calle, pueblo y provincia; quita repetidos a menos de 50 m). `Place.toWaypoint()` con id negativo:
  la ruta y la flecha funcionan igual que con un punto guardado, sin guardarlo. 3 pruebas
  (`PlacesTest`, con una respuesta real en `src/test/resources/photon-muestra.json`).
- `data/Places.search`: sólo con «Usar la red» y red validada; dice por qué no si no.
- Android Auto: la lupa abre la lista de puntos, que ahora empieza por «Buscar un sitio…»
  (`PlaceSearchCarScreen`, `SearchTemplate`; medio segundo de espera al escribir; distancia con
  `DistanceSpan`). Tocar un resultado = lo mismo que tocar un punto (ruta en coche y elegir entre las
  alternativas). El teclado del coche sólo va parado (lo bloquea Android Auto).
- Móvil: lupa (botón pequeño) junto al de guardar punto → hoja de búsqueda (`PlaceSearchSheet`) →
  ficha del sitio (`PlaceSheet`): «Ruta hasta aquí» (el mismo `RouteToButton`), «Ir hacia aquí», «Ver
  en el mapa» y «Guardar como punto».
- **Probado en el emulador** (móvil, release): «mercadona segovia» desde La Granja → 4 resultados con distancia, ficha, «Ruta hasta aquí» en coche → 2 rutas (18 min · 10 km y 22 min · 12,4 km). **Sin probar** en el móvil ni en Android Auto (no hay DHU).

**0.17.1 (27-nov-2026): arreglos de Android Auto.** Bruno: «cuando intenta calcular una ruta en coche
desde Android Auto, da un error y se cierra»; «el zoom por defecto es demasiado cerca»; «el icono de
puntos para seleccionar destino debería ser una lupa».
- **El cierre**: `RoutePreviewNavigationTemplate.Builder.setItemList` exige que cada fila lleve un
  `DurationSpan` o `DistanceSpan` (`ModelUtils.validateAllRowsHaveDistanceOrDuration`) y si no lanza
  `IllegalArgumentException`. `RouteChoiceCarScreen`: la duración con `DurationSpan` en el título y la
  distancia con `DistanceSpan` en el texto. Cualquier lista de rutas en el coche: con spans.
- Zoom del coche al abrir: 14 fijo (`CarMapRenderer.START_ZOOM`); antes heredaba el del móvil (≥13).
- Botón de los puntos en el mapa del coche: lupa (`car_search`); las filas de la lista siguen con la chincheta.
- Radar «sobre las vías de Chamartín» (40.48048, -3.68543): es el del Ayuntamiento de Madrid
  «Paseo de la Castellana nº 300, salida» (50); Nominatim pone ahí la Castellana en obras (prolongación
  sobre la playa de vías). Dato oficial: no se toca.
- Rótulos al revés con «dirección arriba» en el coche: los del IGN vienen pintados en las teselas
  (PNG), no se pueden girar uno a uno; sin cambiar (ver lo hablado con Bruno).

**0.17.0 (26-nov-2026): cámaras de semáforo en rojo.** Bruno: «¿hay avisos de las cámaras de
semáforo? ¿Se podrían incluir con un icono diferente?».
- Ni la DGT ni Radares Anunciados las tienen (son municipales). OpenStreetMap, 218 en España
  (taginfo de Geofabrik: 215 relaciones `type=enforcement` + `enforcement=traffic_signals` y 7
  nodos): `RadarList.parseOverpassRedLights` toma el miembro `to` (o `force`; si no, `device`) como
  punto y el rumbo de `from` a ese punto como sentido (205 de 218 lo traen).
- `assets/semaforos.csv` (sacado de Overpass el 26-nov-2026) aparte de `radares.csv` (el feed diario
  no lo pisa); `Radars.list` junta los dos. Con red, una vez por semana, Overpass
  (`overpass-api.de`, POST; sus servidores públicos se saturan: si falla, se sigue con lo que hay).
- `RadarKind.RED_LIGHT` («Cámara de semáforo»): aviso a 200 m como mucho (en ciudad, 500 sería
  pronto); casilla propia en los ajustes (móvil y coche), encendida de serie y añadida una vez a quien
  ya tenía clases elegidas (`radar_kinds_red_light`). En el mapa, un semáforo (rojo, ámbar, verde)
  sobre una placa blanca, distinto de las señales redondas de los radares.
- 38 pruebas (muestra real de Overpass en `src/test/resources/semaforos-muestra.json`). **Sin ver
  en pantalla** el icono (emulador sin ventana).

**0.16.0 (26-nov-2026): en Android Auto, la dirección de la marcha hacia arriba.** Bruno: «que en
Android Auto la dirección en la que se mueve el coche sea hacia arriba en el mapa».
- `CarMapRenderer.recenter`: siguiendo la posición y a 2 m/s o más (parado, el rumbo del GPS baila),
  `mapOrientation = -bearing`, girando cada vez el 60 % de lo que falta (sin tirones). Interruptor
  «Mapa: dirección arriba / norte arriba» (`Prefs.carHeadingUp`, de serie arriba) en la pantalla de
  ajustes del coche (`RadarSettingsCarScreen`, ahora «Ajustes»).
- osmdroid pinta las capas con el lienzo **ya girado** (`MapView.dispatchDraw` → `Projection.save(c,
  true, false)`): la flecha de la posición pasa a `rotate(bearing)` (antes restaba la orientación,
  que siempre era 0); chinchetas, nombres y señales de radar se contragiran sobre su punto para ir
  derechos; la velocidad se pinta sin giro (`projection.save(c, false, false)`, como la escala).
- **Sin probar** (no hay DHU). En el móvil el mapa no gira: nada cambia allí.

**0.15.0 (26-nov-2026): sin voz; los radares, con un pitido suave.** Bruno: «has quitado las
instrucciones guiadas por audio, ¿verdad? Los radares sí, que tengan un pitido razonablemente sutil».
La voz no se había quitado (sólo el aviso «Sin voz» de la 0.8.2): si el móvil tenía motor, hablaba.
- Fuera `TextToSpeech` entero de `FollowService` (maniobras, «Recalculando», «Ruta nueva», llegada),
  `FollowState.noVoice` y el `<queries>` de `TTS_SERVICE`. `RouteGuide.announce` y
  `RadarAlert.spoken` quedan en `core` (con sus pruebas) aunque la app ya no los dice.
- `data/Beeper`: «bip-bip» (880 Hz 110 ms + 1175 Hz 130 ms, 15 ms de entrada y salida, volumen 0,35)
  sintetizado con `AudioTrack` como `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` y foco transitorio que baja
  la música (con Android Auto sale por el coche). El modo de aviso de radares «Con pitido» lo usa en vez
  de la notificación que sonaba y vibraba; «Sólo en pantalla», nada.
- **Sin oír**: el emulador va sin audio.

**0.14.0 (26-nov-2026): elegir ruta como en Waze (todas a la vez y el botón de las dos flechas).**
Bruno: «que pueda elegir ruta desde un botón como el de Waze: de dos flechas, para elegir entre las
rutas disponibles, y las muestra todas a la vez en el mapa».
- `RoutePreview.show` calcula las alternativas (`alternativeidx` 1-3) **todas de una vez** en segundo
  plano y las va añadiendo; para al llegar a una casi igual. `select(i)` elige; ya no hay «Otra ruta».
- Mapa (móvil y coche): `MapScene.setPreview` pinta la elegida en azul fuerte y las demás claras
  debajo; tocar una clara la elige (`Polyline.setOnClickListener`). Tarjeta: un `FilterChip` por ruta
  con su tiempo y distancia.
- Botón de las dos flechas (`AltRoute`) yendo por una ruta: en el móvil, junto al de guardar punto;
  en Android Auto, en lugar del de grabar. `RoutePreview.fromNavigation` calcula desde donde estás al
  mismo destino; «Empezar» cambia de ruta, «Cancelar» sigue con la de antes.
- Android Auto: elegir un punto ya no empieza en el acto: `RouteChoiceCarScreen` con la plantilla
  `RoutePreviewNavigationTemplate` (lista con `setOnSelectedListener` y «Empezar»); el mapa encuadra la
  elegida mientras se elige y vuelve a seguir al acabar.
- **Probado en el emulador** (móvil): 4 rutas de Sol a Plaza de España en la tarjeta, elegir la de 17
  min (1,5 km) y empezar; el botón de las dos flechas yendo por ella vuelve a dar las 4; «Cancelar»
  sigue con la de antes. **Sin probar**: Android Auto (no hay DHU) y tocar las líneas del mapa.

**0.13.0 (26-nov-2026): los radares, en el mapa.** Bruno: «los radares tienen que tener un icono
visible en el mapa cuando vas a más de 20 km/h o has elegido ruta en coche».
- `ui/RadarsOverlay` (en `MapScene`, móvil y coche; encima de los tracks y debajo de los puntos): una
  señal (círculo blanco, borde rojo; naranja los móviles) con el límite dentro o una cámara si no se
  sabe; los tramos, además, línea discontinua de principio a fin; los tramos de radar móvil, sólo la
  línea. Se ve a más de 20 km/h (y 2 min después, por los semáforos) o con una ruta en coche, desde
  z11, y sólo las clases elegidas en los ajustes (y los móviles, su día). Se recargan al cambiar los
  ajustes (`Radars.settingsFlow`).
- **Sin ver en pantalla**: el emulador sin ventana da capturas negras (también `adb emu screenrecord
  screenshot`); comprobado sólo que no se cae con una ruta en coche por la M-40.

**0.12.0 (26-nov-2026): Android Auto — rutas, pararlas y el coche en el centro.** Bruno: «una vez
seleccionas una ruta no te deja cancelarla; no te deja seleccionar una ruta, sólo te dice a qué
distancia está el punto (debería ser al revés); la ubicación del coche debería ser el centro del mapa».
- Elegir un punto en el coche (`PointsCarScreen` → `MapCarScreen.routeTo`) calcula la **ruta en
  coche** y empieza a guiar (sin vista previa); si faltan los datos de la zona los baja (con red);
  sólo si no se puede (sin GPS, sin datos ni red, sin ruta, o no deja arrancar el servicio) queda la
  flecha en línea recta, diciendo por qué.
- **Parar**: con algo en marcha, el botón de guardar punto pasa a ser «Parar» (X roja); y en la lista
  de puntos, la primera fila. Además `NavigationManager.navigationStarted/Ended` con su
  `onStopNavigation` (el coche puede pararla por sus mandos; la maniobra va al cuadro).
- **Centro**: el desplazamiento de osmdroid estaba bien (`getScreenCenterX` = centro + offset: el coche
  va al centro de lo visible), pero `animateTo` con una posición por segundo iba por detrás: ahora
  `setCenter` en el acto; al arrastrar (o el modo mover del coche) deja de seguir y **vuelve solo a
  los 15 s**; al empezar una ruta, vuelve a seguir.
- **Sin probar** (no hay DHU con Android Auto en el emulador): todo lo de esta versión.

**0.11.0 (26-nov-2026): más radares, ajustes de avisos (también en el coche) y la velocidad en Android
Auto.** Bruno pasó «Radares Anunciados» (geiserx.github.io/radares-anunciados-ha) y preguntó por
OpenStreetMap; luego: «que sea configurable la distancia de aviso y el tipo de aviso en la pantalla
de Android Auto, y la velocidad del coche en un simbolito en una esquina».
- Fuente principal: el GeoJSON de Radares Anunciados (`feed.geojson`, ~2,6 MB, cada 6 h; ODbL por
  OSM; proyecto de un particular, GPL): DGT, Servei Català de Trànsit, Euskadi, ayuntamientos y OSM
  (`highway=speed_camera`), sin repetidos. Se usa `fixed`/`trailer` → `FIXED`, `stretch` (la
  línea) → `SECTION` (o `MOBILE_STRETCH` si es `dgt_invive`), `section` sólo si no es un extremo
  `-from`/`-to` (Cataluña da un punto), `mobile_announced` → `MOBILE` con `valid_from/to`; fuera
  `mobile_recurring` (multas de hace meses) y `reported` (notas sin confirmar). `maxspeed` y, si
  `direction` es un rumbo en grados (OSM), se descartan los que vigilan el otro sentido (más de 90°
  con la ruta). Respaldo: el DATEX de la DGT. `org.json` en las pruebas (`testImplementation`).
- `assets/radares.csv` (195 KB, 4.022: 2.330 fijos, 299 tramos, 68 móviles, 1.325 tramos de móvil)
  sacado del feed; con red, cada día. La lista «F;/T;» que guardó la 0.10.0 se tira al actualizar.
- `RadarSettings` (`data/Radars`): encendido, distancia (300/500/800/1000 m), modo (con sonido /
  sólo en pantalla) y clases (de serie, todo menos los tramos de radar móvil de la DGT). En Offline →
  Ajustes y en Android Auto (`RadarSettingsCarScreen`, desde el engranaje de la lista de puntos: en
  el mapa no caben más botones). Cambiarlos con la ruta en marcha rehace la guía.
- Android Auto: `SpeedOverlay` en el mapa del coche, abajo a la izquierda de lo visible: la velocidad
  del GPS en km/h; en rojo si el radar que viene trae límite y vas por encima.
- 36 pruebas y lint sin errores. **Probado en el emulador**: los ajustes se ven en Offline. **Sin
  probar**: la pantalla de ajustes y la velocidad en Android Auto (no hay DHU), la descarga del feed
  en el móvil.

**0.10.0 (26-nov-2026): avisos de radares en coche.** Bruno: «en el caso de conducción por coche,
¿podemos activar el aviso de radares en España?».
- Datos: la lista oficial de la DGT en el Punto de Acceso Nacional (DATEX II 1.0,
  `nap.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml`, ~2 MB): 690 cabinas
  cinemómetro (`Point`) y 47 tramos de velocidad media (`Linear`; ojo: en el XML el `to` va antes
  que el `from`). Sin sentido ni límite; sin Cataluña ni el País Vasco (tienen su propio servicio).
  La fecha de publicación del fichero bajado el 26-nov-2026 era 18-dic-2025: la DGT no lo
  actualiza a menudo.
- `core/Radars.kt`: `RadarList` (lee el DATEX con expresiones regulares; CSV compacto
  `F|T;lat;lon;latFin;lonFin;carretera`) y `RadarGuide`: sitúa cada radar sobre la ruta (a más de
  40 m no es de esa carretera; un tramo cuenta si la ruta lo recorre en su sentido) y avisa una vez a
  500 m; en los tramos, también al entrar, y dentro lo recuerda hasta el final.
- `data/Radars.kt`: copia en `assets/radares.csv` (22 KB, sacada del fichero de la DGT) para ir sin
  red desde el primer día; con red y «Usar la red», cada semana se baja el de la DGT a
  `files/radares.csv` (si trae menos de 100, no se usa). Interruptor «Avisar de radares en coche»
  en los ajustes de Offline (encendido de serie).
- Sólo yendo por una ruta en coche. Aviso: voz si hay motor y notificación del canal de avisos
  (suena y vibra; se quita sola al minuto). En la píldora, franja roja «Radar a 350 m · M-40» o «En
  tramo de radar de velocidad media · A-6»; en Android Auto, detrás de la maniobra.
- Avisar con una lista de ubicaciones es legal en España (lo prohibido son los detectores).
- 34 pruebas (2 nuevas: el DATEX real recortado en `src/test/resources/radares-muestra.xml`, y los
  avisos en una ruta con un radar fijo, otro en otra carretera y un tramo en cada sentido) y lint sin
  errores. **Probado en el emulador**: 8,2 km por la M-40 recorridos enteros → «Radar a 484 m ·
  M-40» … «85 m» y desaparece al pasarlo. La notificación no se llegó a ver (quizá ya se había
  quitado sola). **Sin probar en el móvil.**

**0.9.0 (26-nov-2026): guardar los mapas del camino al empezar.** Bruno: «cuando empiezas un viaje con
conexión, que descargue todas las teselas del camino ±500 m, por si no hay cobertura más adelante».
- `core/RouteCorridor`: las teselas que tocan una franja de ±500 m a lo largo del camino (se recorre
  a pasos de menos de media tesela y se añade el cuadrado de ±500 m de cada paso). `plan` va de z10 a
  z17 (sin pasar del máximo de cada capa) y, si pasa del tope de 100.000, quita zooms de detalle.
  Prueba: 10 km a z15 = 26 teselas, a z17 = 288.
- `Tiles`: el motor de descarga se generalizó (`run` con listas de teselas por capa y zoom; las zonas
  de Offline siguen igual, con secuencias perezosas) y `startRoute`/`saveAlong` bajan el pasillo del
  mapa que se tenga puesto. Arranca en `FollowService` al empezar a seguir (track o ruta) y al
  recalcular, sólo con «Usar la red» y red validada (`NET_CAPABILITY_VALIDATED`), y si no hay ya otra
  descarga en marcha. Progreso en la píldora («Guardando mapas del camino: 45 %») y en Offline.
- Orden de magnitud: una ruta a pie de 1,4 km son ~300 teselas; 77 km en coche, unas 2.800 (50-60 MB
  del topográfico).
- **Probado en el emulador**: ruta de Sol a Plaza de España → «Mapas del camino guardados», 306
  teselas del topográfico. **Sin probar en el móvil.**

**0.8.2 (26-nov-2026): la versión en Offline y botones para elegir cómo vas.** Bruno: «¿dónde veo la
versión en la app?» → debajo de la atribución del IGN, «IGN GPS 0.8.2» (`BuildConfig.VERSION_NAME`).
Y «que la selección de coche, bici o a pie sean botones, no sólo texto» → en «Ruta hasta…» tres
botones grandes con su icono (`FilledTonalButton`; el último usado, relleno en el azul de seguir).
**Sin probar en el móvil** (cambios sólo de aspecto).

**0.8.1 (26-nov-2026): en coche, por pistas de tierra si hace falta; y el último tramo sin camino.**
Bruno: entre Vadillo de la Sierra y Cabezas del Villar (Ávila) la ruta le dejaba a más de 4 km de un
punto. Comprobado con el servidor de BRouter: los caminos de tierra están en OpenStreetMap, pero
`car-vario` no entra en `highway=track` (a un punto en el campo acababa a 2,8-5,7 km, con rodeos de
50 km); con su opción `use_offroad` (o a pie o en bici) llega hasta donde acaba el camino.
- `Routing.route`: en coche, si la ruta acaba a más de 300 m del destino, calcula otra con
  `use_offroad=1` (`RoutingParamCollector.setProfileParams`) y se queda con la que llegue más cerca
  (100 m al menos); `PlannedRoute.offroad` lo marca y la vista previa lo dice.
- Lo que quede sin camino (más de 50 m entre el final de la ruta y el punto, `END_GAP_M`) se avisa en
  la vista previa y, al llegar al final, «Fin del camino: «X» queda a 599 m al norte» y el punto pasa a
  ser el destino de «ir hacia aquí» (flecha en línea recta).
- **Probado en el emulador** (cuadro `W10_N40`, 73 MB, bajado desde la app): de Vadillo de la Sierra a
  un punto en el campo a 2,8 km, en coche: 3,8 km por pistas, «acaba a 599 m»; recorrida entera, «Fin
  del camino» y la flecha hacia el punto. **Sin probar en el móvil.**
- El otro caso de Bruno (en La Granja de San Ildefonso, hasta «Votika 7» le llevaba por Miedes de
  Atienza) está sin ver: hacen falta las coordenadas del punto; desde el centro de Madrid al pueblo
  la ruta sale normal (77 km).

**0.8.0 (26-nov-2026): vista previa con alternativas y recalcular antes del inicio.** Lo pidió
Bruno («que recalcule aunque no hayas pasado por el inicio» y «más opciones de ruta»; de las opciones
eligió sólo las alternativas, sin ajustes por modo).
- `data/RoutePreview`: «Ruta hasta aquí» ya no empieza en el acto: la ruta sale en el mapa (línea
  azul, encuadrada) con una tarjeta abajo (`RoutePreviewCard`): hasta dónde, «Ruta N de M»,
  distancia, tiempo de BRouter y desnivel, y «Cancelar», «Otra ruta» y «Empezar» (pide el permiso
  de notificaciones). «Otra ruta» pide `alternativeidx` 1 a 3 (hasta 4 rutas; se guardan las ya
  calculadas); si BRouter devuelve una casi igual (longitud a menos del 1 % y el punto medio a menos
  de 50 m) ya no hay más y vuelve a la primera.
- Recalcular antes de llegar a la ruta: en *hacia el inicio*, si te alejas más de 50 m de lo más
  cerca que has estado de ella (`APPROACH_SLACK_M`), es que vas por otro sitio y se calcula otra
  desde donde estés (en coche la ruta suele empezar en la calle de al lado).
- **Probado en el emulador**: vista previa a pie de Sol a Plaza de España (1,4 km · 15 min · ↑ 3 m
  ↓ 19 m), cuatro alternativas distintas (1,4 a 1,5 km) y vuelta a la primera; en coche (1,9 km · 6
  min), «Empezar», alejarse 300 m al este sin llegar al inicio → ruta nueva de 2,8 km desde ahí.
  **Sin probar en el móvil.**

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
