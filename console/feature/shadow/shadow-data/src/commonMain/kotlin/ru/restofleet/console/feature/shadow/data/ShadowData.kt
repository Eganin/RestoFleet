package ru.restofleet.console.feature.shadow.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.apiCall
import ru.restofleet.console.feature.shadow.domain.ShadowCodec
import ru.restofleet.console.feature.shadow.domain.ShadowRepository
import ru.restofleet.console.feature.shadow.domain.ShadowState
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.map
import ru.restofleet.shared.protocol.ShadowJson
import ru.restofleet.shared.protocol.rest.console.ConsolePaths
import ru.restofleet.shared.protocol.rest.console.ShadowDto
import ru.restofleet.shared.shadow.ShadowDocument

class ShadowApi(private val client: HttpClient) {
    suspend fun get(id: String): Outcome<ShadowDto, ConsoleError> = apiCall { client.get(ConsolePaths.deviceShadow(id)) }

    suspend fun put(id: String, patch: JsonObject): Outcome<ShadowDto, ConsoleError> =
        apiCall { client.put(ConsolePaths.deviceShadow(id)) { setBody(patch) } }
}

class ShadowRepositoryImpl(private val api: ShadowApi, private val clock: Clock) : ShadowRepository {
    override suspend fun load(deviceId: String): Outcome<ShadowState, ConsoleError> =
        api.get(deviceId).map { it.toDomain(clock.nowMillis()) }

    override suspend fun put(deviceId: String, patch: ShadowDocument): Outcome<ShadowState, ConsoleError> =
        api.put(deviceId, ShadowJson.toJson(patch)).map { it.toDomain(clock.nowMillis()) }
}

internal fun ShadowDto.toDomain(loadedAt: Long) = ShadowState(
    deviceId = deviceId,
    desired = ShadowJson.fromJson(desired),
    desiredVersion = desiredVersion,
    desiredUpdatedAt = desiredUpdatedAt,
    reported = reported?.let(ShadowJson::fromJson),
    reportedVersion = reportedVersion,
    errors = errors,
    delta = ShadowJson.fromJson(delta),
    pending = pending,
    published = published,
    loadedAt = loadedAt,
)

/** JSON тени через общий [ShadowJson]; ошибка разбора — `InvalidInput` для поля `json`. */
class ShadowJsonCodec : ShadowCodec {
    override fun render(document: ShadowDocument): String = ShadowJson.encode(document)

    @Suppress("SwallowedException")
    override fun parse(text: String): Outcome<ShadowDocument, ConsoleError> = try {
        Outcome.Success(ShadowJson.decode(text))
    } catch (e: SerializationException) {
        Outcome.Failure(ConsoleError.InvalidInput("json", e.message ?: "invalid json"))
    } catch (e: IllegalArgumentException) {
        Outcome.Failure(ConsoleError.InvalidInput("json", e.message ?: "invalid json"))
    }
}
