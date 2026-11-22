package ru.restofleet.arch

import com.google.common.truth.Truth.assertWithMessage
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Test
import java.io.File

/**
 * Архитектурные правила консоли (этап П, реш. 407, 429; `docs/architecture.md`, разд. 4–5):
 * - `-domain` без фреймворков, транспорта и чужих слоёв;
 * - `-ui` без `-data`, Decompose, Ktor и Koin;
 * - ViewModel зависит только от use cases (и роутера);
 * - нет импортов чужой фичи, фича не зависит от другой фичи в `build.gradle.kts`;
 * - Koin и `KoinComponent` только в composition root: `<f>-component/…/di`, `console:root`, `console:app-*`.
 * Общие правила слоёв (`ArchitectureTest`) продолжают действовать и для консоли.
 */
class ConsoleArchitectureTest {
    private val production = Konsist.scopeFromProduction().files
    private val console = production.filter { "/console/" in it.normalizedPath }
    private val features: List<File> = featureDirs()

    private fun featureFiles(module: String): List<KoFileDeclaration> =
        console.filter {
            Regex("/console/feature/[a-z0-9-]+/[a-z0-9-]+-$module/src/").containsMatchIn(it.normalizedPath)
        }

    @Test
    fun consoleModulesAreFound() {
        assertWithMessage("console files").that(console).isNotEmpty()
        assertWithMessage("features").that(features).isNotEmpty()
        listOf("domain", "ui").forEach {
            assertWithMessage("$it files").that(featureFiles(it)).isNotEmpty()
        }
    }

    @Test
    fun allFeaturesOfStageArePresent() {
        // FR-CN-01…10 (реш. 401): потеря фичи при слиянии или переименовании ловится здесь
        assertWithMessage("features").that(features.map { it.name }).containsAtLeast(
            "auth", "dashboard", "restaurants", "devices", "device-details", "shadow", "commands",
            "alerts", "incidents", "provisioning",
        )
    }

    @Test
    fun everyFeatureHasFourModules() {
        features.forEach { feature ->
            listOf("domain", "data", "ui", "component").forEach { layer ->
                val module = File(feature, "${feature.name}-$layer")
                assertWithMessage("module ${module.name}").that(File(module, "build.gradle.kts").isFile).isTrue()
            }
        }
    }

    @Test
    fun featureDomainHasNoFrameworksOrOuterLayers() {
        featureFiles("domain").assertFalse(testName = "console domain imports framework or outer layer") { f ->
            f.imports.any { i ->
                DOMAIN_FORBIDDEN.any { i.name.startsWith(it) } ||
                    i.name.startsWith("ru.restofleet.console.") &&
                    DOMAIN_CONSOLE_ALLOWED.none { i.name.startsWith(it) } &&
                    !i.name.startsWith("ru.restofleet.console.feature.${packageOf(f)}.domain")
            }
        }
    }

    @Test
    fun featureUiDoesNotImportDataDecomposeKtorOrKoin() {
        featureFiles("ui").assertFalse(testName = "console ui imports data, decompose, ktor or koin") { f ->
            f.imports.any { i ->
                UI_FORBIDDEN.any { i.name.startsWith(it) } ||
                    i.name.contains(".feature.") && i.name.contains(".data.")
            }
        }
    }

    @Test
    fun viewModelsDependOnlyOnUseCases() {
        val viewModels = Konsist.scopeFromProduction().classes()
            .filter {
                "/console/feature/" in it.containingFile.path.replace('\\', '/') && it.name.endsWith("ViewModel")
            }
        assertWithMessage("view models").that(viewModels).isNotEmpty()
        viewModels.forEach { vm ->
            val imports = vm.containingFile.imports.map { it.name }
            vm.primaryConstructor?.parameters.orEmpty().forEach { p ->
                val type = p.type.name
                val ok = type in ALLOWED_VM_TYPES || type.endsWith("Router") ||
                    imports.any { it.endsWith(".domain.$type") }
                assertWithMessage("${vm.name}(${p.name}: $type) must be a use case, router or scope")
                    .that(ok).isTrue()
            }
        }
    }

