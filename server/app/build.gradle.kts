// Composition root бэкенда (КМ2, план 2.4): main (Netty), Koin, конфиг (hoplite), CLI serve | migrate | seed,
// фоновые задачи. Образ стенда — Dockerfile на eclipse-temurin:21-jre поверх installDist.
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
    mainClass.set("ru.restofleet.server.app.MainKt")
    applicationName = "restofleet-backend"
}

dependencies {
    implementation(projects.server.domain)
    implementation(projects.server.data)
    implementation(projects.server.api)
    implementation(projects.shared.protocol)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.hoplite.core)
    implementation(libs.hoplite.yaml)
    implementation(libs.kaml)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
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
    // dev-seed стенда — часть контракта: тест разбирает настоящие deploy/seed/*.yaml
    val seed = rootProject.layout.projectDirectory.dir("deploy/seed")
    inputs.dir(seed).withPropertyName("seedDir").optional()
    systemProperty("rf.seedDir", seed.asFile.absolutePath)
}
