package ru.restofleet.agent.data.deviceadmin

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.shared.kernel.Outcome
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DpmDeviceAdminTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val self = context.packageName
    private val admin = ComponentName(self, "ru.restofleet.agent.AdminReceiver")

    // Строгие моки: любой незаявленный вызов роняет тест — так проверяется «не обращается к DPM / сети / установщику»
    private val dpm = mockk<DevicePolicyManager>()
    private val downloader = mockk<ApkDownloader>()
    private val installer = mockk<PackageInstallerSession>()
    private val lockTask = mockk<LockTaskLauncher>()

    private fun sut(api: Int = 34) =
        DpmDeviceAdmin(context, dpm, admin, downloader, installer, lockTask, { api }, Dispatchers.IO)

    private fun owner(isOwner: Boolean) = every { dpm.isDeviceOwnerApp(self) } returns isOwner

    private val grantedState = DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
    private val content = "apk-bytes".toByteArray()
    private val goodHash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private val url = "https://apk-host/app.apk"

    private fun apk(): File = tmp.newFile().also { it.writeBytes(content) }

    @Test
    fun isDeviceOwnerAsksDpmAboutOwnPackage() = blocking {
        owner(true)
        assertThat(sut().isDeviceOwner()).isTrue()
        owner(false)
        assertThat(sut().isDeviceOwner()).isFalse()
    }

    // ---------- reboot ----------

    @Test
    fun rebootWithoutDeviceOwnerDoesNotTouchDpmBeyondTheRoleCheck() = blocking {
        owner(false)
        assertThat(sut().reboot()).isEqualTo(Outcome.Failure(DeviceAdminError.NotDeviceOwner))
        verify(exactly = 0) { dpm.reboot(any()) }
    }

    @Test
    fun rebootAsDeviceOwnerCallsDpm() = blocking {
        owner(true)
        every { dpm.reboot(admin) } returns Unit
        assertThat(sut().reboot()).isEqualTo(Outcome.Success(Unit))
        verify(exactly = 1) { dpm.reboot(admin) }
    }

    @Test
    fun rebootDuringACallIsUnsupported() = blocking {
        owner(true)
        every { dpm.reboot(admin) } throws IllegalStateException("ongoing call")
        assertThat(sut().reboot()).isEqualTo(Outcome.Failure(DeviceAdminError.Unsupported("call_in_progress")))
    }

    @Test
    fun rebootAfterLosingTheRoleIsNotDeviceOwner() = blocking {
        owner(true)
        every { dpm.reboot(admin) } throws SecurityException("not owner")
        assertThat(sut().reboot()).isEqualTo(Outcome.Failure(DeviceAdminError.NotDeviceOwner))
    }

    // ---------- installApk ----------

    @Test
    fun installWithoutDeviceOwnerTouchesNeitherNetworkNorInstaller() = blocking {
        owner(false)
        assertThat(sut().installApk(url, goodHash)).isEqualTo(Outcome.Failure(DeviceAdminError.NotDeviceOwner))
        coVerify(exactly = 0) { downloader.download(any()) }
        coVerify(exactly = 0) { installer.install(any()) }
    }

    @Test
    fun malformedHashIsRefusedBeforeDownloading() = blocking {
        owner(true)
        for (bad in listOf("", "abc", goodHash.dropLast(1), "z".repeat(64))) {
            assertThat(sut().installApk(url, bad))
                .isEqualTo(Outcome.Failure(DeviceAdminError.Unsupported("invalid_sha256")))
        }
        coVerify(exactly = 0) { downloader.download(any()) }
    }

    @Test
    fun hashMismatchNeverReachesPackageInstallerAndDeletesTheFile() = blocking {
        owner(true)
        val file = apk()
        coEvery { downloader.download(url) } returns Outcome.Success(file)
        val other = "0".repeat(64)
        assertThat(sut().installApk(url, other)).isEqualTo(Outcome.Failure(DeviceAdminError.HashMismatch))
        coVerify(exactly = 0) { installer.install(any()) }
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun matchingHashIsInstalledAndFileIsDeletedAfterwards() = blocking {
        owner(true)
        val file = apk()
        coEvery { downloader.download(url) } returns Outcome.Success(file)
        var existedDuringInstall = false
        coEvery { installer.install(file) } answers {
            existedDuringInstall = file.exists()
            Outcome.Success(Unit)
        }
        assertThat(sut().installApk(url, goodHash.uppercase())).isEqualTo(Outcome.Success(Unit))
        assertThat(existedDuringInstall).isTrue()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun installerFailureIsReturnedAndFileIsDeleted() = blocking {
        owner(true)
        val file = apk()
        coEvery { downloader.download(url) } returns Outcome.Success(file)
        val failure = Outcome.Failure(DeviceAdminError.InstallFailed(3, "aborted"))
        coEvery { installer.install(file) } returns failure
        assertThat(sut().installApk(url, goodHash)).isEqualTo(failure)
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun installerExceptionPropagatesButFileIsStillDeleted() {
        owner(true)
        val file = apk()
        coEvery { downloader.download(url) } returns Outcome.Success(file)
        coEvery { installer.install(file) } throws IllegalStateException("boom")
        val thrown = runCatching { runBlocking { sut().installApk(url, goodHash) } }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun downloadFailureIsReturnedAsIsAndInstallerIsNotCalled() = blocking {
        owner(true)
        val failure = Outcome.Failure(DeviceAdminError.DownloadFailed("http_404"))
        coEvery { downloader.download(url) } returns failure
        assertThat(sut().installApk(url, goodHash)).isEqualTo(failure)
        coVerify(exactly = 0) { installer.install(any()) }
    }

    @Test
    fun secondInstallWhileOneRunsFailsFastAndLockIsReleasedAfterwards() = blocking {
        owner(true)
        val file = apk()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { downloader.download(url) } answers { Outcome.Success(file) }
        coEvery { installer.install(any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            Outcome.Success(Unit)
        }
        val device = sut()
        val first = async(Dispatchers.Default) { device.installApk(url, goodHash) }
        withTimeout(5_000) { entered.await() }

        assertThat(device.installApk(url, goodHash))
            .isEqualTo(Outcome.Failure(DeviceAdminError.Unsupported("install_in_progress")))

        release.complete(Unit)
        assertThat(first.await()).isEqualTo(Outcome.Success(Unit))
        file.writeBytes(content)
        assertThat(device.installApk(url, goodHash)).isEqualTo(Outcome.Success(Unit))
    }

    @Test
    fun cancelledInstallDeletesFileAndReleasesTheLock() = blocking {
        owner(true)
        val file = apk()
        val entered = CompletableDeferred<Unit>()
        coEvery { downloader.download(url) } returns Outcome.Success(file)
        coEvery { installer.install(any()) } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        val device = sut()
        val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { device.installApk(url, goodHash) }
        withTimeout(5_000) { entered.await() }
        withTimeout(5_000) { job.cancelAndJoin() }

        assertThat(job.isCancelled).isTrue()
        assertThat(file.exists()).isFalse()

        coEvery { installer.install(any()) } returns Outcome.Success(Unit)
        file.writeBytes(content)
        assertThat(device.installApk(url, goodHash)).isEqualTo(Outcome.Success(Unit))
    }

    // ---------- lock task ----------

    @Test
    fun lockTaskWithoutDeviceOwnerDoesNothing() = blocking {
        owner(false)
        assertThat(sut().startLockTask("com.android.chrome", null))
            .isEqualTo(Outcome.Failure(DeviceAdminError.NotDeviceOwner))
        verify(exactly = 0) { dpm.setLockTaskPackages(any(), any()) }
    }

    @Test
    fun lockTaskOfMissingPackageDoesNotChangeTheWhitelist() = blocking {
        owner(true)
        val missing = Outcome.Failure(DeviceAdminError.Unsupported("package_not_installed"))
        every { lockTask.target("com.android.chrome", null) } returns missing
        assertThat(sut().startLockTask("com.android.chrome", null)).isEqualTo(missing)
        verify(exactly = 0) { dpm.setLockTaskPackages(any(), any()) }
    }

    @Test
    fun lockTaskWhitelistsSelfAndTargetThenStarts() = blocking {
        owner(true)
        val target = Intent(Intent.ACTION_VIEW)
        val packages = slot<Array<String>>()
        every { lockTask.target("com.android.chrome", "https://x.test/") } returns Outcome.Success(target)
        every { dpm.setLockTaskPackages(admin, capture(packages)) } returns Unit
        every { lockTask.start(target, "com.android.chrome", "https://x.test/") } returns Outcome.Success(Unit)
        assertThat(sut().startLockTask("com.android.chrome", "https://x.test/")).isEqualTo(Outcome.Success(Unit))
        assertThat(packages.captured.toList()).containsExactly(self, "com.android.chrome").inOrder()
    }

    @Test
    fun lockTaskStartFailureIsReturned() = blocking {
        owner(true)
        val target = Intent(Intent.ACTION_VIEW)
        val failure = Outcome.Failure(DeviceAdminError.Unsupported("activity_not_found"))
        every { lockTask.target("p.q", null) } returns Outcome.Success(target)
        every { dpm.setLockTaskPackages(admin, any()) } returns Unit
        every { lockTask.start(target, "p.q", null) } returns failure
        assertThat(sut().startLockTask("p.q", null)).isEqualTo(failure)
    }

    @Test
    fun lockTaskAfterLosingTheRoleIsNotDeviceOwner() = blocking {
        owner(true)
        every { lockTask.target("p.q", null) } returns Outcome.Success(Intent())
        every { dpm.setLockTaskPackages(admin, any()) } throws SecurityException("not owner")
        assertThat(sut().startLockTask("p.q", null)).isEqualTo(Outcome.Failure(DeviceAdminError.NotDeviceOwner))
    }

    // ---------- permissions ----------

    @Test
    fun grantWithoutDeviceOwnerGrantsNothing() = blocking {
        owner(false)
        val result = sut(34).grantSelfPermissions()
        assertThat(result.values.toSet()).containsExactly(false)
        assertThat(result.keys).containsExactly(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.PACKAGE_USAGE_STATS,
        )
        verify(exactly = 0) { dpm.setPermissionGrantState(any(), any(), any(), any()) }
    }

    @Test
    fun grantAsDeviceOwnerSetsGrantedStateAndUsageAccessStaysDenied() = blocking {
        owner(true)
        every { dpm.setPermissionGrantState(admin, self, any(), grantedState) } returns true
        val result = sut(34).grantSelfPermissions()
        assertThat(result).containsExactly(
            Manifest.permission.ACCESS_FINE_LOCATION, true,
            Manifest.permission.POST_NOTIFICATIONS, true,
            Manifest.permission.READ_PHONE_STATE, true,
            Manifest.permission.PACKAGE_USAGE_STATS, false,
        )
        verify(exactly = 0) {
            dpm.setPermissionGrantState(any(), any(), Manifest.permission.PACKAGE_USAGE_STATS, any())
        }
    }

    @Test
    fun postNotificationsIsSkippedBelowApi33() = blocking {
        owner(true)
        every { dpm.setPermissionGrantState(admin, self, any(), any()) } returns true
        val result = sut(32).grantSelfPermissions()
        assertThat(result.keys).doesNotContain(Manifest.permission.POST_NOTIFICATIONS)
        verify(exactly = 0) {
            dpm.setPermissionGrantState(any(), any(), Manifest.permission.POST_NOTIFICATIONS, any())
        }
    }

    @Test
    fun refusedOrFailedGrantsAreReportedAsFalse() = blocking {
        owner(true)
        every { dpm.setPermissionGrantState(admin, self, Manifest.permission.ACCESS_FINE_LOCATION, any()) } returns
            false
        every {
            dpm.setPermissionGrantState(admin, self, Manifest.permission.READ_PHONE_STATE, any())
        } throws SecurityException("no")
        val result = sut(32).grantSelfPermissions()
        assertThat(result).containsExactly(
            Manifest.permission.ACCESS_FINE_LOCATION, false,
            Manifest.permission.READ_PHONE_STATE, false,
            Manifest.permission.PACKAGE_USAGE_STATS, false,
        )
    }

    private fun blocking(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { block() }
    }
}
