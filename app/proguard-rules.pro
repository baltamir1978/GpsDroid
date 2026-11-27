# MapLibre y las corrutinas traen sus propias reglas; de momento no hace falta nada más.

# BRouter: el motor lee los perfiles con su propio intérprete y crea clases por nombre en algún
# sitio; mejor no dejar que R8 recorte ni renombre nada suyo.
-keep class btools.** { *; }
-dontwarn btools.**
