package ru.restofleet.server.data.db

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.Samples.T0
import ru.restofleet.server.data.support.TestDatabase
import ru.restofleet.server.domain.command.CommandReasons
import ru.restofleet.server.domain.command.CommandStatus
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.shadow.DefaultDesired
import ru.restofleet.server.domain.shadow.ReportedShadow
import ru.restofleet.server.domain.shadow.Shadow
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue

internal class ShadowAndCommandRepositoriesTest : PostgresTest() {
    private val shadows = ExposedShadowRepository()
    private val commands = ExposedCommandRepository()

    @Test
    fun `shadow round trip keeps documents, versions and errors`() {
        val d = givenDevice()
        val initial = Shadow.initial(d.id, DefaultDesired.document, T0)

        tx { shadows.insert(initial) }

        assertThat(tx { shadows.find(d.id) }).isEqualTo(initial)
        assertThat(tx { shadows.find(Samples.deviceId()) }).isNull()
        assertThat(TestDatabase.scalar("SELECT jsonb_typeof(desired) FROM shadows")).isEqualTo("object")
    }

    @Test
    fun `desired and reported are updated independently`() {
        val d = givenDevice()
        tx { shadows.insert(Shadow.initial(d.id, DefaultDesired.document, T0)) }
        val desired = ShadowDocument(mapOf("telemetry" to ShadowValue.Obj(mapOf("intervalSec" to ShadowValue.Num(60)))))
        val errors = listOf("watchedApps:empty")
        val reported = ReportedShadow(d.id, desired, appliedVersion = 2, appliedAt = T0 + 50, errors = errors)

        tx { shadows.updateDesired(d.id, desired, version = 2, at = T0 + 10) }
        tx { shadows.updateReported(reported, at = T0 + 60) }

        val stored = tx { shadows.find(d.id) }!!
        assertThat(stored.desired).isEqualTo(desired)
        assertThat(stored.desiredVersion).isEqualTo(2)
        assertThat(stored.desiredUpdatedAt).isEqualTo(T0 + 10)
        assertThat(stored.reported).isEqualTo(desired)
        assertThat(stored.reportedVersion).isEqualTo(2)
        assertThat(stored.appliedAt).isEqualTo(T0 + 50)
        assertThat(stored.reportedAt).isEqualTo(T0 + 60)
        assertThat(stored.errors).containsExactly("watchedApps:empty")
        assertThat(stored.delta.isEmpty).isTrue()
        assertThat(stored.isPending).isFalse()
    }

    @Test
    fun `findForUpdate holds the row until commit so concurrent version bumps serialize`() {
        val d = givenDevice()
        tx { shadows.insert(Shadow.initial(d.id, DefaultDesired.document, T0)) }
        val locked = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        runBlocking(Dispatchers.IO) {
            val first = async {
                TestDatabase.tx.inTransaction {
                    val s = shadows.findForUpdate(d.id)!!
                    locked.complete(Unit)
                    delay(300)
                    shadows.updateDesired(d.id, s.desired, s.desiredVersion + 1, T0 + 1)
                    synchronized(order) { order += "first" }
                }
            }
            val second = async {
                locked.await()
                TestDatabase.tx.inTransaction {
                    val s = shadows.findForUpdate(d.id)!!
                    synchronized(order) { order += "second saw ${s.desiredVersion}" }
                    shadows.updateDesired(d.id, s.desired, s.desiredVersion + 1, T0 + 2)
                }
            }
            first.await()
            second.await()
        }

        assertThat(order).containsExactly("first", "second saw 2").inOrder()
        assertThat(tx { shadows.find(d.id) }!!.desiredVersion).isEqualTo(3)
    }

    @Test
    fun `command round trip and update of state fields only`() {
        val d = givenDevice()
        val command = Samples.command(d.id)
        tx { commands.insert(command) }

        val done = command.copy(
            status = CommandStatus.SUCCEEDED,
            deliveredAt = T0 + 1,
            finishedAt = T0 + 2,
            result = mapOf("deviceTime" to "123"),
            reason = "ok",
        )
        tx { commands.update(done.copy(type = "reboot")) }

        assertThat(tx { commands.find(command.cmdId) }).isEqualTo(done)
        assertThat(tx { commands.findForUpdate(command.cmdId) }).isEqualTo(done)
        assertThat(tx { commands.find("c-none") }).isNull()
    }

