package ru.restofleet.server.app.seed

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import ru.restofleet.server.domain.auth.usecase.UpsertUser
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.restaurant.RestaurantDraft
import ru.restofleet.server.domain.restaurant.usecase.UpsertRestaurantByCode
import ru.restofleet.shared.kernel.Outcome

/** `deploy/seed/restaurants.yaml` (`1-coursework.md` 4.5): вымышленные рестораны стенда. */
@Serializable
data class RestaurantsFile(val restaurants: List<SeedRestaurant>)

@Serializable
data class SeedRestaurant(
    val code: String,
    val name: String,
    val city: String,
    val address: String = "",
    val timezone: String,
)

/**
 * `deploy/seed/users.yaml`: **пароля в файле нет** — только имя секрета ([SeedUser.passwordSecret]) в каталоге
 * секретов (`/run/secrets` в контейнере, `deploy/out` на хосте). Поле `password` отклоняется разбором (строгий YAML).
 */
@Serializable
data class UsersFile(val users: List<SeedUser>)

@Serializable
data class SeedUser(val login: String, val passwordSecret: String)

/** Строгий разбор: неизвестный ключ (`password:` в `users.yaml`, опечатка) — ошибка, а не молчаливый пропуск. */
object SeedFiles {
    fun restaurants(text: String): RestaurantsFile = Yaml.default.decodeFromString(RestaurantsFile.serializer(), text)

    fun users(text: String): UsersFile = Yaml.default.decodeFromString(UsersFile.serializer(), text)
}

/** Итог seed: ошибки — коды доменных отказов с ключом записи, без паролей. */
data class SeedReport(val created: Int, val updated: Int, val users: Int, val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * Dev-seed через **те же** use cases, что и REST API (`1-coursework.md` 4.5): ресторан — upsert по `code`,
 * пользователь — создание или смена пароля. Повторный запуск идемпотентен. Ошибка одной записи не останавливает
 * остальные, но делает итог неуспешным.
 */
class Seeder(
    private val upsertRestaurant: UpsertRestaurantByCode,
    private val upsertUser: UpsertUser,
) {
    suspend fun run(restaurants: RestaurantsFile, users: UsersFile, password: (SeedUser) -> String): SeedReport {
        var created = 0
        var updated = 0
        var seededUsers = 0
        val errors = mutableListOf<String>()
        restaurants.restaurants.forEach { r ->
            when (val result = upsertRestaurant(RestaurantDraft(r.code, r.name, r.city, r.address, r.timezone))) {
                is Outcome.Success -> if (result.value.created) created++ else updated++
                is Outcome.Failure -> errors += "restaurant ${r.code}: ${result.error.describe()}"
            }
        }
        users.users.forEach { u ->
            when (val result = upsertUser(u.login, password(u))) {
                is Outcome.Success -> seededUsers++
                is Outcome.Failure -> errors += "user ${u.login}: ${result.error.describe()}"
            }
        }
        return SeedReport(created, updated, seededUsers, errors)
    }

    private fun ServerError.describe(): String = when (this) {
        is ServerError.Validation -> "$code $field $reason"
        is ServerError.Conflict -> "$code $reason"
        else -> code
    }
}
