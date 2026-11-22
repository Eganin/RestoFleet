// Presentation-слой бэкенда (КМ2, план 2.3): Ktor routes, DTO API, StatusPages, JWT + Basic устройства, RateLimit,
// OpenAPI + Swagger UI. Use cases приходят параметрами (composition root — server:app); data не импортируется
// (правило Konsist), REST-контракт устройства — DTO shared:protocol.rest (реш. 222, 245).
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

    api(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.server.swagger)
    implementation(libs.kotlin.logging)

    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.core)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kaml)
    testImplementation(libs.logback.classic)
}

kover {
    currentProject {
        createVariant("ci") { add("jvm") }
    }
    reports {
        verify {
            rule { minBound(85) }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
}

tasks.withType<Test>().configureEach {
    // Копия спецификации для людей (реш. 212): тест сверяет её с ресурсом модуля
    val openApiCopy = rootProject.layout.projectDirectory.file("docs/openapi.yaml")
    inputs.file(openApiCopy).withPropertyName("openApiCopy")
    systemProperty("openApiCopy", openApiCopy.asFile.absolutePath)
    val fixtures = rootProject.layout.projectDirectory.dir("docs/protocol/fixtures")
    inputs.dir(fixtures).withPropertyName("protocolFixtures")
    systemProperty("fixturesDir", fixtures.asFile.absolutePath)
}
