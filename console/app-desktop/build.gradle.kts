plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    application
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

application {
    mainClass.set("ru.restofleet.console.desktop.MainKt")
}

/** Нативные библиотеки Compose Desktop только для ОС сборки (остальные артефакты резолвятся, но не качаются зря). */
val composeDesktopRuntime = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        "mac" in os -> if (arm) libs.compose.mp.desktop.macos.arm64 else libs.compose.mp.desktop.macos.x64
        "win" in os -> libs.compose.mp.desktop.windows.x64
        else -> if (arm) libs.compose.mp.desktop.linux.arm64 else libs.compose.mp.desktop.linux.x64
    }
}

dependencies {
    implementation(projects.console.root)
    implementation(projects.console.core.common)
    implementation(projects.console.core.storage)
    implementation(libs.decompose)
    implementation(libs.decompose.extensions.compose)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.compose.mp.runtime)
    implementation(libs.compose.mp.foundation)
    implementation(libs.compose.mp.ui)
    implementation(libs.compose.mp.material3)
    implementation(composeDesktopRuntime)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.compose.mp.ui.test)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"), rootProject.file("console/config/detekt.yml"))
}
