import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9: Kotlin встроен — плагин org.jetbrains.kotlin.android не применяется.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

android {
    namespace = "ru.restofleet.agent"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "ru.restofleet.agent"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-mvp"
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    // Требование HiveMQ MQTT Client на Android: иначе сборка APK падает на дублях ресурсов Netty
    packaging {
        resources {
            excludes += listOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }
}

/**
 * Доверие к CA стенда (`deploy/certs/out/public/ca.crt`, единственный файл CA для клиентов по `deploy/README.md`)
 * только в debug: ресурсы `xml/network_security_config` и `raw/stand_ca` генерируются в `build/generated/` и
 * подключаются лишь к debug-варианту; `android:networkSecurityConfig` объявлен только в `src/debug/AndroidManifest.xml`.
 * Сертификат в git не попадает. `raw/stand_ca` создаётся всегда (debug-код MQTT-сессии на него ссылается): без файла
 * CA ресурс пустой, `<debug-overrides/>` пустой, сборка и CI проходят, а сессия не стартует (`ca_missing`). Путь можно
 * подменить свойством `-PstandCaPath=` (так проверяется ветка «нет файла»). В release CA не доставляется вовсе
 * (QR-провижининг, MVP-B).
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
            // В APK попадает только публичный сертификат: ключ или произвольный файл по -PstandCaPath — отказ сборки
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

/**
 * Падает, если в смёрженном release-манифесте осталась отладочная поверхность: `networkSecurityConfig` (CA стенда не
 * доверяется), `DevConfigReceiver` и его действие `DEV_CONFIG` (приём учётки брокера по adb, только debug),
 * `testOnly="true"` (позволил бы снять Device Owner без сброса) или `debuggable="true"`.
 */
abstract class VerifyNoDebugSurfaceTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val text = mergedManifest.get().asFile.readText()
        check("networkSecurityConfig" !in text) { "release manifest must not declare networkSecurityConfig" }
        check("DevConfigReceiver" !in text) { "release manifest must not declare DevConfigReceiver" }
        check("DEV_CONFIG" !in text) { "release manifest must not declare the DEV_CONFIG action" }
        check(!TEST_ONLY.containsMatchIn(text)) { "release manifest must not be testOnly" }
        check(!DEBUGGABLE.containsMatchIn(text)) { "release manifest must not be debuggable" }
    }

    private companion object {
        val TEST_ONLY = Regex("""android:testOnly\s*=\s*"true"""")
        val DEBUGGABLE = Regex("""android:debuggable\s*=\s*"true"""")
    }
}

val standCaFile: Provider<RegularFile> = providers.gradleProperty("standCaPath")
    .map { layout.projectDirectory.file(it) }
    .orElse(rootProject.layout.projectDirectory.file("deploy/certs/out/public/ca.crt"))

androidComponents {
    // AGP отключает юнит-тесты release по умолчанию; поверхность release (нет вкладки DO и команд Lab, решение 140)
    // проверяют только классы `*ReleaseTest` из src/testRelease — фильтр ниже, общие тесты в release не гоняются повторно.
    beforeVariants(selector().withBuildType("release")) { builder ->
        (builder as HasHostTestsBuilder).hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
    onVariants(selector().withBuildType("debug")) { variant ->
        val generate = tasks.register<GenerateStandTrustTask>("generate${variant.name.replaceFirstChar { it.uppercase() }}StandTrust") {
            standCaFile.get().asFile.takeIf { it.isFile }?.let { standCa.set(it) }
            outputDir.set(layout.buildDirectory.dir("generated/standTrust/${variant.name}/res"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(generate, GenerateStandTrustTask::outputDir)
    }
    onVariants(selector().withBuildType("release")) { variant ->
        val verify = tasks.register<VerifyNoDebugSurfaceTask>(
            "verify${variant.name.replaceFirstChar { it.uppercase() }}NoDebugSurface",
        ) {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.named("check") { dependsOn(verify) }
        // и сама сборка release-артефакта: упаковка (APK, bundle), установка и агрегирующие задачи, а не только `check`
        val releaseOutputs = setOf("assembleRelease", "bundleRelease", "packageRelease", "packageReleaseBundle", "installRelease")
        tasks.matching { it.name in releaseOutputs }.configureEach { dependsOn(verify) }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get())) }
}

dependencies {
    implementation(projects.agent.domain)
    implementation(projects.agent.data)
    implementation(projects.agent.presentation)
    implementation(projects.shared.kernel)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.essenty.instance.keeper)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.workmanager)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
}

// Каждый тестовый класс — в своём JVM. Robolectric (native runtime) в каждом sandbox, то есть при каждой смене
// SDK/конфигурации, копирует и загружает собственную копию librobolectric-nativeruntime (DefaultNativeRuntimeLoader.
// loadLibrary: System.load из загрузчика sandbox). Если в одном JVM нативку уже загрузил другой sandbox
// (AgentServiceTest на API 29/33), следующий sandbox (API 34 в WatchdogSchedulerTest) падает с
// UnsatisfiedLinkError на SQLiteConnectionNatives.nativeOpen; от порядка классов зависит, упадёт ли тест.
// Изоляция по классам делает прогон детерминированным (решение 106). Kover и `build`/`check` не затронуты.
tasks.withType<Test>().configureEach {
    forkEvery = 1
    if (name.endsWith("ReleaseUnitTest")) filter.includeTestsMatching("*ReleaseTest")
    // Ожидание для NetworkSecurityConfigTest: есть ли CA стенда на момент сборки
    systemProperty("standCaPresent", standCaFile.get().asFile.isFile.toString())
}

kover {
    currentProject {
        createVariant("ci") { add("debug") }
    }
}

detekt {
    buildUponDefaultConfig = true
}
