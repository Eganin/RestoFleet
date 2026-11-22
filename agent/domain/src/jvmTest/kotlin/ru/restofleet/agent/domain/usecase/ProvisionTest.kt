package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.error.ProvisionError
import ru.restofleet.agent.domain.model.DeviceRole
import ru.restofleet.agent.domain.model.ProvisionedDevice
import ru.restofleet.agent.domain.model.Registration
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.agent.domain.port.DeviceDataReset
import ru.restofleet.agent.domain.port.ProvisioningApi
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import java.io.IOException

/** FR-AG-10, 12: код → учётка брокера в защищённом хранилище + роль и ресторан (реш. 210). */
class ProvisionTest {
    private val device = ProvisionedDevice(
        deviceId = DeviceId.parse("d-1f2e")!!,
        password = "s3cr3t",
        restaurantId = "r-1",
        role = DeviceRole.KITCHEN,
        brokerHost = "10.0.2.2",
        brokerPort = 8883,
    )
    private val calls = mutableListOf<Pair<String, String>>()
    private var answer: Outcome<ProvisionedDevice, ProvisionError> = Outcome.Success(device)
    private val api = ProvisioningApi { url, code ->
        calls += url to code
        answer
    }
    private val saved = mutableListOf<BrokerSettings>()
    private var saveFailure: Exception? = null
    private var loadFailure: Exception? = null

    /** Порядок побочных эффектов: сброс данных прежнего устройства и запись учётки. */
    private val effects = mutableListOf<String>()
    private val store = object : CredentialStore {
        override suspend fun save(s: BrokerSettings) {
            saveFailure?.let { throw it }
            saved += s
            effects += "save ${s.deviceId.value}"
        }

        override suspend fun load(): BrokerSettings? {
            loadFailure?.let { throw it }
            return saved.lastOrNull()
        }
    }
    private var resetFailure: Exception? = null
    private val reset = DeviceDataReset {
        resetFailure?.let { throw it }
        effects += "reset"
    }
    private val sut = Provision(api, store, reset)

    private fun previous(id: String) {
        saved += BrokerSettings("10.0.2.2", 8883, DeviceId.parse(id)!!, "old", null, null)
    }

    @Test
    fun successStoresCredentialsWithRoleAndRestaurant() = runTest {
        val outcome = sut("https://10.0.2.2:8443", "ABCD-EFGH")

        assertThat(saved.single()).isEqualTo(
            BrokerSettings("10.0.2.2", 8883, device.deviceId, "s3cr3t", DeviceRole.KITCHEN, "r-1"),
        )
        assertThat(outcome).isEqualTo(
            Outcome.Success(Registration(device.deviceId, DeviceRole.KITCHEN, "r-1", "10.0.2.2", 8883)),
        )
    }

    @Test
    fun baseUrlAndCodeAreTrimmedAndTrailingSlashDropped() = runTest {
        sut("  https://stand.local:8443/  ", "  abcd efgh \n")

        assertThat(calls.single()).isEqualTo("https://stand.local:8443" to "abcd efgh")
    }

    @Test
    fun invalidBaseUrlsAreRejectedWithoutCallingTheServer() = runTest {
        listOf(
            "", "http://10.0.2.2:8443", "HTTPS://h", "https://", "https://h/api", "https://h?x=1", "https://u@h",
            "https://h:0", "https://h:99999", "ftp://h", "https://h h", "https://h#f",
        ).forEach { url ->
            assertWithMessage(url).that(sut(url, "code")).isEqualTo(Outcome.Failure(ProvisionError.InvalidBaseUrl))
        }
        assertThat(calls).isEmpty()
    }

    @Test
    fun blankCodeIsRejectedWithoutCallingTheServer() = runTest {
        assertThat(sut("https://h", "   ")).isEqualTo(Outcome.Failure(ProvisionError.EmptyCode))
        assertThat(calls).isEmpty()
    }

    @Test
    fun serverRefusalIsPassedThroughAndNothingIsStored() = runTest {
        listOf(
            ProvisionError.CodeInvalid, ProvisionError.CodeUsed, ProvisionError.CodeExpired,
            ProvisionError.RateLimited(30), ProvisionError.NoNetwork, ProvisionError.Tls,
        ).forEach { error ->
            answer = Outcome.Failure(error)
            assertWithMessage(error.code).that(sut("https://h", "c")).isEqualTo(Outcome.Failure(error))
        }
        assertThat(saved).isEmpty()
    }

    @Test
    fun storageFailureIsReportedWithoutItsMessage() = runTest {
        saveFailure = IOException("keystore: s3cr3t")

        assertThat(sut("https://h", "c")).isEqualTo(Outcome.Failure(ProvisionError.StorageFailed))
    }

    @Test
    fun throwingApiBecomesFailedWithClassOnly() = runTest {
        val broken = Provision({ _, _ -> throw IllegalStateException("code=ABCD") }, store, reset)

        assertThat(broken("https://h", "ABCD"))
            .isEqualTo(Outcome.Failure(ProvisionError.Failed("IllegalStateException")))
    }

    @Test
    fun registrationUnderAnotherDeviceClearsPreviousDeviceDataBeforeSavingCredentials() = runTest {
        // R-3: эмулятор работал под dev-emu-36 (DEV_CONFIG), теперь получает d-…: тень, outbox и журнал команд
        // прежнего устройства не должны применяться и уходить под новым deviceId
        previous("dev-emu-36")

        assertThat(sut("https://h", "c")).isInstanceOf(Outcome.Success::class.java)
        assertThat(effects).containsExactly("reset", "save d-1f2e").inOrder()
    }

    @Test
    fun firstRegistrationKeepsWhatTheAgentBufferedBeforeIt() = runTest {
        sut("https://h", "c")

        assertThat(effects).containsExactly("save d-1f2e")
    }

    @Test
    fun repeatedRegistrationOfTheSameDeviceKeepsItsData() = runTest {
        previous("d-1f2e")

        sut("https://h", "c")

        assertThat(effects).containsExactly("save d-1f2e")
    }

    @Test
    fun unreadablePreviousCredentialsAreTreatedAsAnotherDevice() = runTest {
        loadFailure = IOException("keystore busy")

        sut("https://h", "c")

        assertThat(effects).containsExactly("reset", "save d-1f2e").inOrder()
    }

    @Test
    fun failedResetIsStorageFailedAndCredentialsAreNotSaved() = runTest {
        previous("dev-emu-36")
        resetFailure = IOException("disk full")

        assertThat(sut("https://h", "c")).isEqualTo(Outcome.Failure(ProvisionError.StorageFailed))
        assertThat(saved.map { it.deviceId.value }).containsExactly("dev-emu-36")
    }

    @Test
    fun deviceToStringHidesPassword() {
        assertThat(device.toString()).doesNotContain("s3cr3t")
        assertThat(device.toString()).contains("d-1f2e")
    }
}