    @Test
    fun `expireDue moves only pending commands past their expiry`() {
        val d = givenDevice()
        val due = Samples.command(d.id, expiresAt = T0 + 10)
        val dueDelivered = Samples.command(d.id, status = CommandStatus.DELIVERED, expiresAt = T0 + 10)
        val alive = Samples.command(d.id, expiresAt = T0 + 1_000)
        val finished = Samples.command(d.id, status = CommandStatus.SUCCEEDED, expiresAt = T0 + 10)
        tx { listOf(due, dueDelivered, alive, finished).forEach { commands.insert(it) } }

        val expired = tx { commands.expireDue(now = T0 + 11) }

        assertThat(expired).isEqualTo(2)
        listOf(due, dueDelivered).forEach {
            val c = tx { commands.find(it.cmdId) }!!
            assertThat(c.status).isEqualTo(CommandStatus.EXPIRED)
            assertThat(c.reason).isEqualTo(CommandReasons.EXPIRED)
            assertThat(c.finishedAt).isEqualTo(T0 + 11)
        }
        assertThat(tx { commands.find(alive.cmdId) }!!.status).isEqualTo(CommandStatus.QUEUED)
        assertThat(tx { commands.find(finished.cmdId) }!!.status).isEqualTo(CommandStatus.SUCCEEDED)
        assertThat(tx { commands.expireDue(now = T0 + 10) }).isEqualTo(0)
    }

    @Test
    fun `failUnfinished fails queued and delivered commands of one device only`() {
        val d = givenDevice()
        val other = givenDevice()
        val queued = Samples.command(d.id, cmdId = "c-q")
        val delivered = Samples.command(d.id, cmdId = "c-d", status = CommandStatus.DELIVERED)
        val finished = Samples.command(d.id, cmdId = "c-s", status = CommandStatus.SUCCEEDED)
        val foreign = Samples.command(other.id, cmdId = "c-f")
        tx { listOf(queued, delivered, finished, foreign).forEach { commands.insert(it) } }

        val failed = tx { commands.failUnfinished(d.id, CommandReasons.DECOMMISSIONED, now = T0 + 7) }

        assertThat(failed).isEqualTo(2)
        listOf(queued, delivered).forEach {
            val c = tx { commands.find(it.cmdId) }!!
            assertThat(c.status).isEqualTo(CommandStatus.FAILED)
            assertThat(c.reason).isEqualTo(CommandReasons.DECOMMISSIONED)
            assertThat(c.finishedAt).isEqualTo(T0 + 7)
        }
        assertThat(tx { commands.find(finished.cmdId) }!!.status).isEqualTo(CommandStatus.SUCCEEDED)
        assertThat(tx { commands.find(foreign.cmdId) }!!.status).isEqualTo(CommandStatus.QUEUED)
    }

    @Test
    fun `deliverQueued hands out each live queued command once, oldest first, only to its device`() {
        val d = givenDevice()
        val other = givenDevice()
        val older = Samples.command(d.id, cmdId = "c-b", createdAt = T0)
        val newer = Samples.command(d.id, cmdId = "c-a", createdAt = T0 + 5)
        val stale = Samples.command(d.id, expiresAt = T0 + 1)
        val delivered = Samples.command(d.id, status = CommandStatus.DELIVERED)
        val foreign = Samples.command(other.id)
        tx { listOf(newer, older, stale, delivered, foreign).forEach { commands.insert(it) } }

        val first = tx { commands.deliverQueued(d.id, now = T0 + 2) }
        val second = tx { commands.deliverQueued(d.id, now = T0 + 3) }

        assertThat(first.map { it.cmdId }).containsExactly("c-b", "c-a").inOrder()
        assertThat(first.map { it.status }.toSet()).containsExactly(CommandStatus.DELIVERED)
        assertThat(first.map { it.deliveredAt }.toSet()).containsExactly(T0 + 2)
        assertThat(second).isEmpty()
        assertThat(tx { commands.find(foreign.cmdId) }!!.status).isEqualTo(CommandStatus.QUEUED)
        assertThat(tx { commands.find(stale.cmdId) }!!.status).isEqualTo(CommandStatus.QUEUED)
    }
}
