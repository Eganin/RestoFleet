package ru.restofleet.agent.data.deviceadmin

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class Sha256VerifierTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun file(content: ByteArray): File = tmp.newFile().also { it.writeBytes(content) }

    @Test
    fun matchingHashIsAccepted() {
        assertThat(Sha256Verifier.matches(file("abc".toByteArray()), abcHash)).isTrue()
    }

    @Test
    fun hexCaseDoesNotMatter() {
        assertThat(Sha256Verifier.matches(file("abc".toByteArray()), abcHash.uppercase())).isTrue()
    }

    @Test
    fun differentContentIsRejected() {
        assertThat(Sha256Verifier.matches(file("abd".toByteArray()), abcHash)).isFalse()
    }

    @Test
    fun emptyExpectedIsRejectedEvenForEmptyFile() {
        val emptyFileHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertThat(Sha256Verifier.matches(file(ByteArray(0)), "")).isFalse()
        assertThat(Sha256Verifier.matches(file(ByteArray(0)), emptyFileHash)).isTrue()
    }

    @Test
    fun malformedExpectedIsRejected() {
        val f = file("abc".toByteArray())
        assertThat(Sha256Verifier.matches(f, abcHash.dropLast(1))).isFalse()
        assertThat(Sha256Verifier.matches(f, abcHash + "0")).isFalse()
        assertThat(Sha256Verifier.matches(f, "g" + abcHash.drop(1))).isFalse()
        assertThat(Sha256Verifier.matches(f, " $abcHash")).isFalse()
        assertThat(Sha256Verifier.matches(f, "0x$abcHash".take(64))).isFalse()
    }

    @Test
    fun missingFileIsRejected() {
        assertThat(Sha256Verifier.matches(File(tmp.root, "absent.apk"), abcHash)).isFalse()
    }

    @Test
    fun largeFileIsHashedAcrossBuffers() {
        val content = ByteArray(300_000) { (it % 251).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        assertThat(Sha256Verifier.matches(file(content), expected)).isTrue()
        assertThat(Sha256Verifier.matches(file(content.copyOf().also { it[299_999] = 0 }), expected)).isFalse()
    }
}
