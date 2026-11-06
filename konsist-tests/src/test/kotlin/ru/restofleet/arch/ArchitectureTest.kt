package ru.restofleet.arch

import com.google.common.truth.Truth.assertWithMessage
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

/**
 * Правило зависимостей Clean Architecture (docs/architecture.md, разд. 3.1, 3.2, 4).
 * Слой определяется путём модуля, а не именем класса: так правило ловит и Reducer, и Store, и Component.
 *
 * - domain: `<part>/domain/`, `<feature>-domain/`, `shared/{kernel,shadow,probes}/`;
 * - presentation: `<part>/presentation/`, `<feature>-ui/`, `console/core/mvi/`;
 * - composition root: `<part>/app/`, `console/app-<platform>/`, `console/root/`, `<feature>-component/`, пакеты `di`.
 * `shared:protocol` — DTO транспорта, то есть слой data.
 */
class ArchitectureTest {

    private val production = Konsist.scopeFromProduction().files

    private val domainFiles = production.filter { DOMAIN_PATH.containsMatchIn(it.normalizedPath) }
    private val presentationFiles = production.filter { PRESENTATION_PATH.containsMatchIn(it.normalizedPath) }

    @Test
    fun domainSetIsNotEmpty() {
        // Защита от «зелёного» прогона на пустом наборе после переименования или переноса модулей
        assertWithMessage("domain files found by $DOMAIN_PATH").that(domainFiles).isNotEmpty()
    }

    @Test
    fun domainHasNoFrameworkOrOuterLayerImports() {
        domainFiles.assertFalse(testName = "domain imports framework, transport or data") { file ->
            file.imports.any { import -> isForbiddenInDomain(import.name) }
        }
    }

    @Test
    fun presentationDoesNotImportDataOrTransport() {
        presentationFiles.assertFalse(testName = "presentation imports data layer or transport DTO") { file ->
            file.imports.any { import -> isDataLayer(import.name) }
        }
    }

    @Test
    fun useCasesExposeSingleOperatorInvoke() {
        Konsist.scopeFromProduction().classes()
            .filter { it.resideInPackage("..usecase..") }
            .assertTrue(testName = "use case has exactly one public operator invoke") { useCase ->
                val publicFunctions = useCase.functions(includeNested = false, includeLocal = false)
                    .filter { it.hasPublicOrDefaultModifier }
                val single = publicFunctions.singleOrNull()
                single != null && single.name == "invoke" && single.hasOperatorModifier
            }
    }

    @Test
    fun koinOnlyInCompositionRoot() {
        production
            .filterNot { COMPOSITION_ROOT_PATH.containsMatchIn(it.normalizedPath) }
            .assertFalse(testName = "Koin used outside composition root") { file ->
                val importsKoin = file.imports.any { it.name.startsWith("org.koin.") }
                val extendsKoinComponent = file.classes(includeNested = true)
                    .any { cls -> cls.parents().any { it.name == "KoinComponent" } }
                importsKoin || extendsKoinComponent
            }
    }

    private companion object {
        val DOMAIN_PATH = Regex("/(domain|[a-z0-9]+(-[a-z0-9]+)*-domain|shared/(kernel|shadow|probes))/src/")
        val PRESENTATION_PATH = Regex("/(presentation|[a-z0-9]+(-[a-z0-9]+)*-ui|console/core/mvi)/src/")
        val COMPOSITION_ROOT_PATH =
            Regex("(/app/src/|/console/app-[a-z]+/src/|/console/root/src/|-component/src/|/di/)")

        val FORBIDDEN_IN_DOMAIN = listOf(
            "android.", "androidx.", "io.ktor.", "org.jetbrains.exposed.", "kotlinx.serialization.",
            "org.koin.", "com.hivemq.", "okhttp3.", "com.arkivanov.", "ru.restofleet.shared.protocol",
        )

        fun isDataLayer(name: String): Boolean =
            name.contains(".data.") || name.startsWith("ru.restofleet.shared.protocol")

        fun isForbiddenInDomain(name: String): Boolean =
            FORBIDDEN_IN_DOMAIN.any { name.startsWith(it) } || isDataLayer(name)

        val KoFileDeclaration.normalizedPath: String get() = path.replace('\\', '/')
    }
}
