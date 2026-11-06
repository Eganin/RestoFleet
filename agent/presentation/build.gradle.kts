import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9: Kotlin встроен — плагин org.jetbrains.kotlin.android не применяется.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

android {
    namespace = "ru.restofleet.agent.presentation"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get())) }
}

dependencies {
    implementation(projects.agent.domain)
    // api: ViewModel наследует BaseMviViewModel, а тот — InstanceKeeper.Instance; app должен видеть эти супертипы
    api(projects.console.core.mvi)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

kover {
    currentProject {
        createVariant("ci") { add("debug") }
    }
    reports {
        variant("ci") {
            // Как в корневом агрегаторе: composable и preview исключены, остальное — Reducer, маппер, ViewModel
            filters {
                excludes {
                    annotatedBy("androidx.compose.runtime.Composable", "androidx.compose.ui.tooling.preview.Preview")
                    // Kover фильтрует по аннотации только классы, а @Composable стоит на функциях файловых фасадов:
                    // фасады Compose-UI исключаются по имени (в них только composable, превью и их мелкие помощники)
                    classes(
                        "*.lab.LabScreenKt*", "*.lab.LabContainerKt*", "*.lab.view.section.*",
                        "*.common.ObserveEffectsKt*", "*ComposableSingletons*",
                    )
                }
            }
            verify {
                rule("Модуль: строки ≥ 90 %") { minBound(90) }
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
}
