plugins {
    id("com.android.application")
    // kotlin("android") retire : le support Kotlin integre d AGP 9 le
    // remplace (voir android/build.gradle.kts pour le detail).
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fr.riftbound.scanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.riftbound.scanner"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        create("fixe") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "riftbound"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixe")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

dependencies {
    implementation("fr.riftbound:riftbound-core")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
