import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    android {
        namespace = "ru.restofleet.console.core.testing"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get())) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get())) }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.kernel)
            api(projects.shared.protocol)
            api(projects.console.core.common)
            api(libs.kotlinx.coroutines.test)
            api(libs.ktor.client.core)
            api(libs.ktor.client.mock)
            api(libs.ktor.client.content.negotiation)
            api(libs.ktor.serialization.kotlinx.json)
            api(libs.decompose)
        }
        jvmTest.dependencies {
            implementation(libs.junit4)
            implementation(libs.truth)
        }
    }
}

kover {
    currentProject {
        createVariant("ci") { add("jvm") }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"), rootProject.file("console/config/detekt.yml"))
}

// В KMP анализ идёт по source set'ам; `detekt` собирает их все (CI вызывает именно его)
tasks.named("detekt") {
    dependsOn(tasks.matching { it.name.startsWith("detekt") && it.name.endsWith("SourceSet") && !it.name.startsWith("detektBaseline") })
}
