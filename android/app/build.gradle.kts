plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La clave de firma es SIEMPRE la misma (viene de los "secrets" de GitHub): así cada versión nueva se instala
// encima de la anterior sin desinstalar. Sin clave (compilando en otra máquina) se firma con la de prueba.
val claveArchivo = System.getenv("MORSIS_KEYSTORE")?.let { file(it) }

android {
    namespace = "ar.com.ferreteriadnjr.morsis"
    compileSdk = 34

    defaultConfig {
        applicationId = "ar.com.ferreteriadnjr.morsis"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // Solo celulares con procesador ARM (todos los Samsung): el instalador pesa la mitad.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (claveArchivo != null && claveArchivo.exists()) {
            create("morsis") {
                storeFile = claveArchivo
                storePassword = System.getenv("MORSIS_KEYSTORE_PASS")
                keyAlias = "morsis"
                keyPassword = System.getenv("MORSIS_KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("morsis") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
}
