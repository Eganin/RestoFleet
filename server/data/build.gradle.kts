// Data-слой бэкенда (КМ2, план 2.2): Exposed + Flyway (PostgreSQL), HiveMQ (ingest, publisher, DynSec), bcrypt.
// Интеграционные тесты (Testcontainers: TimescaleDB/PostgreSQL 17, Mosquitto 2.1 + DynSec) идут в обычном `test`:
// для гейта нужен запущенный Docker (реш. 215).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    api(projects.server.domain)
    implementation(projects.shared.protocol)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.hivemq.mqtt.client)
    implementation(libs.bcrypt)
    implementation(libs.kotlin.logging)

    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.core)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.logback.classic)
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
    // Конфигурация DynSec стенда (роли device и backend-service) — часть контракта: тест применяет её к брокеру
    inputs.dir(rootProject.layout.projectDirectory.dir("deploy/mosquitto")).withPropertyName("dynsecScripts")
    systemProperty("rf.deployDir", rootProject.layout.projectDirectory.dir("deploy").asFile.absolutePath)
    // Golden-фикстуры протокола: декодер ingest разбирает каждое сообщение устройства
    val fixtures = rootProject.layout.projectDirectory.dir("docs/protocol/fixtures")
    inputs.dir(fixtures).withPropertyName("protocolFixtures")
    systemProperty("fixturesDir", fixtures.asFile.absolutePath)
}
