package ru.restofleet.server.data.db

import org.junit.Before
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.TestDatabase
import ru.restofleet.server.domain.device.Device
import ru.restofleet.server.domain.restaurant.Restaurant

/** Интеграционные тесты репозиториев на TimescaleDB/PostgreSQL 17 (Testcontainers, реш. 215). */
internal abstract class PostgresTest {
    protected val restaurants = ExposedRestaurantRepository()
    protected val devices = ExposedDeviceRepository()

    @Before
    fun cleanDatabase() {
        TestDatabase.reset()
    }

    protected fun <T> tx(block: suspend () -> T): T = TestDatabase.inTx(block)

    protected fun givenRestaurant(code: String = "R-${Samples.uuid().take(8)}"): Restaurant =
        Samples.restaurant(code).also { r -> tx { restaurants.insert(r) } }

    protected fun givenDevice(
        restaurant: Restaurant = givenRestaurant(),
        build: (Restaurant) -> Device = { Samples.device(it) },
    ): Device = build(restaurant).also { d -> tx { devices.insert(d) } }
}
