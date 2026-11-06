// Корневой скрипт: только объявление плагинов (apply false) и Kover-агрегатор.
// Convention-плагинов и build-logic нет — у каждого модуля свой build.gradle.kts.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover)
}

// Модули, входящие в общий гейт покрытия. Спайки (:spikes:*) и инструменты (:tools:*) сюда не добавляются.
dependencies {
    kover(projects.shared.kernel)
    kover(projects.shared.protocol)
    kover(projects.console.core.mvi)
    kover(projects.agent.domain)
    kover(projects.agent.data)
    kover(projects.agent.app)
}

kover {
    currentProject {
        // Агрегированный вариант: собирает одноимённые варианты "ci" модулей из dependencies { kover(...) }
        createVariant("ci") {}
    }
    reports {
        filters {
            excludes {
                annotatedBy("androidx.compose.runtime.Composable", "androidx.compose.ui.tooling.preview.Preview")
                classes(
                    "*Module*", "*.di.*", "*Activity", "*Activity\$*", "*Service", "*Service\$*",
                    "*Receiver", "*Worker", "*.BuildConfig", "*ComposableSingletons*",
                )
            }
        }
        variant("ci") {
            verify {
                rule("Проект: строки ≥ 80 %") { minBound(80) }
            }
        }
    }
}
