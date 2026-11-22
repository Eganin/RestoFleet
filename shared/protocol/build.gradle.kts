import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    android {
        namespace = "ru.restofleet.shared.protocol"
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
            // ShadowJson: JSON ↔ ShadowDocument — общий маппер тени для бэкенда (data, api) и агента (реш. 244)
            api(projects.shared.shadow)
            implementation(libs.kotlinx.serialization.json)
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

tasks.withType<Test>().configureEach {
    // Golden-фикстуры протокола лежат в docs/ и являются частью контракта (архитектура, разд. 7)
    val fixtures = rootProject.layout.projectDirectory.dir("docs/protocol/fixtures")
    inputs.dir(fixtures).withPropertyName("protocolFixtures")
    systemProperty("fixturesDir", fixtures.asFile.absolutePath)
}

detekt {
    buildUponDefaultConfig = true
}

// В KMP анализ идёт по source set'ам; `detekt` собирает их все (CI вызывает именно его)
tasks.named("detekt") {
    dependsOn(tasks.matching { it.name.startsWith("detekt") && it.name.endsWith("SourceSet") && !it.name.startsWith("detektBaseline") })
}
