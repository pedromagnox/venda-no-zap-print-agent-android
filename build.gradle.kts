plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}

// O projeto vive numa pasta sincronizada pelo OneDrive, que segura locks nos
// milhares de arquivos intermediários e quebra o build (AccessDenied). Build
// roda fora do sync; o APK final é copiado pra dist/ (um arquivo só).
allprojects {
    layout.buildDirectory.set(file("C:/Android/builds/vnz-print-agent/${project.name}"))
}
