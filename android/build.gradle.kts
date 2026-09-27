plugins {
    id("com.android.application") version "9.4.1" apply false
    // kotlin("android") retire : AGP 9 fournit son propre support Kotlin
    // integre (builtInKotlin) et le plugin classique est incompatible
    // avec ses classes internes. Voir android/app/build.gradle.kts.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
