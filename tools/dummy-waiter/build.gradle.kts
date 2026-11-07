import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Тестовый артефакт для D3 (тихая установка) и R7/R12 (события пакетов). Версия задаётся -PdummyVersionCode=N.
// AGP 9: Kotlin встроен — плагин org.jetbrains.kotlin.android не применяется.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
}

val dummyVersionCode: Int = (findProperty("dummyVersionCode") as String?)?.toInt() ?: 1

android {
    namespace = "ru.restofleet.dummy.waiter"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "ru.restofleet.dummy.waiter"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = dummyVersionCode
        versionName = "4.$dummyVersionCode"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get())) }
}

detekt {
    buildUponDefaultConfig = true
}
