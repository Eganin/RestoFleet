// Симулятор устройств (КМ4, план 4.1–4.2; арх. разд. 10): N виртуальных устройств в одном процессе (корутина на
// устройство, клиенты HiveMQ на общем event loop), профили kitchen/hall/assembly, режимы MQTT и REST-polling,
// сценарии YAML, результаты CSV, измеритель эксперимента «MQTT vs REST-polling». Пакеты domain/data/app внутри модуля
// (правила Konsist — как у отдельных модулей). Образ для compose (profile `experiment`) — Dockerfile поверх installDist.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    application
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

application {
    mainClass.set("ru.restofleet.server.simulator.app.MainKt")
    applicationName = "restofleet-simulator"
}

dependencies {
    implementation(projects.shared.kernel)
    implementation(projects.shared.protocol)
    implementation(projects.shared.shadow)
    implementation(projects.shared.probes)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hivemq.mqtt.client)
    implementation(libs.okhttp)
    implementation(libs.kaml)
    implementation(libs.kotlin.logging)
    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.core)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.testcontainers)
    testImplementation(libs.logback.classic)
}

kover {
    currentProject {
        createVariant("ci") { add("jvm") }
    }
    reports {
        verify {
            rule { minBound(80) }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
}

tasks.withType<Test>().configureEach {
    // Golden-фикстуры протокола: симулятор шлёт и разбирает те же сообщения, что агент (FR-SM-02)
    val fixtures = rootProject.layout.projectDirectory.dir("docs/protocol/fixtures")
    inputs.dir(fixtures).withPropertyName("protocolFixtures")
    systemProperty("fixturesDir", fixtures.asFile.absolutePath)
}
