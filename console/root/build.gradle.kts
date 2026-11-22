import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    android {
        namespace = "ru.restofleet.console.root"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get())) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get())) }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.console.core.decompose)
            api(projects.console.core.common)
            implementation(projects.console.core.network)
            api(projects.console.core.storage)
            implementation(projects.console.core.designsystem)
            implementation(projects.console.core.ui)
            implementation(projects.console.feature.auth.authDomain)
            implementation(projects.console.feature.auth.authComponent)
            api(libs.decompose)
            implementation(libs.decompose.extensions.compose)
            api(libs.ktor.client.core)
            api(libs.ktor.client.logging)
            implementation(libs.compose.mp.runtime)
            implementation(libs.compose.mp.foundation)
            implementation(libs.compose.mp.material3)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
        }
        jvmTest.dependencies {
            implementation(libs.junit4)
            implementation(libs.truth)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(projects.console.core.testing)
            implementation(libs.compose.mp.ui.test)
            implementation(libs.compose.mp.desktop.macos.arm64)
        }
    }
}

kover {
    currentProject {
        createVariant("ci") { add("jvm") }
    }
    reports {
        filters {
            excludes {
                annotatedBy("androidx.compose.runtime.Composable", "androidx.compose.ui.tooling.preview.Preview")
                classes("*ComposableSingletons*")
            }
        }
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
