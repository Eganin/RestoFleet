import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    android {
        namespace = "ru.restofleet.console.core.designsystem"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get())) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get())) }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.mp.runtime)
            api(libs.compose.mp.foundation)
            api(libs.compose.mp.ui)
            api(libs.compose.mp.material3)
        }
        jvmTest.dependencies {
            implementation(libs.junit4)
            implementation(libs.truth)
            implementation(libs.compose.mp.ui.test)
            implementation(libs.compose.mp.desktop.macos.arm64)
        }
    }
}

kover {
    currentProject {
        createVariant("ci") { add("jvm") }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"), rootProject.file("console/config/detekt.yml"))
}

tasks.named("detekt") {
    dependsOn(tasks.matching { it.name.startsWith("detekt") && it.name.endsWith("SourceSet") && !it.name.startsWith("detektBaseline") })
}
