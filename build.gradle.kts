// Корневой скрипт: только объявление плагинов (apply false) и Kover-агрегатор.
// Convention-плагинов и build-logic нет — у каждого модуля свой build.gradle.kts.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
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
    kover(projects.shared.shadow)
    kover(projects.shared.probes)
    kover(projects.agent.domain)
    kover(projects.agent.data)
    kover(projects.agent.presentation)
    kover(projects.agent.app)
    kover(projects.server.domain)
    kover(projects.server.data)
    kover(projects.server.api)
    kover(projects.server.app)
    kover(projects.server.simulator)

    // console — модули консоли (этап П); строки в этом блоке добавляет поток B (реш. 407)
    kover(projects.console.core.mvi)
    kover(projects.console.core.common)
    kover(projects.console.core.decompose)
    kover(projects.console.core.storage)
    kover(projects.console.core.network)
    kover(projects.console.core.designsystem)
    kover(projects.console.core.ui)
    kover(projects.console.root)
    kover(projects.console.feature.auth.authDomain)
    kover(projects.console.feature.auth.authData)
    kover(projects.console.feature.auth.authUi)
    kover(projects.console.feature.auth.authComponent)
    kover(projects.console.feature.dashboard.dashboardDomain)
    kover(projects.console.feature.dashboard.dashboardData)
    kover(projects.console.feature.dashboard.dashboardUi)
    kover(projects.console.feature.dashboard.dashboardComponent)
    kover(projects.console.feature.devices.devicesDomain)
    kover(projects.console.feature.devices.devicesData)
    kover(projects.console.feature.devices.devicesUi)
    kover(projects.console.feature.devices.devicesComponent)
    kover(projects.console.feature.restaurants.restaurantsDomain)
    kover(projects.console.feature.restaurants.restaurantsData)
    kover(projects.console.feature.restaurants.restaurantsUi)
    kover(projects.console.feature.restaurants.restaurantsComponent)
    kover(projects.console.feature.deviceDetails.deviceDetailsDomain)
    kover(projects.console.feature.deviceDetails.deviceDetailsData)
    kover(projects.console.feature.deviceDetails.deviceDetailsUi)
    kover(projects.console.feature.deviceDetails.deviceDetailsComponent)
    kover(projects.console.feature.commands.commandsDomain)
    kover(projects.console.feature.commands.commandsData)
    kover(projects.console.feature.commands.commandsUi)
    kover(projects.console.feature.commands.commandsComponent)
    kover(projects.console.feature.shadow.shadowDomain)
    kover(projects.console.feature.shadow.shadowData)
    kover(projects.console.feature.shadow.shadowUi)
    kover(projects.console.feature.shadow.shadowComponent)
    // end console
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
