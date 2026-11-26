# GpsDroid (IGN GPS)

GPS de montaña para Android con los **mapas del Instituto Geográfico Nacional**: topográfico,
ortofoto, híbrido e IGN Base, tracks GPX y mapas para cuando no hay cobertura. Es la versión para
el móvil de [ZeroGPS](https://github.com/baltamir1978/ZeroGPS), la app del Cardputer Zero.

Funciona **sin Google Play** (hecha para GrapheneOS): la posición sale del GPS del móvil, sin
ubicación por red ni servicios de Google.

## Qué hace

- **Mapa** a pantalla completa con cuatro estilos:
  - **Topográfico**: el MTN 1:25.000 (hasta z17).
  - **Satélite**: la ortofoto PNOA (hasta z19).
  - **Híbrido**: la ortofoto con los nombres, calles y carreteras del IGN encima.
  - **IGN Base**: el mapa base vectorial (hasta z18).

  Tu posición con su precisión y, si te mueves, hacia dónde vas; seguir la posición, el nivel de
  zoom, la escala, y un botón para no usar la red (sólo lo guardado, sin gastar datos). En modo escritorio, la
  rueda del ratón acerca y aleja por donde está el cursor.
- **Satélites**: el cielo con los satélites a la vista y los que se usan, por constelación, y el
  fix (coordenadas, altitud, precisión). Las altitudes son **sobre el nivel del mar**, como en
  los mapas (el GPS da la altura sobre el elipsoide, unos 50 m más en España; desde Android 14 se
  convierte). Los puntos guardados en el mapa toman la altitud del modelo del terreno Copernicus
  (por la API de Open-Meteo, si se usa la red).
- **Tracks**: grabar por dónde vas (sigue con la pantalla apagada; se guarda cada minuto),
  importar GPX de otras apps, compartirlos y borrarlos. Al tocar uno se abre su **ficha**:
  distancia, tiempo total y en movimiento, velocidad media y ritmo, subida y bajada, altitud
  máxima y mínima y el **perfil de altitud** (se recorre con el dedo). Desde ella, «Ver en el mapa».
  **Seguir un track** (o seguirlo al revés), desde su ficha o su menú: te guía por él con lo que
  queda, el desnivel que falta (↑ ↓) y la hora de llegada al ritmo que llevas; lo andado se pinta
  más claro. Si te sales, la píldora del mapa se pone roja con la distancia y una flecha hacia el
  punto más cercano del track, y el móvil **vibra y suena aunque esté en el bolsillo** (también al
  llegar al final). Antes de llegar al track dice a qué distancia empieza. Se puede seguir un track
  y grabar a la vez.
  **Copia de todo**: guarda todos los tracks y los puntos en un ZIP donde elijas (los datos viven
  dentro de la app y se perderían al desinstalarla).
- **Puntos**: mantén pulsado el mapa (o toca el botón verde para tu posición) y guárdalo con un
  nombre. Al tocar uno: **ir hacia él** (arriba en el mapa sale una flecha que apunta hacia el
  punto con la brújula, la distancia y hacia dónde queda: «1,2 km al NE · a tu derecha», y una
  línea discontinua hasta él), renombrarlo, borrarlo o abrirlo en otra app de mapas. En Tracks
  salen todos, los más cerca primero, con su **altitud** y cuánto hay que subir o bajar desde donde
  estás («Altitud 1.245 m · 230 m más arriba»); se comparten en un GPX; al importar un GPX con puntos
  (`<wpt>`) se añaden.
- **Rutas** («Ruta hasta aquí» en la ficha de un punto): a pie, en bici o en coche, por caminos y
  carreteras, calculadas **sin conexión** por [BRouter](https://f-droid.org/packages/btools.routingapp/)
  (app aparte, libre, con los mapas de OpenStreetMap que te bajas por zonas en su «Download
  Manager»). Indicaciones giro a giro en el mapa y **en voz alta** (hace falta un motor de voz; en
  GrapheneOS, RHVoice o eSpeak NG), y si te sales calcula otra desde donde estés.
- **Android Auto**: el mapa del IGN en el coche, con tu posición, capas, zoom, arrastrar, el track
  que se graba, los puntos, guardar un punto donde estás, grabar, la tarjeta del destino (flecha,
  distancia y a qué mano queda) el track que se sigue (lo que queda o, fuera de él, hacia dónde volver) y, yendo por una ruta, cada maniobra como en un navegador. En GrapheneOS necesita Google Play aislado y Android Auto; y, como
  la app no viene de Google Play, hay que activar **«Fuentes desconocidas»** en los ajustes de
  desarrollador de Android Auto (se abren tocando varias veces la versión en sus ajustes).
- **Mapas offline**: bajar una zona (alrededor del centro del mapa o de tu posición, o una sierra
  entera: Guadarrama, Gredos, Picos de Europa, Sierra Nevada…), uno de los mapas o todos, del
  zoom que elijas. Antes dice cuántas teselas son y lo que ocupará; lo bajado no se vuelve a pedir,
  así que repetir una descarga la reanuda. Tope de 100.000 teselas por descarga: el IGN es un
  servicio público.

## Compilar

```sh
export JAVA_HOME=~/Android/jdk ANDROID_HOME=~/Android/Sdk
./gradlew testDebugUnitTest lintDebug
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Necesita `../droid-common` clonado al lado (tema y piezas comunes de las apps).

## Mapas

© Instituto Geográfico Nacional de España, CC BY 4.0 (la atribución se ve siempre en el mapa).
