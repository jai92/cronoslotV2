plugins {
    id("com.android.application")
}

android {
    namespace = "com.cronoslot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cronoslot"
        minSdk = 24
        targetSdk = 34
        versionCode = 51
        versionName = "5.1"
    }

    // Firma fija: así cada APK nueva se instala ENCIMA de la anterior sin desinstalar
    // (y sin perder pilotos, coches, circuitos ni registros).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
}
