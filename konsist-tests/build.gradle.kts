// Архитектурные тесты (Konsist). Читают исходники всего репозитория с диска,
// поэтому от продуктовых модулей не зависят.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}

detekt {
    buildUponDefaultConfig = true
}

tasks.test {
    // Исходники могут меняться без изменения графа задач — тесты всегда перезапускаются
    outputs.upToDateWhen { false }
}
