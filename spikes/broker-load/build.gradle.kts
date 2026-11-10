// Одноразовый спайк S1–S3 (нагрузка на брокер и DynSec). Вне гейтов Kover и Konsist (см. корневой build.gradle.kts).
// Detekt не подключён: одноразовый код (MaxLineLength/MagicNumber), решение 181.
// Запуск: ./gradlew :spikes:broker-load:installDist, затем build/install/broker-load/bin/broker-load <cmd> (README).
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

application {
    mainClass.set("ru.restofleet.spike.MainKt")
    applicationName = "broker-load"
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

dependencies {
    implementation(projects.shared.protocol)
    implementation(projects.shared.kernel)
    implementation(libs.hivemq.mqtt.client)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
