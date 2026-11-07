import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9: Kotlin встроен — плагин org.jetbrains.kotlin.android не применяется.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

android {
    namespace = "ru.restofleet.agent"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "ru.restofleet.agent"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-mvp"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get())) }
}

dependencies {
    implementation(projects.agent.domain)
    implementation(projects.agent.data)
    implementation(projects.agent.presentation)
    implementation(projects.shared.kernel)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.essenty.instance.keeper)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.workmanager)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
}

// Каждый тестовый класс — в своём JVM. Robolectric (native runtime) в каждом sandbox, то есть при каждой смене
// SDK/конфигурации, копирует и загружает собственную копию librobolectric-nativeruntime (DefaultNativeRuntimeLoader.
// loadLibrary: System.load из загрузчика sandbox). Если в одном JVM нативку уже загрузил другой sandbox
// (AgentServiceTest на API 29/33), следующий sandbox (API 34 в WatchdogSchedulerTest) падает с
// UnsatisfiedLinkError на SQLiteConnectionNatives.nativeOpen; от порядка классов зависит, упадёт ли тест.
// Изоляция по классам делает прогон детерминированным (решение 106). Kover и `build`/`check` не затронуты.
tasks.withType<Test>().configureEach {
    forkEvery = 1
}

kover {
    currentProject {
        createVariant("ci") { add("debug") }
    }
}

detekt {
    buildUponDefaultConfig = true
}
