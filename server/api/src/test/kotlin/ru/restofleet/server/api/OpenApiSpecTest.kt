package ru.restofleet.server.api

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.yamlMap
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.testApplication
import org.junit.Test
import java.io.File

/**
 * Рукописная спецификация (реш. 212) не расходится с маршрутами Ktor, копия в `docs/` — с ресурсом модуля.
 * Операции контракта П, которых ещё нет в Ktor, помечены `x-planned: true` (ПМ1): они исключаются из сверки, а тест
 * падает, если помеченная операция уже реализована, — задача, которая добавляет маршрут, снимает пометку.
 */
class OpenApiSpecTest {
    private val kit = ApiTestKit()
    private val specText = checkNotNull(javaClass.classLoader.getResource(ApiPaths.OPENAPI_RESOURCE)).readText()

    /** Операция спецификации → помечена ли она `x-planned: true`. */
    private fun specOperations(): Map<String, Boolean> {
        val paths = Yaml.default.parseToYamlNode(specText).yamlMap.get<YamlMap>("paths")!!
        return paths.entries.flatMap { (path, item) ->
            (item as YamlMap).entries.filter { (method, _) -> method.content in METHODS }.map { (method, operation) ->
                val planned = (operation as YamlMap).getScalar("x-planned")?.toBoolean() ?: false
                "${method.content.uppercase()} ${path.content}" to planned
            }
        }.toMap()
    }

    private fun ktorOperations(): Set<String> {
        var routes: List<RoutingNode> = emptyList()
        testApplication {
            application {
                restofleetApi(kit.useCases, kit.settings)
                routes = plugin(RoutingRoot).getAllRoutes()
            }
            startApplication()
        }
        return routes.mapNotNull { route ->
            val method = (route.selector as? HttpMethodRouteSelector)?.method ?: return@mapNotNull null
            val path = generateSequence(route) { it.parent }.toList().reversed().mapNotNull { node ->
                when (val s = node.selector) {
                    is PathSegmentConstantRouteSelector -> s.value
                    is PathSegmentParameterRouteSelector -> "${s.prefix.orEmpty()}{${s.name}}${s.suffix.orEmpty()}"
                    else -> null
                }
            }.joinToString("/", prefix = "/")
            "${method.value} $path"
        }.filterNot { it.contains(" /${ApiPaths.SWAGGER}") }.toSet()
    }

    @Test
    fun `every ktor route is described in the specification and nothing else is`() {
        val ktor = ktorOperations()
        val spec = specOperations().filterValues { planned -> !planned }.keys

        assertWithMessage("routes found in Ktor").that(ktor.size).isAtLeast(MIN_OPERATIONS)
        assertWithMessage("routes missing from documentation.yaml").that(ktor - spec).isEmpty()
        assertWithMessage("documented operations without a route").that(spec - ktor).isEmpty()
    }

    @Test
    fun `a planned operation is not routed yet`() {
        val planned = specOperations().filterValues { it }.keys

        assertWithMessage("x-planned operations already present in Ktor (remove the mark)")
            .that(planned intersect ktorOperations()).isEmpty()
    }

    @Test
    fun `the specification contains every operation of the practice contract`() {
        assertThat(specOperations().keys).containsAtLeastElementsIn(PRACTICE_OPERATIONS)
    }

    @Test
    fun `docs copy is identical to the module resource`() {
        val copy = File(System.getProperty("openApiCopy"))

        assertWithMessage("docs/openapi.yaml must be a copy of server/api/.../openapi/documentation.yaml")
            .that(copy.readText()).isEqualTo(specText)
    }

    @Test
    fun `swagger ui and the specification are served without authentication`() = kit.run {
        val ui = client.get("/swagger")
        val file = client.get("/swagger/documentation.yaml")

        assertThat(ui.status).isEqualTo(HttpStatusCode.OK)
        assertThat(ui.bodyAsText()).contains("swagger-ui")
        assertThat(file.status).isEqualTo(HttpStatusCode.OK)
        assertThat(file.bodyAsText()).contains("openapi: 3.0.3")
    }

    private companion object {
        val METHODS = setOf("get", "post", "put", "patch", "delete")
        const val MIN_OPERATIONS = 24

        /** Контракт этапа П (план ПМ1, 1.3; реш. 408, 417–419). */
        val PRACTICE_OPERATIONS = setOf(
            "GET /api/v1/alerts", "PATCH /api/v1/alerts/{id}", "GET /api/v1/incidents", "GET /api/v1/incidents/{id}",
            "PATCH /api/v1/incidents/{id}", "GET /api/v1/summary", "POST /api/v1/auth/logout",
            "GET /api/v1/devices/{id}/commands", "GET /api/v1/ws",
        )
    }
}
