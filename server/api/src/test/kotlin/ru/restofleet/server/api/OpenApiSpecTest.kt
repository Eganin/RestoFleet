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

/** Рукописная спецификация (реш. 212) не расходится с маршрутами Ktor, копия в `docs/` — с ресурсом модуля. */
class OpenApiSpecTest {
    private val kit = ApiTestKit()
    private val specText = checkNotNull(javaClass.classLoader.getResource(ApiPaths.OPENAPI_RESOURCE)).readText()

    private fun specOperations(): Set<String> {
        val paths = Yaml.default.parseToYamlNode(specText).yamlMap.get<YamlMap>("paths")!!
        return paths.entries.flatMap { (path, item) ->
            (item as YamlMap).entries.keys.map { it.content }.filter { it in METHODS }
                .map { "${it.uppercase()} ${path.content}" }
        }.toSet()
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
        val spec = specOperations()

        assertWithMessage("routes found in Ktor").that(ktor.size).isAtLeast(MIN_OPERATIONS)
        assertWithMessage("routes missing from documentation.yaml").that(ktor - spec).isEmpty()
        assertWithMessage("documented operations without a route").that(spec - ktor).isEmpty()
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
    }
}
