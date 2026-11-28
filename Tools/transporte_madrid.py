#!/usr/bin/env python3
"""
Genera app/src/main/assets/transporte_madrid.geojson: líneas y estaciones de Metro, Metro Ligero y
Cercanías de Madrid para la capa «Transporte» del mapa del móvil.

Los datos salen de los servicios abiertos del Consorcio Regional de Transportes de Madrid (CRTM,
«© CRTM»): un servicio por modo y, dentro, una capa de tramos y otra de estaciones por línea y
sentido. Las líneas se toman de un sentido (los dos van por la misma vía); las estaciones, de los dos
(alguna sólo está en uno), juntadas por su código. El CRTM no da el color de cada línea: va abajo,
el de los planos (cotejado con OpenStreetMap el 28-nov-2026). Los nombres del CRTM vienen en
mayúsculas y sin tildes: se toma el de la estación de OpenStreetMap que esté a menos de 400 m y se
llame igual sin mirar tildes (Overpass); si no hay, el del CRTM arreglado a mano.

Uso: python3 Tools/transporte_madrid.py   (con cada versión de la app, para tenerlo al día)
"""
import json
import math
import re
import unicodedata
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

BASE = "https://services5.arcgis.com/UxADft6QPcvFyDU1/arcgis/rest/services"
OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/transporte_madrid.geojson"

# modo: (servicio, prefijo de capa, colores por línea, nombre que se enseña)
MODES = {
    "metro": ("Lineas_Metro", "M4", {
        "1": "#30A3DC", "2": "#E0292F", "3": "#FFD000", "4": "#943834", "5": "#96BF0D",
        "6": "#9A9999", "7": "#FA9137", "8": "#E46CB1", "9": "#9B4192", "10": "#1E4A9C",
        "11": "#0D9E35", "12": "#A49A00", "R": "#FFFFFF",
    }, lambda l: "Ramal" if l == "R" else f"L{l}"),
    "ligero": ("M10_Lineas", "M10", {
        "1": "#4682B4", "2": "#660066", "3": "#E3312D", "4": "#98CD37",
    }, lambda l: f"ML{l}"),
    "cercanias": ("M5_Lineas", "M5", {
        "C1": "#009AD5", "C2": "#008A29", "C3": "#8B039B", "C4": "#00289C", "C5": "#FAB700",
        "C7": "#DE0118", "C8": "#76777A", "C9": "#FFA500", "C10": "#8FBE00",
    }, lambda l: f"C-{l[1:]}"),
}

# Palabras que van en minúscula al pasar los nombres (vienen en mayúsculas) a nombre propio.
LOWER = {"de", "del", "la", "las", "los", "el", "y", "e", "a", "en"}


def get(url):
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "GpsDroid-datos"})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception as e:  # el servicio de ArcGIS falla de vez en cuando
            if attempt == 3:
                raise
            print(f"  reintento ({e})", file=sys.stderr)
            time.sleep(3 * (attempt + 1))


def features(service, layer):
    url = f"{BASE}/{service}/FeatureServer/{layer}/query?where=1%3D1&outFields=*&outSR=4326&f=geojson"
    return get(url)["features"]


def nice(name):
    words = re.split(r"(\s+|-|/|\.)", name.strip().lower())
    out = []
    for i, w in enumerate(words):
        if w and w[0].isalpha() and (i == 0 or w not in LOWER):
            w = w[0].upper() + w[1:]
        out.append(w)
    s = "".join(out)
    return re.sub(r"\bT(\d)", r"T\1", s)


OVERPASS = "https://overpass-api.de/api/interpreter"
OSM_QUERY = """[out:json][timeout:120];
area["name"="Comunidad de Madrid"]["boundary"="administrative"]->.a;
(node(area.a)["railway"~"^(station|halt|stop|tram_stop)$"];
 node(area.a)["public_transport"="station"];);
out tags center;"""


def plain(s):
    s = unicodedata.normalize("NFD", s.lower())
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def osm_names():
    data = urllib.parse.urlencode({"data": OSM_QUERY}).encode()
    req = urllib.request.Request(OVERPASS, data=data, headers={"User-Agent": "GpsDroid-datos"})
    with urllib.request.urlopen(req, timeout=180) as r:
        els = json.load(r)["elements"]
    out = []
    for e in els:
        name = e.get("tags", {}).get("name")
        if name and "lat" in e:
            out.append((e["lon"], e["lat"], name, plain(name)))
    print(f"OpenStreetMap: {len(out)} estaciones con nombre", file=sys.stderr)
    return out


def fix_name(crtm, coords, osm):
    """El nombre bien escrito: el de OSM si casa (sin tildes) cerca; si no, el del CRTM arreglado."""
    base = nice(crtm)
    m = re.match(r"^(.*), (el|la|los|las)$", base, re.I)  # «Goloso, el» → «El Goloso»
    if m:
        base = f"{m.group(2).capitalize()} {m.group(1)}"
    key = plain(re.sub(r"(?i)^avda\.? ", "Avenida ", base)).removeprefix("estacion de ")
    lon, lat = coords
    best = None
    for olon, olat, name, okey in osm:
        d = math.hypot((olon - lon) * 84_000, (olat - lat) * 111_000)
        if d < 400 and okey.removeprefix("estacion de ") == key and (best is None or d < best[0]):
            best = (d, name)
    return best[1] if best else base


