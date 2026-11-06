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
        namespace = "ru.restofleet.agent.domain"
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
            api(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(libs.junit4)
            implementation(libs.truth)
            implementation(libs.kotlin.test)
        }
    }
}

kover {
    currentProject {
        // Вариант для агрегированного CI-гейта: в KMP-модулях тесты и код меряются на jvm-таргете
        createVariant("ci") { add("jvm") }
    }
    reports {
        verify {
            rule { minBound(90) }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
}

// В KMP анализ идёт по source set'ам; `detekt` собирает их все (CI вызывает именно его)
tasks.named("detekt") {
    dependsOn(tasks.matching { it.name.startsWith("detekt") && it.name.endsWith("SourceSet") && !it.name.startsWith("detektBaseline") })
}
