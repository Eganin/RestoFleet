import com.android.build.api.artifact.SingleArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9: Kotlin встроен — плагин org.jetbrains.kotlin.android не применяется.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

android {
    namespace = "ru.restofleet.console"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "ru.restofleet.console"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.2.0-practice"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/**
 * Доверие к CA стенда (`deploy/certs/out/public/ca.crt`) только в debug (реш. 423): ресурсы `xml/network_security_config`
 * и `raw/stand_ca` генерируются в `build/generated/` и подключаются лишь к debug-варианту; атрибут
 * `android:networkSecurityConfig` объявлен только в `src/debug/AndroidManifest.xml`. Сертификат в git не попадает.
 * Без файла CA `<debug-overrides/>` пуст: сборка проходит, а вход на стенд с самоподписанным сертификатом — нет.
 * Путь подменяется свойством `-PstandCaPath=`. Копия задачи из `agent/app` (без convention-плагинов, реш. 423).
 */
abstract class GenerateStandTrustTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val standCa: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val xmlDir = root.resolve("xml").apply { mkdirs() }
        val ca = standCa.orNull?.asFile
        ca?.readText()?.let { pem ->
            check("-----BEGIN CERTIFICATE-----" in pem) { "stand CA file must be a PEM certificate" }
            check("PRIVATE KEY" !in pem) { "stand CA file must not contain a private key" }
        }
        root.resolve("raw").apply { mkdirs() }.resolve("stand_ca.pem").writeBytes(ca?.readBytes() ?: ByteArray(0))
        val overrides = if (ca != null) {
            "<debug-overrides>\n        <trust-anchors>\n" +
                "            <certificates src=\"@raw/stand_ca\" />\n" +
                "        </trust-anchors>\n    </debug-overrides>"
        } else {
            "<debug-overrides />"
        }
        xmlDir.resolve("network_security_config.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    $overrides
</network-security-config>
""",
        )
    }
}

/** Падает, если в смёрженном release-манифесте осталась отладочная поверхность (CA стенда, `debuggable`). */
abstract class VerifyNoDebugSurfaceTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val text = mergedManifest.get().asFile.readText()
        check("networkSecurityConfig" !in text) { "release manifest must not declare networkSecurityConfig" }
        check(!Regex("""android:debuggable\s*=\s*"true"""").containsMatchIn(text)) {
            "release manifest must not be debuggable"
        }
    }
}

val standCaFile: Provider<RegularFile> = providers.gradleProperty("standCaPath")
    .map { layout.projectDirectory.file(it) }
    .orElse(rootProject.layout.projectDirectory.file("deploy/certs/out/public/ca.crt"))

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val generate = tasks.register<GenerateStandTrustTask>("generate${name}StandTrust") {
            standCaFile.get().asFile.takeIf { it.isFile }?.let { standCa.set(it) }
            outputDir.set(layout.buildDirectory.dir("generated/standTrust/${variant.name}/res"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(generate, GenerateStandTrustTask::outputDir)
    }
    onVariants(selector().withBuildType("release")) { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyNoDebugSurfaceTask>("verify${name}NoDebugSurface") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.named("check") { dependsOn(verify) }
        val releaseOutputs = setOf("assembleRelease", "bundleRelease", "packageRelease", "installRelease")
        tasks.matching { it.name in releaseOutputs }.configureEach { dependsOn(verify) }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get())) }
}

dependencies {
    implementation(projects.console.root)
    implementation(projects.console.core.common)
    implementation(projects.console.core.storage)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.decompose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.compose.mp.runtime)
    implementation(libs.compose.mp.ui)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"), rootProject.file("console/config/detekt.yml"))
}
