package ru.restofleet.server.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.Samples.T0
import ru.restofleet.server.data.support.TestDatabase
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.device.DeviceAnnouncement
import ru.restofleet.server.domain.device.DeviceFilter
import ru.restofleet.server.domain.device.DeviceInfo
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.device.DeviceTransport
import ru.restofleet.server.domain.restaurant.RestaurantId
import java.sql.SQLException
import kotlin.test.assertFailsWith

internal class RegistryRepositoriesTest : PostgresTest() {
    private val credentials = ExposedCredentialRepository()

    @Test
    fun `restaurant round trip, lookup by code and list ordered by code`() {
        val b = givenRestaurant("B-2")
        val a = givenRestaurant("A-1")

        assertThat(tx { restaurants.findById(b.id) }).isEqualTo(b)
        assertThat(tx { restaurants.findByCode("A-1") }).isEqualTo(a)
        assertThat(tx { restaurants.list() }.map { it.code }).containsExactly("A-1", "B-2").inOrder()
        assertThat(tx { restaurants.findById(RestaurantId("not-a-uuid")) }).isNull()
        assertThat(tx { restaurants.findById(RestaurantId(Samples.uuid())) }).isNull()
        assertThat(tx { restaurants.findByCode("nope") }).isNull()
    }

    @Test
    fun `restaurant update changes fields but not createdAt, code stays unique`() {
        val r = givenRestaurant("OLD")
        val changed = r.copy(
            code = "NEW", name = "Новый", city = "Москва", address = "Тверская, 1", timezone = "UTC",
            updatedAt = T0 + 5,
        )

        tx { restaurants.update(changed) }

        assertThat(tx { restaurants.findById(r.id) }).isEqualTo(changed)
        givenRestaurant("OTHER")
        assertFailsWith<SQLException> { tx { restaurants.update(changed.copy(code = "OTHER")) } }
    }

    @Test
    fun `uuid is case insensitive but abbreviated forms are not accepted`() {
        val r = givenRestaurant()

        assertThat(tx { restaurants.findById(RestaurantId(r.id.value.uppercase())) }).isEqualTo(r)
        // UUID.fromString принял бы «1-1-1-1-1» как 00000001-0001-0001-0001-000000000001
        assertThat(tx { restaurants.findById(RestaurantId("1-1-1-1-1")) }).isNull()
    }

    @Test
    fun `device round trip keeps info, and a device without model has no info`() {
        val r = givenRestaurant()
        val withInfo = givenDevice(r)
        val bare = givenDevice(r) { Samples.device(it, info = null) }

        assertThat(tx { devices.findById(withInfo.id) }).isEqualTo(withInfo)
        assertThat(tx { devices.findById(bare.id) }!!.info).isNull()
        assertThat(tx { devices.findById(Samples.deviceId()) }).isNull()
    }

    @Test
    fun `device list filters by restaurant, role and status, ordered by registration and paged`() {
        val r1 = givenRestaurant("R1")
        val r2 = givenRestaurant("R2")
        val d1 = givenDevice(r1) { Samples.device(it, createdAt = T0 + 1, role = DeviceRole.HALL) }
        val d2 = givenDevice(r1) { Samples.device(it, createdAt = T0 + 2) }
        val d3 = givenDevice(r1) { Samples.device(it, createdAt = T0 + 3, status = DeviceStatus.ONLINE) }
        givenDevice(r2) { Samples.device(it, createdAt = T0) }

        val all = PageRequest.of()
        assertThat(tx { devices.list(DeviceFilter(restaurantId = r1.id), all) }.map { it.id })
            .containsExactly(d1.id, d2.id, d3.id).inOrder()
        assertThat(tx { devices.list(DeviceFilter(r1.id, DeviceRole.KITCHEN), all) }.map { it.id })
            .containsExactly(d2.id, d3.id).inOrder()
        assertThat(tx { devices.list(DeviceFilter(status = DeviceStatus.ONLINE), all) }.map { it.id })
            .containsExactly(d3.id)
        assertThat(tx { devices.list(DeviceFilter(r1.id), PageRequest.of(limit = 1, offset = 1)) }.map { it.id })
            .containsExactly(d2.id)
        assertThat(tx { devices.list(DeviceFilter(), all) }).hasSize(4)
        assertThat(tx { devices.list(DeviceFilter(RestaurantId("bad")), all) }).isEmpty()
    }

    @Test
    fun `active ids exclude unknown and decommissioned devices`() {
        val live = givenDevice()
        val gone = givenDevice()
        tx { devices.decommission(gone.id, T0 + 10) }

        val active = tx { devices.activeIds(listOf(live.id, gone.id, Samples.deviceId())) }

        assertThat(active).containsExactly(live.id)
        assertThat(tx { devices.activeIds(emptyList()) }).isEmpty()
    }

