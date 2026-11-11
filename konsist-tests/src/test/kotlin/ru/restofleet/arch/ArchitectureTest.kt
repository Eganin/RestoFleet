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
 * `shared:protocol` — DTO транспорта, то есть слой data. Исключение — `server:api` (presentation бэкенда): REST-DTO
 * устройства из `shared:protocol.rest` — это его request/response DTO (реш. 222, 245), правило — отдельный тест.
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
    fun sharedDomainModulesAreFoundAndDoNotDependOnParts() {
        // shared:shadow и shared:probes — общий домен агента, бэкенда и симулятора: не знают о частях системы
        val shared = production.filter { SHARED_DOMAIN_PATH.containsMatchIn(it.normalizedPath) }
        listOf("/shared/shadow/", "/shared/probes/").forEach { module ->
            assertWithMessage("files of $module").that(shared.any { module in it.normalizedPath }).isTrue()
        }
        shared.assertFalse(testName = "shared domain imports agent, server, console or protocol") { file ->
            file.imports.any { import -> PARTS.any { import.name.startsWith(it) } }
        }
    }

    @Test
    fun serverDomainIsFoundAndUsesOnlySharedDomain() {
        // server:domain (КМ2): только shared:kernel/shadow/probes и свой пакет; без JDBC и фреймворков бэкенда
        val serverDomain = production.filter { "/server/domain/src/" in it.normalizedPath }
        assertWithMessage("server:domain files").that(serverDomain).isNotEmpty()
        serverDomain.assertFalse(testName = "server domain imports outside shared domain") { file ->
            file.imports.any { import ->
                import.name.startsWith("ru.restofleet.") && SERVER_DOMAIN_ALLOWED.none { import.name.startsWith(it) }
            }
        }
    }

    @Test
    fun serverUseCasesAreFound() {
        // Правило «один invoke» ниже матчит пакеты ..usecase..: убедиться, что серверные use cases туда попадают
        val useCases = Konsist.scopeFromProduction().classes()
            .filter { it.resideInPackage("ru.restofleet.server.domain..usecase..") }
        assertWithMessage("server use cases").that(useCases.size).isAtLeast(MIN_SERVER_USE_CASES)
    }

    @Test
    fun serverDataDoesNotImportApiOrApp() {
        production.filter { "/server/data/src/" in it.normalizedPath }
            .assertFalse(testName = "server data imports api or app") { file ->
                file.imports.any { import -> SERVER_OUTER.any { import.name.startsWith(it) } }
            }
    }

    @Test
    fun serverApiIsFoundAndDoesNotImportDataOrInfrastructure() {
        // server:api — presentation бэкенда (КМ2b, реш. 245): только domain и REST-контракт устройства из
        // shared:protocol (реш. 222); реализации портов, Exposed, HiveMQ, JDBC-пул и composition root — нельзя
        val api = production.filter { "/server/api/src/" in it.normalizedPath }
        assertWithMessage("server:api files").that(api).isNotEmpty()
        api.assertFalse(testName = "server api imports data layer, app or infrastructure") { file ->
            file.imports.any { import ->
                import.name.contains(".data.") || SERVER_API_FORBIDDEN.any { import.name.startsWith(it) }
            }
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

        val SHARED_DOMAIN_PATH = Regex("/shared/(kernel|shadow|probes)/src/")
        val PARTS = listOf(
            "ru.restofleet.agent.", "ru.restofleet.server.", "ru.restofleet.console.", "ru.restofleet.shared.protocol",
        )

        val FORBIDDEN_IN_DOMAIN = listOf(
            "android.", "androidx.", "io.ktor.", "org.jetbrains.exposed.", "kotlinx.serialization.",
            "org.koin.", "com.hivemq.", "okhttp3.", "com.arkivanov.", "ru.restofleet.shared.protocol",
            "org.flywaydb.", "com.zaxxer.", "org.postgresql.", "at.favre.", "java.sql.", "javax.sql.",
        )

        val SERVER_DOMAIN_ALLOWED = listOf(
            "ru.restofleet.server.domain.", "ru.restofleet.shared.kernel.", "ru.restofleet.shared.shadow.",
            "ru.restofleet.shared.probes.",
        )

        val SERVER_OUTER = listOf("ru.restofleet.server.api", "ru.restofleet.server.app")

        val SERVER_API_FORBIDDEN = listOf(
            "ru.restofleet.server.app", "org.jetbrains.exposed.", "com.hivemq.", "org.flywaydb.", "com.zaxxer.",
            "org.postgresql.", "at.favre.", "javax.sql.",
        )

        /** Сейчас их 30 (КМ2a); порог ловит потерю правила при переносе пакетов. */
        const val MIN_SERVER_USE_CASES = 30

        fun isDataLayer(name: String): Boolean =
            name.contains(".data.") || name.startsWith("ru.restofleet.shared.protocol")

        fun isForbiddenInDomain(name: String): Boolean =
            FORBIDDEN_IN_DOMAIN.any { name.startsWith(it) } || isDataLayer(name)

        val KoFileDeclaration.normalizedPath: String get() = path.replace('\\', '/')
    }
}
