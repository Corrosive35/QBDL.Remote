plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Clés de signature lues depuis ~/.gradle/gradle.properties ou passées en -P par release.yml,
// jamais depuis le dépôt. Sans elles, la config release n'est pas créée du tout et
// assembleRelease produit un APK non signé plutôt que d'échouer.
val keystorePath: String? = providers.gradleProperty("QDLR_STORE_FILE").orNull

android {
    namespace = "com.qobuzdl.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qobuzdl.remote"
        // 26 : plancher des icônes adaptatives, et en deçà le WebView embarqué est souvent trop
        // ancien pour 100dvh (Chrome 108+) et env(safe-area-inset-*), que le CSS utilise déjà.
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.2.1"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.gradleProperty("QDLR_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("QDLR_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("QDLR_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // Coquille WebView : aucune réflexion, rien à obscurcir, et l'APK pèse déjà moins de
            // 5 Mo. R8 n'apporterait ici qu'une classe de pannes difficiles à diagnostiquer.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
        viewBinding = true
        // AGP 8 ne génère plus BuildConfig par défaut ; MainActivity s'en sert pour n'activer le
        // débogage du WebView qu'en debug.
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.material)
}
