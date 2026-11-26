pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // El motor de rutas de BRouter (MIT): no está en Maven Central; JitPack lo compila de sus
        // etiquetas de GitHub. Sólo para él, para que nada más venga de ahí.
        maven("https://jitpack.io") { content { includeGroup("com.github.abrensch.brouter") } }
    }
}
rootProject.name = "GpsDroid"
include(":app")

// Lo que comparten las apps (modo escritorio): el repositorio droid-common, clonado en la carpeta
// de al lado. Gradle sustituye `es.altamirano:common` por él.
includeBuild("../droid-common")
