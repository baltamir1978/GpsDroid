package es.altamirano.gps.core

/**
 * Los iconos de puntos del Mapa Base vectorial del CNIG (callejero e híbrido), por categorías que se
 * encienden y apagan en la pestaña «Puntos». El estilo trae ~85 capas de iconos (antenas, torres
 * eléctricas, depuradoras…) y Bruno sólo quiere unas pocas: «como mucho hospitales, farmacias,
 * iglesias y poco más». Farmacias no hay: el CNIG no las tiene.
 *
 * Una categoría reúne capas del estilo por su `id` (el prefijo hasta un «-» o el final). Las capas que
 * no casan con ninguna (calles, pueblos, ríos, el texto genérico de instalaciones) no se tocan. Si el
 * CNIG cambia los nombres, lo nuevo se ve (sin categoría, no se oculta) y `MapPoisTest` lo avisa con
 * la lista de capas guardada.
 */
enum class PoiGroup(val label: String) {
    HEALTH("Sanidad y seguridad"),
    RELIGION("Religión"),
    PUBLIC("Servicios públicos"),
    TRANSPORT("Transporte"),
    ROAD("Carreteras"),
    CULTURE("Cultura y turismo"),
    LEISURE("Ocio, deporte y naturaleza"),
    INDUSTRY("Industria e infraestructuras"),
}

enum class PoiCategory(val key: String, val label: String, val group: PoiGroup, val byDefault: Boolean, prefixes: List<String>) {
    HEALTH_CARE("sanidad", "Hospitales y centros de salud", PoiGroup.HEALTH, true, sip("instalacion_sanitaria")),
    EMERGENCY("emergencias", "Emergencias y bomberos", PoiGroup.HEALTH, true, sip("emergencias")),
    POLICE("policia", "Policía y Guardia Civil", PoiGroup.HEALTH, true, sip("orden_publico_seguridad")),

    CHURCH("religion", "Iglesias, ermitas y otros templos", PoiGroup.RELIGION, true, sip("instalacion_religiosa")),

    TOWN_HALL("ayuntamiento", "Ayuntamientos", PoiGroup.PUBLIC, false, sip("ayuntamiento")),
    SCHOOL("educacion", "Colegios e institutos", PoiGroup.PUBLIC, false, sip("instalacion_educativa")),
    CEMETERY("cementerio", "Cementerios", PoiGroup.PUBLIC, false, sip("cementerio")),
    PRISON("carcel", "Cárceles", PoiGroup.PUBLIC, false, sip("centro_penitenciario")),

    TRAIN("tren", "Estaciones de tren", PoiGroup.TRANSPORT, true, listOf("estacion_ferrocarril_pto")),
    BUS("autobus", "Estaciones de autobús", PoiGroup.TRANSPORT, true, listOf("estacion_autobus_pto")),
    AIRPORT("aeropuerto", "Aeropuertos y helipuertos", PoiGroup.TRANSPORT, false, listOf("aerodromo_pto")),
    HARBOUR("puerto", "Puertos", PoiGroup.TRANSPORT, false, listOf("puerto_pto")),
    PARKING("aparcamiento", "Aparcamientos", PoiGroup.TRANSPORT, false, listOf("aparcamiento_pto")),

    SERVICE_AREA("area_servicio", "Áreas de servicio y de descanso", PoiGroup.ROAD, true, listOf("area_servicio_pto")),
    ROAD_NUMBER("numero_carretera", "Número de la carretera", PoiGroup.ROAD, true,
        listOf("autopista_lin-simbolo", "autovia_lin-simbolo", "carretera_general_lin-simbolo",
            "carretera_autonomica_1er_nivel_lin-simbolo", "carretera_autonomica_2do_nivel_lin-simbolo",
            "carretera_autonomica_3er_nivel_lin-simbolo")),
    KILOMETER("pk", "Puntos kilométricos", PoiGroup.ROAD, false, listOf("carretera_pk_pto", "ferrocarril_pk_pto")),
    TOLL("peaje", "Peajes", PoiGroup.ROAD, false, listOf("peaje_pto")),
    LEVEL_CROSSING("paso_nivel", "Pasos a nivel", PoiGroup.ROAD, false, listOf("paso_a_nivel_pto")),

    MUSEUM("museo", "Museos, teatros y auditorios", PoiGroup.CULTURE, false, sip("museo") + sip("teatro_auditorio")),
    CASTLE("castillo", "Castillos", PoiGroup.CULTURE, false, sip("castillo_fortaleza")),
    MONUMENT("monumento", "Monumentos y fuentes", PoiGroup.CULTURE, false, sip("monumento")),
    HERITAGE("patrimonio", "Patrimonio, yacimientos y zonas protegidas", PoiGroup.CULTURE, false, listOf("zona_protegida_pto")),
    INTERPRETATION("interpretacion", "Centros de interpretación", PoiGroup.CULTURE, false, sip("centro_interpretacion")),
    LIGHTHOUSE("faro", "Faros", PoiGroup.CULTURE, false, listOf("servicio_instalacion_pto-simbolo-faro")),