def simplify(pts, tol):
    """Douglas-Peucker en grados (tol ~ 1e-5 = 1 m): las vías no necesitan un punto cada metro."""
    if len(pts) < 3:
        return pts
    (x1, y1), (x2, y2) = pts[0], pts[-1]
    dx, dy = x2 - x1, y2 - y1
    norm = (dx * dx + dy * dy) ** 0.5 or 1e-12
    worst, idx = 0.0, 0
    for i in range(1, len(pts) - 1):
        x, y = pts[i]
        d = abs(dy * x - dx * y + x2 * y1 - y2 * x1) / norm
        if d > worst:
            worst, idx = d, i
    if worst <= tol:
        return [pts[0], pts[-1]]
    return simplify(pts[: idx + 1], tol)[:-1] + simplify(pts[idx:], tol)


def rnd(p):
    return [round(p[0], 5), round(p[1], 5)]


def line_key(prefix, name):
    """M4_L10a_S1_TRAMO → ('10', 'S1', 'TRAMO'); M5_LC4b_S2_ESTACION → ('C4', 'S2', 'ESTACION')."""
    m = re.match(rf"{prefix}_L([A-Z]?\d+|R)[a-zA-Z]?_(S\d)_([A-Z]+)$", name)
    return (m.group(1), m.group(2), m.group(3)) if m else None


def main():
    osm = osm_names()
    out = []
    for mode, (service, prefix, colors, label) in MODES.items():
        layers = get(f"{BASE}/{service}/FeatureServer?f=json")["layers"]
        stations = {}
        lines_done = set()
        for lay in sorted(layers, key=lambda l: (line_key(prefix, l["name"]) or ("", "", ""))[1]):
            key = line_key(prefix, lay["name"])
            if not key:
                continue
            line, sense, kind = key
            if line not in colors:
                print(f"  {lay['name']}: línea sin color, se salta", file=sys.stderr)
                continue
            if not kind.startswith("TRAMO"):
                continue
            # Las estaciones salen de los tramos (de los dos sentidos): cada tramo acaba en la estación
            # que nombra. Las capas «ESTACION» no sirven: la de la L1 (S1) trae estaciones de otras líneas.
            # La vía, de un sentido; si una variante (a/b) sólo tiene S2, de ése.
            variant = lay["name"].split("_")[1]
            draw = sense == "S1" or (variant, "S1") not in lines_done
            lines_done.add((variant, sense))
            print(f"{mode} {lay['name']}", file=sys.stderr)
            for f in features(service, lay["id"]):
                g, p = f.get("geometry"), f["properties"]
                if not g:
                    continue
                parts = [g["coordinates"]] if g["type"] == "LineString" else g["coordinates"]
                name = p.get("DENOMINACION")
                if name and parts and parts[-1]:
                    key = (name, sense, variant, p.get("CODIGOESTACION"))
                    st = stations.setdefault(key, {"name": name, "coords": rnd(parts[-1][-1]), "lines": []})
                    if label(line) not in st["lines"]:
                        st["lines"].append(label(line))
                if not draw:
                    continue
                for part in parts:
                    pts = [rnd(p) for p in simplify(part, 2e-5)]
                    if len(pts) >= 2:
                        out.append({"type": "Feature", "geometry": {"type": "LineString", "coordinates": pts},
                                    "properties": {"k": "l", "mode": mode, "line": label(line), "color": colors[line]}})
        # El CRTM da un código por línea: Nuevos Ministerios sale tres veces. Mismo nombre a menos de
        # 600 m = una estación, con todas sus líneas (los andenes de la L8 en Nuevos Ministerios están a 450 m).
        merged = []
        for st in stations.values():
            st["name"] = fix_name(st["name"], st["coords"], osm)
            twin = next((m for m in merged if m["name"] == st["name"] and math.hypot(
                (m["coords"][0] - st["coords"][0]) * 84_000, (m["coords"][1] - st["coords"][1]) * 111_000) < 600), None)
            if twin:
                twin["lines"] += [l for l in st["lines"] if l not in twin["lines"]]
            else:
                merged.append(st)
        for st in merged:
            st["lines"].sort(key=lambda l: (len(l), l))
            out.append({"type": "Feature", "geometry": {"type": "Point", "coordinates": st["coords"]},
                        "properties": {"k": "s", "mode": mode, "name": st["name"], "lines": " ".join(st["lines"])}})
        print(f"{mode}: {len(merged)} estaciones", file=sys.stderr)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"type": "FeatureCollection", "features": out}, ensure_ascii=False, separators=(",", ":")))
    print(f"{OUT}: {len(out)} elementos, {OUT.stat().st_size // 1024} KB", file=sys.stderr)


if __name__ == "__main__":
    main()
