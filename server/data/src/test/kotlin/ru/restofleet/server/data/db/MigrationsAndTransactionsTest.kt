package ru.restofleet.server.data.db

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.TestDatabase
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.Outcome
import java.sql.SQLException
import kotlin.test.assertFailsWith

internal class MigrationsAndTransactionsTest : PostgresTest() {

    @Test
    fun `V1 creates every table of the coursework model and a second migrate is a no-op`() {
        val tables = TestDatabase.scalar(
            "SELECT string_agg(table_name, ',' ORDER BY table_name) FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'",
        )!!.split(',')

        assertThat(TestDatabase.migrated).isEqualTo(1)
        assertThat(tables).containsExactly(
            "commands", "device_credentials", "device_sessions", "devices", "events", "probe_results",
            "provisioning_tokens", "refresh_tokens", "restaurants", "shadows", "telemetry", "users",
        )
        assertThat(Migrations.migrate(TestDatabase.dataSource)).isEqualTo(0)
        assertThat(TestDatabase.scalar("SELECT max(version) FROM flyway_schema_history")).isEqualTo("1")
    }

    @Test
    fun `check constraints reject values the domain never writes`() {
        val restaurant = givenRestaurant()
        val sql = "INSERT INTO devices (id, restaurant_id, role, status, created_at) " +
            "VALUES ('d-x', '${restaurant.id.value}', 'chef', 'online', now())"

        val error = assertFailsWith<SQLException> {
            TestDatabase.dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        }

        assertThat(error.sqlState).isEqualTo("23514")
    }

    @Test
    fun `success commits, failure rolls back and is returned as is`() {
        val kept = Samples.restaurant("KEEP")
        val dropped = Samples.restaurant("DROP")

        val ok = runBlocking {
            TestDatabase.tx.transactional<Unit, String> {
                restaurants.insert(kept)
                Outcome.Success(Unit)
            }
        }
        val failed = runBlocking {
            TestDatabase.tx.transactional<Unit, String> { restaurants.insert(dropped); Outcome.Failure("nope") }
        }

        assertThat(ok).isEqualTo(Outcome.Success(Unit))
        assertThat(failed).isEqualTo(Outcome.Failure("nope"))
        assertThat(tx { restaurants.list() }.map { it.code }).containsExactly("KEEP")
    }

    @Test
    fun `exception rolls back, propagates and the block is not retried`() {
        var attempts = 0
        val duplicate = Samples.restaurant("DUP")
        tx { restaurants.insert(duplicate) }

        assertFailsWith<SQLException> {
            tx {
                attempts++
                restaurants.insert(Samples.restaurant("NEW"))
                restaurants.insert(duplicate.copy(id = RestaurantId(Samples.uuid())))
            }
        }

        assertThat(attempts).isEqualTo(1)
        assertThat(tx { restaurants.findByCode("NEW") }).isNull()
    }
}