    CAMPING("camping", "Campings", PoiGroup.LEISURE, false, sip("camping")),
    SKI("esqui", "Estaciones de esquí", PoiGroup.LEISURE, false, sip("estacion_invernal")),
    WATERFALL("cascada", "Cascadas", PoiGroup.LEISURE, false, listOf("nombre_hidrografia_pto-cascada")),
    TREE("arbol", "Árboles singulares", PoiGroup.LEISURE, false, listOf("espacio_natural_protegido_pol-texto-arbol_singular")),
    PARK("parque", "Parques, jardines y zonas recreativas", PoiGroup.LEISURE, false,
        sip("parque") + sip("jardin") + sip("zona_verde") + sip("zona_recreativa") + sip("terreno_natural")),
    SPORT("deporte", "Campos de fútbol y pistas deportivas", PoiGroup.LEISURE, false,
        sip("campo_futbol") + sip("pista_deportiva") + sip("instalacion_deportiva")),
    GOLF("golf", "Golf y circuitos", PoiGroup.LEISURE, false, sip("campo_golf") + sip("circuito")),
    THEME_PARK("ocio", "Parques temáticos y recintos feriales", PoiGroup.LEISURE, false, sip("parque_tematico_ocio") + sip("recinto_ferial")),

    ANTENNA("antena", "Antenas y telecomunicaciones", PoiGroup.INDUSTRY, false,
        sip("antena") + sip("instalacion_telecomunicacion") + sip("torre_transporte")),
    POWER("electricidad", "Torres y líneas eléctricas", PoiGroup.INDUSTRY, false,
        sip("torre_electrica") + listOf("conduccion_lin-simbolo-energia")),
    WIND("aerogenerador", "Aerogeneradores", PoiGroup.INDUSTRY, false, sip("aerogenerador")),
    ENERGY("energia", "Centrales y otras instalaciones de energía", PoiGroup.INDUSTRY, false, sip("instalacion_energia")),
    WATER("agua", "Depuradoras, residuos, bombeo y piscifactorías", PoiGroup.INDUSTRY, false,
        sip("instalacion_tratamiento_aguas") + sip("instalacion_tratamiento_residuos") + sip("estacion_bombeo") + sip("piscifactoría")),
    // «texto-faro» es, en el estilo del CNIG, el rótulo de las minas (su filtro es explotacion_minera).
    MINE("mina", "Minas y canteras", PoiGroup.INDUSTRY, false, sip("explotacion_minera") + listOf("servicio_instalacion_pto-texto-faro")),
    INDUSTRIAL("industria", "Polígonos, zonas industriales y comerciales", PoiGroup.INDUSTRY, false,
        sip("poligono_industrial") + sip("zona_industrial") + sip("zona_comercial") + sip("parque_tecnologico") + sip("zona_dotacional")),
    MILITARY("militar", "Instalaciones militares", PoiGroup.INDUSTRY, false, sip("instalacion_militar")),
    OTHER("otros", "Otras instalaciones", PoiGroup.INDUSTRY, false, sip("otros_servicios_instalaciones")),
    ;

    internal val prefixes: List<String> = prefixes

    companion object {
        val DEFAULT: Set<PoiCategory> = entries.filter { it.byDefault }.toSet()

        /** La categoría de una capa del estilo, o null si no es un icono de punto (entonces no se toca). */
        fun of(layerId: String): PoiCategory? {
            val id = layerId.trim()   // alguna trae un espacio al final («…recinto_ferial »)
            return entries.firstOrNull { c -> c.prefixes.any { p -> id == p || id.startsWith("$p-") } }
        }

        /** Las elegidas, guardadas como claves separadas por comas; null (nunca tocado) = las de serie. */
        fun parse(saved: String?): Set<PoiCategory> =
            if (saved == null) DEFAULT else saved.split(',').mapNotNull { k -> entries.firstOrNull { it.key == k } }.toSet()

        fun format(set: Set<PoiCategory>): String = entries.filter { it in set }.joinToString(",") { it.key }
    }
}

/** Las capas de una clase de `servicio_instalacion_pto`: el icono (con su nombre) y, si lo hay, el rótulo aparte. */
private fun sip(clase: String) = listOf("servicio_instalacion_pto-simbolo-$clase", "servicio_instalacion_pto-texto-$clase")
