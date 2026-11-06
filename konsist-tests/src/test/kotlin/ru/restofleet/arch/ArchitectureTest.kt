package ru.restofleet.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

/**
 * Правило зависимостей Clean Architecture (docs/architecture.md, разд. 3.1).
 * Domain-набор: shared:kernel и domain-модули. shared:protocol — DTO транспорта (слой data), в набор не входит.
 */
class ArchitectureTest {

    private val project = Konsist.scopeFromProduction()

    private val domainFiles = project.files.filter { file ->
        DOMAIN_ROOTS.any { file.path.contains(it) }
    }

    @Test
    fun domainHasNoFrameworkImports() {
        domainFiles.assertFalse(testName = "domain imports framework") { file ->
            file.imports.any { import -> FORBIDDEN_IN_DOMAIN.any { import.name.startsWith(it) } }
        }
    }

    @Test
    fun useCasesExposeSingleOperatorInvoke() {
        project.classes()
            .filter { it.resideInPackage("..usecase..") }
            .assertTrue(testName = "use case has exactly one public operator invoke") { useCase ->
                val publicFunctions = useCase.functions(includeNested = false, includeLocal = false)
                    .filter { it.hasPublicOrDefaultModifier }
                val single = publicFunctions.singleOrNull()
                single != null && single.name == "invoke" && single.hasOperatorModifier
            }
    }

    @Test
    fun viewModelsDoNotImportData() {
        project.classes()
            .withNameEndingWith("ViewModel")
            .assertFalse(testName = "ViewModel imports data layer") { viewModel ->
                viewModel.containingFile.imports.any { it.name.contains(".data.") }
            }
    }

    @Test
    fun koinComponentOnlyInCompositionRoot() {
        project.files
            .filter { file -> COMPOSITION_ROOTS.none { file.path.contains(it) } }
            .assertFalse(testName = "Koin service locator outside composition root") { file ->
                file.imports.any { it.name in KOIN_SERVICE_LOCATOR }
            }
    }

    private companion object {
        val DOMAIN_ROOTS = listOf("/shared/kernel/src/", "/agent/domain/src/")
        val COMPOSITION_ROOTS = listOf("/agent/app/src/")
        val FORBIDDEN_IN_DOMAIN = listOf(
            "android.", "androidx.", "io.ktor.", "org.jetbrains.exposed.", "kotlinx.serialization.",
            "org.koin.", "com.hivemq.", "okhttp3.", "com.arkivanov.",
        )
        val KOIN_SERVICE_LOCATOR = setOf(
            "org.koin.core.component.KoinComponent",
            "org.koin.core.component.get",
            "org.koin.core.component.inject",
            "org.koin.java.KoinJavaComponent",
        )
    }
}