    @Test
    fun `markOnline sets status, transport and announcement without erasing known fields with nulls`() {
        val d = givenDevice()

        tx { devices.markOnline(d.id, T0 + 100, DeviceTransport.MQTT, DeviceAnnouncement(mode = "normal")) }

        val online = tx { devices.findById(d.id) }!!
        assertThat(online.status).isEqualTo(DeviceStatus.ONLINE)
        assertThat(online.lastSeenAt).isEqualTo(T0 + 100)
        assertThat(online.transport).isEqualTo(DeviceTransport.MQTT)
        assertThat(online.mode).isEqualTo("normal")
        assertThat(online.agentVersion).isEqualTo(d.agentVersion)
        assertThat(online.info).isEqualTo(d.info)

        val info = DeviceInfo("Lenovo TB-X606F", "LENOVO", "10", 29)
        val announcement = DeviceAnnouncement("0.3.0", "device_owner", info)
        tx { devices.markOnline(d.id, T0 + 200, DeviceTransport.MQTT, announcement) }

        val updated = tx { devices.findById(d.id) }!!
        assertThat(updated.agentVersion).isEqualTo("0.3.0")
        assertThat(updated.mode).isEqualTo("device_owner")
        assertThat(updated.info).isEqualTo(info)
    }

    @Test
    fun `long announcement values are cut to column sizes`() {
        val d = givenDevice()
        val info = DeviceInfo("m".repeat(300), "x".repeat(300), "1".repeat(100), 1)

        val announcement = DeviceAnnouncement("v".repeat(100), "z".repeat(100), info)
        tx { devices.markOnline(d.id, T0, DeviceTransport.MQTT, announcement) }

        val stored = tx { devices.findById(d.id) }!!
        assertThat(stored.agentVersion).hasLength(64)
        assertThat(stored.mode).hasLength(32)
        assertThat(stored.info!!.model).hasLength(100)
        assertThat(stored.info!!.osVersion).hasLength(32)
    }

    @Test
    fun `offline, touch and stale pollers change only active devices`() {
        val mqtt = givenDevice()
        val poller = givenDevice()
        val freshPoller = givenDevice()
        val gone = givenDevice()
        tx {
            devices.markOnline(mqtt.id, T0, DeviceTransport.MQTT, DeviceAnnouncement())
            devices.markOnline(poller.id, T0, DeviceTransport.POLL, DeviceAnnouncement())
            devices.markOnline(freshPoller.id, T0 + 60_000, DeviceTransport.POLL, DeviceAnnouncement())
            devices.decommission(gone.id, T0)
        }

        val stale = tx { devices.markStalePollersOffline(cutoff = T0 + 30_000) }

        assertThat(stale).isEqualTo(1)
        assertThat(tx { devices.findById(poller.id) }!!.status).isEqualTo(DeviceStatus.OFFLINE)
        assertThat(tx { devices.findById(freshPoller.id) }!!.status).isEqualTo(DeviceStatus.ONLINE)
        assertThat(tx { devices.findById(mqtt.id) }!!.status).isEqualTo(DeviceStatus.ONLINE)

        tx {
            devices.markOffline(mqtt.id, T0 + 1)
            devices.touch(listOf(mqtt.id, gone.id), T0 + 500)
            devices.markOnline(gone.id, T0 + 600, DeviceTransport.MQTT, DeviceAnnouncement())
            devices.markOffline(gone.id, T0 + 700)
        }
        assertThat(tx { devices.findById(mqtt.id) }!!.status).isEqualTo(DeviceStatus.OFFLINE)
        assertThat(tx { devices.findById(mqtt.id) }!!.lastSeenAt).isEqualTo(T0 + 500)
        val decommissioned = tx { devices.findById(gone.id) }!!
        assertThat(decommissioned.status).isEqualTo(DeviceStatus.DECOMMISSIONED)
        assertThat(decommissioned.decommissionedAt).isEqualTo(T0)
        assertThat(decommissioned.lastSeenAt).isNull()
    }

    @Test
    fun `device must belong to an existing restaurant`() {
        val orphan = Samples.device(Samples.restaurant())

        assertFailsWith<SQLException> { tx { devices.insert(orphan) } }
    }

    @Test
    fun `credential is active until revoked`() {
        val d = givenDevice()
        tx { credentials.insert(d.id, "\$2a\$10\$hash", T0) }

        assertThat(tx { credentials.findActiveHash(d.id) }).isEqualTo("\$2a\$10\$hash")
        tx { credentials.revoke(d.id, T0 + 1) }
        assertThat(tx { credentials.findActiveHash(d.id) }).isNull()
        assertThat(TestDatabase.scalar("SELECT password_hash FROM device_credentials")).isEqualTo("\$2a\$10\$hash")
        assertThat(tx { credentials.findActiveHash(Samples.deviceId()) }).isNull()
    }
}
