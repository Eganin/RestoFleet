package ru.restofleet.agent

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import ru.restofleet.agent.data.di.AGENT_DISPATCHER
import ru.restofleet.agent.data.lifecycle.ServiceStarter
import ru.restofleet.agent.domain.error.ProvisionError
import ru.restofleet.agent.domain.model.DeviceRole
import ru.restofleet.agent.domain.model.ProvisionedDevice
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.agent.domain.port.ProvisioningApi
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = RestoFleetApp::class)
class DevConfigReceiverTest {
    @get:Rule val workManagerIsolation = WorkManagerIsolationRule()

    private class MemoryStore(var failure: Throwable? = null) : CredentialStore {
        val saved = mutableListOf<BrokerSettings>()

        override suspend fun save(s: BrokerSettings) {
            failure?.let { throw it }
            saved += s
        }

        override suspend fun load() = saved.lastOrNull()
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = MemoryStore()
    private val starts = mutableListOf<String>()
    private val provisionCalls = mutableListOf<Pair<String, String>>()
    private var provisionAnswer: Outcome<ProvisionedDevice, ProvisionError> = Outcome.Success(
        ProvisionedDevice(DeviceId.parse("d-77")!!, PASSWORD, "r-1", DeviceRole.KITCHEN, "10.0.2.2", 8883),
    )

    @Before
    fun setUp() {
        loadKoinModules(
            module {
                single<CredentialStore> { store }
                single<ServiceStarter> { ServiceStarter { starts += it } }
                single<CoroutineDispatcher>(AGENT_DISPATCHER) { Dispatchers.IO }
                single<ProvisioningApi> {
                    ProvisioningApi { url, code ->
                        provisionCalls += url to code
                        provisionAnswer
                    }
                }
            },
        )
    }

    @After
    fun tearDown() = stopKoin()

    private fun intent(
        host: String? = "10.0.2.2",
        port: Int? = 8883,
        deviceId: String? = "dev-emu-36",
        password: String? = PASSWORD,
    ) = Intent(DevConfigReceiver.ACTION).apply {
        host?.let { putExtra("host", it) }
        port?.let { putExtra("port", it) }
        deviceId?.let { putExtra("deviceId", it) }
        password?.let { putExtra("password", it) }
    }

    private fun apply(intent: Intent) = runBlocking { DevConfigReceiver().apply(intent) }

    @Test
    fun validBroadcastSavesCredentialsAndStartsServiceOnce() {
        val result = apply(intent())

        assertThat(result).isEqualTo(Activity.RESULT_OK to "ok")
        assertThat(store.saved).containsExactly(BrokerSettings("10.0.2.2", 8883, DeviceId.parse("dev-emu-36")!!, PASSWORD))
        assertThat(starts).containsExactly("dev_config")
    }

    @Test
    fun everyInvalidFieldIsRejectedWithItsCodeAndNothingIsSavedOrStarted() {
        val cases = listOf(
            intent(host = null) to "invalid_host",
            intent(host = "tls://10.0.2.2") to "invalid_host",
            intent(host = "a b") to "invalid_host",
            intent(port = null) to "invalid_port",
            intent(port = 0) to "invalid_port",
            intent(port = 65_536) to "invalid_port",
            intent(deviceId = null) to "invalid_device_id",
            intent(deviceId = "") to "invalid_device_id",
            intent(deviceId = "a/b") to "invalid_device_id",
            intent(deviceId = "a+b") to "invalid_device_id",
            intent(deviceId = "a#b") to "invalid_device_id",
            intent(deviceId = "a b") to "invalid_device_id",
            intent(password = null) to "empty_password",
            intent(password = "") to "empty_password",
        )
        for ((intent, code) in cases) {
            assertThat(apply(intent)).isEqualTo(Activity.RESULT_CANCELED to code)
        }
        assertThat(store.saved).isEmpty()
        assertThat(starts).isEmpty()
    }

    @Test
    fun portSentAsStringIsRejected() {
        val bad = intent(port = null).putExtra("port", "8883")

        assertThat(apply(bad)).isEqualTo(Activity.RESULT_CANCELED to "invalid_port")
    }

