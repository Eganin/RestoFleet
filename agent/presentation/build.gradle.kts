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
    // BackHandler навигации агента (Lab → мастер/статус)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
}

kover {
    currentProject {
        createVariant("ci") { add("debug") }
    }
    reports {
        variant("ci") {
            // Composable и preview исключены, остальное — Reducer, маппер, ViewModel, чистые функции выбора строк.
            // annotatedBy фильтрует и функции с аннотацией; по имени исключаются только синтетические лямбды
            // composable (у них собственной аннотации нет). Имена перечислены поимённо и только для файлов, где нет
            // ничего, кроме composable/preview и их лямбд; логика (выбор @StringRes и аргументов) вынесена
            // в mapper/StringSpec.kt и покрыта тестами.
            filters {
                excludes {
                    annotatedBy("androidx.compose.runtime.Composable", "androidx.compose.ui.tooling.preview.Preview")
                    classes(
                        "*.lab.LabScreenKt*", "*.lab.LabContainerKt*", "*.lab.view.section.CheckRowKt*",
                        "*.lab.view.section.FilterTabsKt*", "*.lab.view.section.LabActionsKt*",
                        "*.lab.view.section.CommandsSectionKt*",
                        "*.deviceowner.DeviceOwnerScreenKt*", "*.deviceowner.DeviceOwnerContainerKt*",
                        "*.deviceowner.view.section.DeviceOwnerHeaderKt*",
                        "*.deviceowner.view.section.DeviceOwnerRowKt*", "*.deviceowner.view.section.ApkSectionKt*",
                        "*.common.ObserveEffectsKt*", "*.common.BadgeMarkKt*", "*ComposableSingletons*",
                        "*.common.StringSpecTextKt*",
                        "*.setup.SetupScreenKt*", "*.setup.SetupContainerKt*",
                        "*.setup.view.section.PermissionsSectionKt*",
                        "*.status.StatusScreenKt*", "*.status.StatusContainerKt*",
                        "*.status.view.section.ProbesSectionKt*",
                        "*.root.RootContainerKt*",
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
