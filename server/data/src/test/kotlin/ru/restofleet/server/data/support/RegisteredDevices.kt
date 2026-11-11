package ru.restofleet.server.data.support

import kotlinx.coroutines.runBlocking
import ru.restofleet.server.data.db.ExposedDeviceRepository
import ru.restofleet.server.data.db.ExposedRestaurantRepository
import ru.restofleet.server.data.mqtt.DynSecBrokerAccounts
import ru.restofleet.server.data.mqtt.HiveMqClient
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome

/** Устройство есть и в DynSec [TestBroker] (отдельным соединением-администратором), и в реестре [TestDatabase]. */
fun registeredDevice(): Pair<DeviceId, String> {
    val admin = HiveMqClient(TestBroker.backendConfig(), trust = null)
    val id = Samples.deviceId()
    val password = "pw-${Samples.uuid()}"
    runBlocking {
        admin.connect()
        admin.subscribe(listOf(DynSecBrokerAccounts.RESPONSE_TOPIC))
        check(DynSecBrokerAccounts(admin, 5_000).createDevice(id, password) is Outcome.Success)
        admin.disconnect()
    }
    val restaurant = Samples.restaurant()
    TestDatabase.inTx {
        ExposedRestaurantRepository().insert(restaurant)
        ExposedDeviceRepository().insert(Samples.device(restaurant, id))
    }
    return id to password
}
