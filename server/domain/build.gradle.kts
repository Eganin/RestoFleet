// Домен бэкенда (КМ2, план 2.1): сущности, порты, use cases. Чистый JVM-модуль без фреймворков —
// зависит только от shared:kernel, shared:shadow, shared:probes и корутин (правило Konsist).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    api(projects.shared.kernel)
    api(projects.shared.shadow)
    api(projects.shared.probes)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

kover {
    currentProject {
        // Вариант для агрегированного CI-гейта (корневой build.gradle.kts)
        createVariant("ci") {}
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
