package ru.restofleet.server.data.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.server.domain.provisioning.ProvisioningCode
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId
import java.security.SecureRandom

class SecretsTest {

    @Test
    fun `bcrypt uses cost 10, salts every hash and verifies only the right password`() {
        val hasher = BcryptHasher()

        val first = hasher.hash("s3cret")
        val second = hasher.hash("s3cret")

        assertThat(first).startsWith("\$2a\$10\$")
        assertThat(first).isNotEqualTo(second)
        assertThat(hasher.verify("s3cret", first)).isTrue()
        assertThat(hasher.verify("s3cret", second)).isTrue()
        assertThat(hasher.verify("S3cret", first)).isFalse()
    }

    @Test
    fun `broken or foreign hash fails verification without throwing`() {
        val hasher = BcryptHasher(cost = 4)

        listOf("", "plain", "\$2a\$10\$short", "\$2a\$99\$" + "x".repeat(53)).forEach {
            assertThat(hasher.verify("pw", it)).isFalse()
        }
    }

    @Test
    fun `provisioning code is 128 bits of crockford base32 that survives manual normalization`() {
        val secrets = SecureRandomSecrets()

        val codes = List(200) { secrets.provisioningCode() }

        codes.forEach { code ->
            assertThat(code).hasLength(26)
            assertThat(code).matches("[0-9ABCDEFGHJKMNPQRSTVWXYZ]{26}")
            assertThat(ProvisioningCode.normalize(code)).isEqualTo(code)
        }
        assertThat(codes.toSet()).hasSize(200)
    }

    @Test
    fun `crockford encoding packs bits in order and pads the last symbol`() {
        assertThat(SecureRandomSecrets(FixedRandom(0x00)).provisioningCode()).isEqualTo("0".repeat(26))
        assertThat(SecureRandomSecrets(FixedRandom(0xFF)).provisioningCode()).isEqualTo("Z".repeat(25) + "W")
        // 0x84 = 10000 100|00 10000 1|...: 5-битные группы 16, 16, 16… — буква G
        assertThat(SecureRandomSecrets(FixedRandom(0x84, 0x21)).provisioningCode()).startsWith("GGG")
    }

    @Test
    fun `device password and refresh token are 32 random bytes in base64url`() {
        val secrets = SecureRandomSecrets()

        listOf(secrets.devicePassword(), secrets.refreshToken()).forEach {
            assertThat(it).hasLength(43)
            assertThat(it).matches("[A-Za-z0-9_-]{43}")
        }
        assertThat(secrets.devicePassword()).isNotEqualTo(secrets.devicePassword())
    }

    @Test
    fun `sha256 is lowercase hex of utf-8 bytes`() {
        assertThat(Sha256Digest.sha256("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(Sha256Digest.sha256("")).hasLength(64)
        assertThat(Sha256Digest.sha256("код")).isEqualTo(Sha256Digest.sha256("код"))
        assertThat(Sha256Digest.sha256("код")).isNotEqualTo(Sha256Digest.sha256("КОД"))
    }

    @Test
    fun `credential cache returns a digest until ttl and forgets it on invalidate`() {
        val clock = MutableClock(1_000)
        val cache = InMemoryDeviceCredentialCache(clock, ttlMs = 300)
        val a = DeviceId.parse("d-a")!!
        val b = DeviceId.parse("d-b")!!

        cache.put(a, "hash-a")
        cache.put(b, "hash-b")
        clock.now = 1_299
        assertThat(cache.get(a)).isEqualTo("hash-a")
        cache.invalidate(b)
        assertThat(cache.get(b)).isNull()
        clock.now = 1_300
        assertThat(cache.get(a)).isNull()
    }

    @Test
    fun `credential cache is cleared when full instead of growing without bound`() {
        val cache = InMemoryDeviceCredentialCache(MutableClock(0), ttlMs = 1_000, maxEntries = 2)
        val ids = (1..3).map { DeviceId.parse("d-$it")!! }

        ids.forEach { cache.put(it, "h-${it.value}") }

        assertThat(cache.get(ids[0])).isNull()
        assertThat(cache.get(ids[1])).isNull()
        assertThat(cache.get(ids[2])).isEqualTo("h-d-3")
    }

    private class MutableClock(var now: Long) : Clock {
        override fun nowMillis(): Long = now
    }

    /** Повторяет заданные байты по кругу. */
    private class FixedRandom(vararg val pattern: Int) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { bytes[it] = pattern[it % pattern.size].toByte() }
        }
    }
}