    @Test
    fun noImportsOfForeignFeatures() {
        val inFeatures = console.filter { "/console/feature/" in it.normalizedPath }
        inFeatures.assertFalse(testName = "feature imports another feature") { f ->
            val own = packageOf(f)
            f.imports.any { i ->
                i.name.startsWith("ru.restofleet.console.feature.") &&
                    !i.name.startsWith("ru.restofleet.console.feature.$own.")
            }
        }
    }

    @Test
    fun koinOnlyInCompositionRoot() {
        console.filter { !COMPOSITION_ROOT.containsMatchIn(it.normalizedPath) }
            .assertFalse(testName = "koin outside composition root") { f ->
                f.imports.any { it.name.startsWith("org.koin.") }
            }
    }

    @Test
    fun featureBuildScriptsDoNotDependOnOtherFeatures() {
        features.forEach { feature ->
            val own = camel(feature.name)
            feature.walkTopDown().filter { it.name == "build.gradle.kts" }.forEach { script ->
                val refs = FEATURE_REF.findAll(script.readText()).map { it.groupValues[1] }
                val foreign = refs.filter { it != own }.toList()
                assertWithMessage("${script.relativeTo(root)} references other features").that(foreign).isEmpty()
            }
        }
    }

    @Test
    fun domainAndUiBuildScriptsKeepLayersClean() {
        features.forEach { feature ->
            val domain = File(feature, "${feature.name}-domain/build.gradle.kts").readText()
            DOMAIN_BUILD_FORBIDDEN.forEach {
                assertWithMessage("${feature.name}-domain uses $it").that(domain).doesNotContain(it)
            }
            val ui = File(feature, "${feature.name}-ui/build.gradle.kts").readText()
            UI_BUILD_FORBIDDEN.forEach {
                assertWithMessage("${feature.name}-ui uses $it").that(ui).doesNotContain(it)
            }
        }
    }

    private companion object {
        val DOMAIN_FORBIDDEN = listOf(
            "android.", "androidx.", "io.ktor.", "kotlinx.serialization.", "org.koin.", "com.arkivanov.",
            "org.jetbrains.compose.", "ru.restofleet.shared.protocol",
        )
        val DOMAIN_CONSOLE_ALLOWED = listOf("ru.restofleet.console.core.common")
        val UI_FORBIDDEN = listOf("io.ktor.", "org.koin.", "com.arkivanov.decompose", "ru.restofleet.shared.protocol")
        val ALLOWED_VM_TYPES = setOf("CoroutineScope", "String", "Int", "Long", "Boolean")
        val COMPOSITION_ROOT = Regex("(/console/root/src/|/console/app-[a-z]+/src/|-component/src/.*/di/)")
        val FEATURE_REF = Regex("""projects\.console\.feature\.([A-Za-z0-9]+)\.""")
        val DOMAIN_BUILD_FORBIDDEN = listOf(
            "libs.ktor", "libs.decompose", "libs.koin", "libs.compose", "libs.kotlinx.serialization",
        )
        val UI_BUILD_FORBIDDEN = listOf("Data)", "libs.ktor", "libs.decompose", "libs.koin")

        val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

        fun featureDirs(): List<File> =
            File(root, "console/feature").listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }

        /** `device-details` → `devicedetails` (пакет) */
        fun packageOf(file: KoFileDeclaration): String =
            Regex("/console/feature/([a-z0-9-]+)/").find(file.path.replace('\\', '/'))!!.groupValues[1].replace("-", "")

        /** `device-details` → `deviceDetails` (аксессор Gradle) */
        fun camel(name: String): String =
            name.split('-')
                .mapIndexed { i, s -> if (i == 0) s else s.replaceFirstChar { it.uppercase() } }
                .joinToString("")

        val KoFileDeclaration.normalizedPath: String get() = path.replace('\\', '/')
    }
}
