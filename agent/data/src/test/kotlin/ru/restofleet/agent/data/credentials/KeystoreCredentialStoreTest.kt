package ru.restofleet.agent.data.credentials

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.shared.kernel.DeviceId
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import android.security.keystore.KeyPermanentlyInvalidatedException
import javax.crypto.AEADBadTagException
import javax.crypto.IllegalBlockSizeException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeystoreCredentialStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefs: SharedPreferences
    private val keys = AesGcmCipherTest.TestKeys()
    private val realCipher = AesGcmCipher(keys)
    private val settings = BrokerSettings("broker.example.com", 8883, DeviceId.parse("dev-emu-36")!!, "pw-9f3c-value")

    @Before
    fun setUp() {
        prefs = context.getSharedPreferences("store-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun store(cipher: BlobCipher = realCipher, p: SharedPreferences = prefs) =
        KeystoreCredentialStore(p, cipher, Dispatchers.Unconfined)

    private fun blobKey(): String = prefs.all.keys.single { it != "unreadable" }

    private fun tamper() {
        val key = blobKey()
        val bytes = Base64.decode(prefs.getString(key, null), Base64.NO_WRAP)
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        prefs.edit().putString(key, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
    }

    private class ThrowingCipher(private val failure: Throwable) : BlobCipher {
        var discarded = 0

        override fun encrypt(plain: ByteArray): ByteArray = ByteArray(0)

        override fun decrypt(blob: ByteArray): ByteArray = throw failure

        override fun discardKey() {
            discarded++
        }
    }

    @Test
    fun saveThenLoadReturnsSameSettings() = runBlocking {
        store().save(settings)

        assertThat(store().load()).isEqualTo(settings)
        assertThat(store().inspect()).isEqualTo(StoredCredentials.Present(settings))
    }

    @Test
    fun loadWithoutRecordIsNullAndInspectIsMissing() = runBlocking {
        assertThat(store().load()).isNull()
        assertThat(store().inspect()).isEqualTo(StoredCredentials.Missing)
    }

    @Test
    fun secretsAreNotStoredInPlainText() = runBlocking {
        store().save(settings)

        val everything = prefs.all.entries.joinToString("|") { "${it.key}=${it.value}" }
        val decodedBlobs = prefs.all.values.filterIsInstance<String>()
            .joinToString("|") { String(Base64.decode(it, Base64.NO_WRAP), Charsets.ISO_8859_1) }
        for (secret in listOf(settings.password, settings.host, settings.deviceId.value)) {
            assertThat(everything).doesNotContain(secret)
            assertThat(decodedBlobs).doesNotContain(secret)
        }
    }

    @Test
    fun saveOverwritesPreviousRecordWithFreshIv() = runBlocking {
        store().save(settings)
        val first = prefs.getString(blobKey(), null)

        store().save(settings)

        assertThat(prefs.getString(blobKey(), null)).isNotEqualTo(first)
        assertThat(store().load()).isEqualTo(settings)
    }

    @Test
    fun tamperedCipherTextGivesNullAndWipesRecordWithoutThrowing() = runBlocking {
        store().save(settings)
        tamper()

        assertThat(store().load()).isNull()

        assertThat(prefs.all.keys).containsExactly("unreadable")
        assertThat(prefs.getString("unreadable", null)).isEqualTo("AEADBadTagException")
    }

    @Test
    fun permanentFailuresGiveNullWipeRecordAndDiscardKey() {
        val failures = listOf(
            AEADBadTagException("tag"),
            KeyPermanentlyInvalidatedException("invalidated"),
            IllegalArgumentException("short blob"),
        )
        for (failure in failures) {
            prefs.edit().clear().commit()
            runBlocking { store().save(settings) }
            val cipher = ThrowingCipher(failure)

            val loaded = runBlocking { store(cipher).load() }

            assertThat(loaded).isNull()
            assertThat(prefs.all.keys).containsExactly("unreadable")
            assertThat(prefs.getString("unreadable", null)).isEqualTo(failure::class.java.simpleName)
            assertThat(cipher.discarded).isEqualTo(1)
        }
    }

    // I4: временный сбой Keystore (keystore2 «system error», OTA, нехватка памяти) не стирает учётку и ключ
    private val transientFailures = listOf(
        KeyStoreException("keystore"),
        // AndroidKeyStoreProvider заворачивает в UnrecoverableKeyException и временные ошибки keystore2
        // («Failed to obtain information about key»), поэтому стирать по нему нельзя
        UnrecoverableKeyException("Failed to obtain information about key"),
        ProviderException("Keystore operation failed"),
        InvalidKeyException("Keystore operation failed"),
        IllegalBlockSizeException(),
        GeneralSecurityException("generic"),
    )

    @Test
    fun transientKeystoreFailuresKeepRecordAndKeyAndLoadThrows() {
        for (failure in transientFailures) {
            prefs.edit().clear().commit()
            runBlocking { store().save(settings) }
            val before = prefs.getString("blob", null)
            val cipher = ThrowingCipher(failure)

            val thrown = assertThrows(CredentialsUnavailableException::class.java) {
                runBlocking { store(cipher).load() }
            }

            assertThat(thrown.reason).isEqualTo(failure::class.java.simpleName)
            assertThat(thrown.message).doesNotContain(settings.password)
            assertThat(prefs.all.keys).containsExactly("blob")
            assertThat(prefs.getString("blob", null)).isEqualTo(before)
            assertThat(cipher.discarded).isEqualTo(0)
        }
    }

    @Test
    fun transientFailureIsReportedAsUnavailableAndNextReadSucceeds() = runBlocking {
        store().save(settings)

        assertThat(store(ThrowingCipher(ProviderException("Keystore operation failed"))).inspect())
            .isEqualTo(StoredCredentials.Unavailable("ProviderException"))
        assertThat(store().inspect()).isEqualTo(StoredCredentials.Present(settings))
        assertThat(store().load()).isEqualTo(settings)
    }

    @Test
    fun corruptedBase64GivesNullAndWipes() = runBlocking {
        store().save(settings)
        prefs.edit().putString(blobKey(), "***not base64***").commit()

        assertThat(store().load()).isNull()
        assertThat(prefs.all.keys).containsExactly("unreadable")
        assertThat(prefs.getString("unreadable", null)).isEqualTo("IllegalArgumentException")
    }

    @Test
    fun wellEncryptedGarbageGivesNullAndWipes() = runBlocking {
        val garbage = realCipher.encrypt(byteArrayOf(1, 2, 3))
        prefs.edit().putString("blob", Base64.encodeToString(garbage, Base64.NO_WRAP)).commit()

        assertThat(store().load()).isNull()
        assertThat(prefs.all.keys).containsExactly("unreadable")
        assertThat(prefs.getString("unreadable", null)).isEqualTo("malformed")
    }

    @Test
    fun inspectReportsUnreadableAfterLoadWipedRecord() = runBlocking {
        store().save(settings)
        tamper()
        store().load()

        assertThat(store().inspect()).isEqualTo(StoredCredentials.Unreadable("AEADBadTagException"))
    }

    @Test
    fun inspectOfBrokenRecordReportsUnreadableOnFirstCall() = runBlocking {
        store().save(settings)
        tamper()

        assertThat(store().inspect()).isEqualTo(StoredCredentials.Unreadable("AEADBadTagException"))
    }

    @Test
    fun saveAfterFailureClearsMarkerAndRestoresRecord() = runBlocking {
        store().save(settings)
        tamper()
        store().load()

        store().save(settings)

        assertThat(prefs.all.keys).doesNotContain("unreadable")
        assertThat(store().inspect()).isEqualTo(StoredCredentials.Present(settings))
    }

    @Test
    fun errorFromCipherIsNotSwallowedAndRecordStays() {
        runBlocking { store().save(settings) }
        val before = prefs.getString("blob", null)

        assertThrows(AssertionError::class.java) {
            runBlocking { store(ThrowingCipher(AssertionError("fatal"))).load() }
        }

        assertThat(prefs.getString("blob", null)).isEqualTo(before)
    }

    @Test
    fun failedCommitOnSaveThrowsIoException() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.commit() } returns false
        val failing = mockk<SharedPreferences>(relaxed = true)
        every { failing.edit() } returns editor

        assertThrows(IOException::class.java) { runBlocking { store(p = failing).save(settings) } }
    }
}