    @Test
    fun provisionByCodeStoresRoleAndAnswersWithDeviceId() {
        // План 3.5: DEV_PROVISION — тот же use case Provision, что у мастера
        val result = apply(Intent(DevConfigReceiver.ACTION_PROVISION).putExtra("code", CODE))

        assertThat(result).isEqualTo(Activity.RESULT_OK to "ok deviceId=d-77 role=kitchen")
        assertThat(provisionCalls).containsExactly("https://10.0.2.2:8443" to CODE)
        assertThat(store.saved.single().role).isEqualTo(DeviceRole.KITCHEN)
        assertThat(starts).containsExactly("dev_config")
    }

    @Test
    fun provisionUsesTheGivenBaseUrl() {
        val intent = Intent(DevConfigReceiver.ACTION_PROVISION).putExtra("code", CODE)

        apply(intent.putExtra("baseUrl", "https://h:18443"))

        assertThat(provisionCalls.single().first).isEqualTo("https://h:18443")
    }

    @Test
    fun provisionRefusalIsAnsweredWithItsCodeOnly() {
        provisionAnswer = Outcome.Failure(ProvisionError.CodeUsed)

        val result = apply(Intent(DevConfigReceiver.ACTION_PROVISION).putExtra("code", CODE))

        assertThat(result).isEqualTo(Activity.RESULT_CANCELED to "token_used")
        assertThat(store.saved).isEmpty()
        assertThat(starts).isEmpty()
    }

    @Test
    fun provisionWithoutCodeIsRejectedLocally() {
        assertThat(apply(Intent(DevConfigReceiver.ACTION_PROVISION)))
            .isEqualTo(Activity.RESULT_CANCELED to "empty_code")
        assertThat(provisionCalls).isEmpty()
    }

    @Test
    fun storageFailureIsReportedAndServiceIsNotStarted() {
        store.failure = IllegalStateException("disk")

        assertThat(apply(intent())).isEqualTo(Activity.RESULT_CANCELED to "storage_failed")
        assertThat(starts).isEmpty()
    }

    @Test
    fun foreignActionIsIgnoredWithoutTouchingStoreOrService() {
        DevConfigReceiver().onReceive(context, intent().setAction("some.other.ACTION"))

        assertThat(store.saved).isEmpty()
        assertThat(starts).isEmpty()
    }

    @Test
    fun orderedBroadcastDeliversResultAndNothingLeaksToLog() {
        val done = CountDownLatch(1)
        var code = Int.MIN_VALUE
        var data: String? = null
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                code = resultCode
                data = resultData
                done.countDown()
            }
        }

        context.sendOrderedBroadcast(
            intent().setComponent(ComponentName(context, DevConfigReceiver::class.java)),
            null,
            resultReceiver,
            null,
            Activity.RESULT_CANCELED,
            null,
            null,
        )
        // Ресивер работает на IO и завершает goAsync позже; ждём, прокачивая главный цикл
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (done.count > 0 && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }

        assertThat(done.count).isEqualTo(0)
        assertThat(code).isEqualTo(Activity.RESULT_OK)
        assertThat(data).isEqualTo("ok")
        assertThat(store.saved).hasSize(1)
        assertThat(ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg}" }).doesNotContain(PASSWORD)
    }

    @Test
    fun debugManifestDeclaresReceiverProtectedByDump() {
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, DevConfigReceiver::class.java),
            PackageManager.GET_META_DATA,
        )

        assertThat(info.exported).isTrue()
        assertThat(info.permission).isEqualTo("android.permission.DUMP")
    }

    @Test
    fun debugManifestRoutesTheProvisionActionToTheReceiver() {
        val receivers = context.packageManager.queryBroadcastReceivers(
            Intent(DevConfigReceiver.ACTION_PROVISION).setPackage(context.packageName),
            0,
        )

        assertThat(receivers.map { it.activityInfo.name }).contains(DevConfigReceiver::class.java.name)
    }

    private companion object {
        const val PASSWORD = "pw-9f3c-unique-value"
        const val CODE = "ABCD-EFGH-1234"
    }
}
