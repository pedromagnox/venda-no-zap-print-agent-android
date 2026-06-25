import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// FCM só liga quando o google-services.json existir (projeto Firebase criado).
// Sem o arquivo o app builda e roda normal, com sync por eventos/manual.
val hasGoogleServices = file("google-services.json").exists()
if (hasGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
}

// Assinatura de release: lê keystore.properties (gitignored, fora do repo). Sem
// ele a release sai sem assinar (fresh clone ainda builda). A MESMA keystore tem
// que assinar TODA release — senão o updater não consegue atualizar por cima
// (Android recusa update com chave diferente).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    namespace = "app.vendanozap.printagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.vendanozap"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.1.11"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            // minify/shrink desligados de propósito: a release se comporta igual
            // ao debug já verificado — sem risco do R8 remover serializers
            // (kotlinx.serialization) ou reflection. Tamanho do APK é irrelevante.
            isMinifyEnabled = false
            isShrinkResources = false
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
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Compila sempre; em runtime é no-op até o google-services.json existir.
    implementation("com.google.firebase:firebase-messaging:24.0.2")
}
