# GpsDroid

GPS de montaña para Android con los **mapas del IGN** (topográfico, ortofoto PNOA e IGN Base):
tu posición, los satélites, tracks GPX y zonas descargadas para usar sin cobertura. **Sólo para
España**, que es lo que cubren los mapas. En el móvil se llama **IGN GPS**.

Es la versión para Android de [ZeroGPS](https://github.com/baltamir1978/ZeroGPS), la app del Cap
LoRa-1262 para el M5Stack Cardputer Zero, escrita desde cero en Kotlin + Jetpack Compose. Funciona
sin Google Play (GrapheneOS): la posición sale del GPS del móvil con `LocationManager`.

**Estado: 0.1.2** (22-nov-2026). Ver `CLAUDE.md`.

## Qué hace

- **Mapa**: teselas del IGN (botón de capas: Topográfico → Satélite → IGN Base), tu posición con
  el círculo de precisión y la flecha del rumbo, el track que se graba (rojo) y el que abras desde
  Tracks (morado). Sigue tu posición hasta que mueves el mapa; el botón de la diana lo recentra.
  Recuerda dónde lo dejaste y la última posición. «Online / Sin red» decide si se bajan las
  teselas que falten o sólo se ve lo guardado.
- **Satélites**: el cielo con los satélites por constelación (GPS, Galileo, GLONASS, BeiDou…;
  relleno = usado en el fix) y el estado: fix, coordenadas, altitud, precisión, velocidad, rumbo.
- **Tracks**: grabar (sigue con la pantalla apagada; notificación con «Parar»), lista con
  distancia, duración y desnivel, abrir en el mapa, compartir el GPX, borrar e importar GPX de
  otras apps.
- **Offline**: descargar una zona alrededor del centro del mapa o de tu posición (1–20 km) o una
  sierra entera, de uno de los mapas o de los tres, eligiendo los zooms; dice antes cuántas
  teselas son, lo que ocupan y el espacio libre. Tope de 100.000 teselas por descarga.

Mapas: © Instituto Geográfico Nacional de España, [CC BY 4.0](https://www.ign.es/resources/licencia/Condiciones_licenciaUso_IGN.pdf).

## Compilar

```sh
Tools/instalar-entorno.sh       # JDK 17, Android SDK y Gradle en ~/Android, sin sudo
./gradlew testDebugUnitTest lintDebug assembleRelease
```

Hace falta [droid-common](https://github.com/baltamir1978/droid-common) clonado en la carpeta de al
lado. No necesita claves de ninguna API.
