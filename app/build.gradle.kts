import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Lokale Angaben (nicht im Repo): local.properties im Wurzelordner
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// Wert aus Umgebung (CI) oder local.properties, leer = nicht gesetzt
fun setting(env: String, prop: String): String? =
    (System.getenv(env) ?: localProps.getProperty(prop))?.takeIf { it.isNotBlank() }

// Versionsnummer aus CI (GITHUB_RUN_NUMBER), lokal 1, plus Versatz.
// Der Versatz liegt ueber allen bisher verteilten Builds: versionCode darf nie sinken, sonst verweigert
// Android das Update ("App nicht installiert"). Setzen per -PversionOffset=…, Umgebung VERSION_OFFSET
// (CI: Repository-Variable VERSION_OFFSET) oder local.properties versionOffset.
val versionOffset = ((project.findProperty("versionOffset") as String?)
    ?: setting("VERSION_OFFSET", "versionOffset") ?: "100").trim().toInt()
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt() + versionOffset

// Play Store: Upload-Schluessel kommt aus der Umgebung (CI-Secrets), nie aus dem Repo.
// UPLOAD_KEYSTORE = Pfad zur .jks, dazu UPLOAD_KEYSTORE_PASSWORD, UPLOAD_KEY_ALIAS, UPLOAD_KEY_PASSWORD.
val uploadKeystore: String? = System.getenv("UPLOAD_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }

// Optionaler Sideload-Schluessel, damit selbst gebaute APKs sich ueber die vorige Version installieren.
// Umgebung SIDELOAD_KEYSTORE (Pfad), SIDELOAD_KEYSTORE_PASSWORD, SIDELOAD_KEY_ALIAS, SIDELOAD_KEY_PASSWORD
// oder local.properties sideload.keystore, sideload.storePassword, sideload.keyAlias, sideload.keyPassword.
// Ohne Angaben: Release unsigniert, Debug mit dem Standard-Debug-Schluessel.
val sideloadKeystore: File? = setting("SIDELOAD_KEYSTORE", "sideload.keystore")
    ?.let { rootProject.file(it) }?.takeIf { it.exists() }
val sideloadStorePassword = setting("SIDELOAD_KEYSTORE_PASSWORD", "sideload.storePassword")

android {
    namespace = "de.camperflower.homehyrax"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.camperflower.homehyrax"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        // nur Handy-Prozessoren (spart ~10 MB); fuer den Emulator x86_64 ergaenzen
        // -Pabis=arm64-v8a baut eine kleinere APK nur fuer 64-Bit-Handys (Sideload)
        ndk { abiFilters += ((project.findProperty("abis") as String?)?.split(",") ?: listOf("arm64-v8a", "armeabi-v7a")) }
    }

    signingConfigs {
        if (sideloadKeystore != null && sideloadStorePassword != null) create("sideload") {
            storeFile = sideloadKeystore
            storePassword = sideloadStorePassword
            // Standard-Alias = Alias im vorhandenen Sideload-Schluessel (stammt vom frueheren App-Namen)
            keyAlias = setting("SIDELOAD_KEY_ALIAS", "sideload.keyAlias") ?: "hometunnel"
            keyPassword = setting("SIDELOAD_KEY_PASSWORD", "sideload.keyPassword") ?: sideloadStorePassword
        }
        if (uploadKeystore != null) create("upload") {
            storeFile = file(uploadKeystore)
            storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
            keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName(if (uploadKeystore != null) "upload" else "sideload")
        }
        debug {
            signingConfigs.findByName("sideload")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    // WireGuard + Proxy (Go, via gomobile gebaut: ./build-go.sh)
    implementation(files("libs/wgbridge.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric bringt Fragment 1.2 mit - zu alt fuer registerForActivityResult
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    // Kamera: RTSP-Livebild (ExoPlayer + RTSP + PlayerView)
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}
