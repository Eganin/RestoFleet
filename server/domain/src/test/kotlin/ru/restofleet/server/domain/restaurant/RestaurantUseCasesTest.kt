package ru.restofleet.server.domain.restaurant

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.fake.World
import ru.restofleet.server.domain.restaurant.usecase.CreateRestaurant
import ru.restofleet.server.domain.restaurant.usecase.GetRestaurant
import ru.restofleet.server.domain.restaurant.usecase.ListRestaurants
import ru.restofleet.server.domain.restaurant.usecase.UpdateRestaurant
import ru.restofleet.server.domain.restaurant.usecase.UpsertRestaurantByCode
import ru.restofleet.shared.kernel.Outcome

class RestaurantUseCasesTest {
    private val w = World()
    private val create = CreateRestaurant(w.restaurants, w.tx, w.ids, w.clock)
    private val update = UpdateRestaurant(w.restaurants, w.tx, w.clock)
    private val get = GetRestaurant(w.restaurants, w.tx)
    private val list = ListRestaurants(w.restaurants, w.tx)
    private val upsert = UpsertRestaurantByCode(w.restaurants, w.tx, create, update)

    private val draft = RestaurantDraft(" SPB-007 ", " Невский ", "Санкт-Петербург", "Невский пр., 1", "Europe/Moscow")

    @Test
    fun createNormalizesAndStores() = runTest {
        val created = (create(draft) as Outcome.Success).value
        assertThat(created.code).isEqualTo("SPB-007")
        assertThat(created.name).isEqualTo("Невский")
        assertThat(created.id.value).isEqualTo("id-1")
        assertThat(created.createdAt).isEqualTo(w.clock.now)
        assertThat(w.db.state.restaurants.values).containsExactly(created)
    }

    @Test
    fun createRejectsTakenCode() = runTest {
        w.restaurant(code = "SPB-007")
        assertThat(create(draft)).isEqualTo(Outcome.Failure(ServerError.Conflict("restaurant_code_taken")))
        assertThat(w.db.state.restaurants).hasSize(1)
    }

    @Test
    fun createValidatesFields() = runTest {
        val cases = mapOf(
            draft.copy(code = "a b") to ServerError.Validation("code", "invalid_format"),
            draft.copy(code = "x".repeat(33)) to ServerError.Validation("code", "invalid_format"),
            draft.copy(name = " ") to ServerError.Validation("name", "required"),
            draft.copy(name = "n".repeat(201)) to ServerError.Validation("name", "too_long"),
            draft.copy(city = "") to ServerError.Validation("city", "required"),
            draft.copy(city = "c".repeat(201)) to ServerError.Validation("city", "too_long"),
            draft.copy(address = "a".repeat(201)) to ServerError.Validation("address", "too_long"),
            draft.copy(timezone = "Mars/Olympus") to ServerError.Validation("timezone", "unknown_zone"),
        )
        cases.forEach { (input, error) -> assertThat(create(input)).isEqualTo(Outcome.Failure(error)) }
        assertThat(w.db.state.restaurants).isEmpty()
    }

    @Test
    fun updatePatchesOnlyGivenFields() = runTest {
        val r = w.restaurant()
        w.clock.now = 5_000
        val updated = (update(r.id, RestaurantPatch(name = "Новое имя")) as Outcome.Success).value
        assertThat(updated).isEqualTo(r.copy(name = "Новое имя", updatedAt = 5_000))
        assertThat(w.db.state.restaurants[r.id]).isEqualTo(updated)
    }

    @Test
    fun updateRejectsUnknownInvalidAndTakenCode() = runTest {
        val r = w.restaurant()
        w.restaurant(id = "r-2", code = "OTHER")
        assertThat(update(RestaurantId("nope"), RestaurantPatch(name = "x")))
            .isEqualTo(Outcome.Failure(ServerError.NotFound("restaurant", "nope")))
        assertThat(update(r.id, RestaurantPatch(timezone = "bad")))
            .isEqualTo(Outcome.Failure(ServerError.Validation("timezone", "unknown_zone")))
        assertThat(update(r.id, RestaurantPatch(code = "OTHER")))
            .isEqualTo(Outcome.Failure(ServerError.Conflict("restaurant_code_taken")))
        assertThat(w.db.state.restaurants[r.id]).isEqualTo(r)
    }

    @Test
    fun updateMayKeepOwnCode() = runTest {
        val r = w.restaurant()
        assertThat(update(r.id, RestaurantPatch(code = r.code))).isInstanceOf(Outcome.Success::class.java)
    }

    @Test
    fun getAndList() = runTest {
        val b = w.restaurant(id = "r-b", code = "B")
        val a = w.restaurant(id = "r-a", code = "A")
        assertThat(get(b.id)).isEqualTo(Outcome.Success(b))
        assertThat(get(RestaurantId("x"))).isEqualTo(Outcome.Failure(ServerError.NotFound("restaurant", "x")))
        assertThat(list()).containsExactly(a, b).inOrder()
    }

    @Test
    fun upsertCreatesThenUpdatesByCode() = runTest {
        val first = (upsert(draft) as Outcome.Success).value
        assertThat(first.created).isTrue()
        val second = (upsert(draft.copy(name = "Переименован")) as Outcome.Success).value
        assertThat(second.created).isFalse()
        assertThat(second.restaurant.id).isEqualTo(first.restaurant.id)
        assertThat(second.restaurant.name).isEqualTo("Переименован")
        assertThat(w.db.state.restaurants).hasSize(1)
    }

    @Test
    fun upsertPropagatesValidation() = runTest {
        assertThat(upsert(draft.copy(timezone = "bad")))
            .isEqualTo(Outcome.Failure(ServerError.Validation("timezone", "unknown_zone")))
    }
}
