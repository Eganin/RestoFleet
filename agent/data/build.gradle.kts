import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9: Kotlin встроен — плагин org.jetbrains.kotlin.android не применяется.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

android {
    namespace = "ru.restofleet.agent.data"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9: ресурсы библиотеки выключены по умолчанию; нужны строки уведомлений команд (res/values/strings.xml)
    buildFeatures {
        androidResources = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get())) }
}

dependencies {
    implementation(projects.agent.domain)
    implementation(projects.shared.protocol)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}

kover {
    currentProject {
        // Вариант для агрегированного CI-гейта: Android-модуль меряется на debug
        createVariant("ci") { add("debug") }
        // Отдельный вариант для пакета platform: в Kover 0.9 фильтры задаются на уровне варианта, не правила
        createVariant("platform") { add("debug") }
    }
    reports {
        variant("ci") {
            // Как в корневом агрегаторе: DI-модули исключены из покрытия (constraints, список исключений)
            filters {
                excludes { packages("ru.restofleet.agent.data.di") }
            }
            verify {
                rule("Модуль: строки ≥ 90 %") { minBound(90) }
            }
        }
        variant("platform") {
            filters {
                includes { packages("ru.restofleet.agent.data.platform") }
            }
            verify {
                rule("platform: ветви 100 %") {
                    bound {
                        coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                        aggregationForGroup = kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE
                        minValue = 100
                    }
                }
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    // Golden-фикстуры протокола лежат в docs/ и являются частью контракта (архитектура, разд. 7)
    val fixtures = rootProject.layout.projectDirectory.dir("docs/protocol/fixtures")
    inputs.dir(fixtures).withPropertyName("protocolFixtures")
    systemProperty("fixturesDir", fixtures.asFile.absolutePath)
}

// Гарантия 100 % ветвей пакета platform: вариант "platform" существует отдельно, потому что в Kover 0.9 фильтры
// задаются только на уровне варианта, а не правила. Правило проверяет koverVerifyPlatform; его запускают
// `check` (значит и `build`) и koverVerifyCi модуля — удалять обе связки нельзя.
tasks.named("check") { dependsOn("koverVerifyPlatform") }
tasks.matching { it.name == "koverVerifyCi" }.configureEach { dependsOn("koverVerifyPlatform") }

detekt {
    buildUponDefaultConfig = true
}
