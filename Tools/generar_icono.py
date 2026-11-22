#!/usr/bin/env python3
"""Icono del lanzador: el mismo mapa plegado con chincheta de ZeroGPS (../ZeroGPS/Tools/
generar_icono.py), pero a sangre completa y con el dibujo dentro de la zona segura del icono
adaptativo (el 66 % central), porque cada lanzador recorta su forma. Necesita Pillow y ZeroGPS
clonado al lado.

    Tools/generar_icono.py
"""

import importlib.util
import os

from PIL import Image

RAIZ = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
LADO = 432  # 108 dp a xxxhdpi


def main():
    ruta = os.path.join(RAIZ, "..", "ZeroGPS", "Tools", "generar_icono.py")
    spec = importlib.util.spec_from_file_location("icono_zerogps", ruta)
    zero = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(zero)

    original = zero.icono()  # 1024 px, cuadrado redondeado con margen de 40
    # Fondo: el mismo degradado azul petróleo, a todo el cuadrado.
    arriba, abajo = (32, 72, 88), (12, 28, 38)
    fondo = Image.new("RGBA", (1, LADO))
    for y in range(LADO):
        t = y / (LADO - 1)
        fondo.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(arriba, abajo)) + (255,))
    fondo = fondo.resize((LADO, LADO))
    # El dibujo, sin el marco redondeado, al 75 % y centrado.
    dibujo = original.crop((110, 110, zero.LADO - 110, zero.LADO - 110))
    lado_dibujo = int(LADO * 0.75)
    dibujo = dibujo.resize((lado_dibujo, lado_dibujo), Image.LANCZOS)
    salida = fondo
    salida.alpha_composite(dibujo, ((LADO - lado_dibujo) // 2, (LADO - lado_dibujo) // 2))
    salida.convert("RGB").save(os.path.join(RAIZ, "app/src/main/res/drawable-nodpi/ic_launcher_full.png"))


if __name__ == "__main__":
    main()
