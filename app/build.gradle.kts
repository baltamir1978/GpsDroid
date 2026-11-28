import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "es.altamirano.gps"
    compileSdk = 37

    defaultConfig {
        applicationId = "es.altamirano.gps"
        minSdk = 31          // Android 12
        targetSdk = 36
        versionCode = 49
        versionName = "0.31.1"
    }

    // La clave de release vive fuera del repositorio; sin ella la release sale sin firmar.
    val keyFile = File(System.getProperty("user.home"), ".android/claves/gps-release.properties")
    val releaseKey = if (keyFile.exists()) Properties().apply { keyFile.inputStream().use { load(it) } } else null
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = File(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        // MapLibre trae código nativo: en depuración, el del Pixel y el del emulador; en la release sólo
        // el del Pixel (el AAB tiene que bajar de 10 MB para subirlo a Play desde el navegador).
        debug { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
        release {
            ndk { abiFilters += listOf("arm64-v8a") }
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("es.altamirano:common")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.coroutines.android)
    implementation(libs.brouter.core)   // rutas sin conexión, dentro de la app
    implementation(libs.car.app)
    implementation(libs.maplibre)      // mapa vectorial del IGN en el coche
    implementation(libs.work.runtime)   // cuadros de rutas al día en segundo plano
    testImplementation(libs.junit)
    // org.json viene con Android, pero en las pruebas de JVM sólo hay esqueletos: la de verdad para leer el GeoJSON de radares.
    testImplementation("org.json:json:20240303")
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}
