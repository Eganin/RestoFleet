package ru.restofleet.server.app

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.app.seed.RestaurantsFile
import ru.restofleet.server.app.seed.SeedFiles
import ru.restofleet.server.app.seed.SeedRestaurant
import ru.restofleet.server.app.seed.SeedUser
import ru.restofleet.server.app.seed.Seeder
import ru.restofleet.server.app.seed.UsersFile
import ru.restofleet.server.domain.auth.User
import ru.restofleet.server.domain.auth.usecase.UpsertUser
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.server.domain.restaurant.UpsertedRestaurant
import ru.restofleet.server.domain.restaurant.usecase.UpsertRestaurantByCode
import ru.restofleet.shared.kernel.Outcome
import java.io.File
import java.time.ZoneId
import kotlin.test.assertFailsWith

class SeedTest {
    private val seedDir = File(System.getProperty("rf.seedDir"))

    @Test
    fun `stand seed has about forty fictional restaurants with unique codes and real zones`() {
        val file = SeedFiles.restaurants(seedDir.resolve("restaurants.yaml").readText())

        assertThat(file.restaurants.size).isAtLeast(40)
        assertThat(file.restaurants.map { it.code }.toSet()).hasSize(file.restaurants.size)
        file.restaurants.forEach {
            assertThat(it.code).matches("^[A-Za-z0-9_-]{1,32}$")
            assertThat(it.name).isNotEmpty()
            assertThat(it.city).isNotEmpty()
            assertThat(it.address).isNotEmpty()
            assertThat(ZoneId.of(it.timezone).id).isEqualTo(it.timezone)
        }
        assertThat(file.restaurants.map { it.timezone }.toSet().size).isAtLeast(5)
    }

    @Test
    fun `stand users file names a secret instead of holding a password`() {
        val text = seedDir.resolve("users.yaml").readText()

        val file = SeedFiles.users(text)

        assertThat(file.users.single()).isEqualTo(SeedUser("admin", "admin-password"))
        assertThat(text.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n"))
            .doesNotContainMatch("(?m)^\\s*password\\s*:")
    }

    @Test
    fun `a plaintext password or unknown key in seed files is rejected`() {
        assertFailsWith<Exception> { SeedFiles.users("users:\n  - login: admin\n    password: hunter2\n") }
        assertFailsWith<Exception> {
            SeedFiles.restaurants("restaurants:\n  - code: A\n    name: n\n    city: c\n    timezone: UTC\n    tz: x\n")
        }
    }

    @Test
    fun `seeder counts created and updated, reports failures without passwords`() = runTest {
        val upsertRestaurant = mockk<UpsertRestaurantByCode>()
        val upsertUser = mockk<UpsertUser>()
        val restaurant = Restaurant(RestaurantId("r"), "A", "n", "c", "", "UTC", 0, 0)
        coEvery { upsertRestaurant(match { it.code == "A" }) } returns
            Outcome.Success(UpsertedRestaurant(restaurant, created = true))
        coEvery { upsertRestaurant(match { it.code == "B" }) } returns
            Outcome.Success(UpsertedRestaurant(restaurant, created = false))
        coEvery { upsertRestaurant(match { it.code == "C" }) } returns
            Outcome.Failure(ServerError.Validation("timezone", "unknown_zone"))
        coEvery { upsertUser("admin", "admin-password-123") } returns
            Outcome.Success(User("u", "admin", "h", User.ROLE_ADMIN, 0))
        coEvery { upsertUser("weak", "short") } returns Outcome.Failure(ServerError.Validation("password", "length"))
        val restaurants = RestaurantsFile(listOf("A", "B", "C").map { SeedRestaurant(it, "n", "c", "", "UTC") })
        val users = UsersFile(listOf(SeedUser("admin", "admin-password"), SeedUser("weak", "weak-password")))
        val passwords = mapOf("admin-password" to "admin-password-123", "weak-password" to "short")

        val report = Seeder(upsertRestaurant, upsertUser)
            .run(restaurants, users) { passwords.getValue(it.passwordSecret) }

        assertThat(report.created).isEqualTo(1)
        assertThat(report.updated).isEqualTo(1)
        assertThat(report.users).isEqualTo(1)
        assertThat(report.ok).isFalse()
        assertThat(report.errors).containsExactly(
            "restaurant C: validation timezone unknown_zone",
            "user weak: validation password length",
        )
        assertThat(report.errors.joinToString()).doesNotContain("short")
        coVerify(exactly = 3) { upsertRestaurant(any()) }
    }
}
