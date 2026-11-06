package ru.restofleet.agent.data.collectors

import android.content.Intent
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Looper
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import ru.restofleet.agent.domain.model.NetworkState
import ru.restofleet.agent.domain.model.NetworkType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ObserversTest {
    private fun pkgIntent(action: String, pkg: String) = Intent(action, Uri.parse("package:$pkg"))

    @Test
    fun changeOfMapsActions() {
        assertThat(PackageEventsObserver.changeOf(Intent.ACTION_PACKAGE_REPLACED, true))
            .isEqualTo(PackageChange.Change.REPLACED)
        assertThat(PackageEventsObserver.changeOf(Intent.ACTION_PACKAGE_ADDED, true)).isNull()
        assertThat(PackageEventsObserver.changeOf(Intent.ACTION_PACKAGE_ADDED, false))
            .isEqualTo(PackageChange.Change.ADDED)
        assertThat(PackageEventsObserver.changeOf(Intent.ACTION_PACKAGE_REMOVED, false))
            .isEqualTo(PackageChange.Change.REMOVED)
        assertThat(PackageEventsObserver.changeOf("other", false)).isNull()
        assertThat(PackageEventsObserver.changeOf(null, false)).isNull()
    }

    @Test
    fun watchedPackageEventsAreEmitted() = runTest {
        val observer = PackageEventsObserver(appContext)
        observer.events(setOf("com.watched")).test {
            appContext.sendBroadcast(pkgIntent(Intent.ACTION_PACKAGE_REMOVED, "com.other"))
            appContext.sendBroadcast(pkgIntent(Intent.ACTION_PACKAGE_REMOVED, "com.watched"))
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(awaitItem()).isEqualTo(PackageChange("com.watched", PackageChange.Change.REMOVED, null))
            appContext.sendBroadcast(pkgIntent(Intent.ACTION_PACKAGE_ADDED, "com.watched"))
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(awaitItem().change).isEqualTo(PackageChange.Change.ADDED)
            appContext.sendBroadcast(Intent(Intent.ACTION_PACKAGE_ADDED))
            shadowOf(Looper.getMainLooper()).idle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun networkChangesAreEmittedAndDeduplicated() = runTest {
        val cm = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
        val network = ShadowNetwork.newInstance(100)
        NetworkChangeObserver(appContext) { 30 }.changes().test {
            val callbacks = shadowOf(cm).networkCallbacks
            callbacks.forEach { it.onCapabilitiesChanged(network, caps) }
            assertThat(awaitItem()).isEqualTo(NetworkState(NetworkType.CELLULAR, null, false))
            callbacks.forEach { it.onCapabilitiesChanged(network, caps) }
            callbacks.forEach { it.onLost(network) }
            assertThat(awaitItem()).isEqualTo(NetworkReader.NONE)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun replacedEventCarriesVersionCode() = runTest {
        PackageEventsObserver(appContext).events(setOf(appContext.packageName)).test {
            appContext.sendBroadcast(pkgIntent(Intent.ACTION_PACKAGE_REPLACED, appContext.packageName))
            shadowOf(Looper.getMainLooper()).idle()
            val change = awaitItem()
            assertThat(change.change).isEqualTo(PackageChange.Change.REPLACED)
            assertThat(change.versionCode as Any?).isNotNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun receiverIsUnregisteredAfterCancel() = runTest {
        val probe = pkgIntent(Intent.ACTION_PACKAGE_ADDED, "com.watched")
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        PackageEventsObserver(appContext).events(setOf("com.watched")).test {
            assertThat(shadowOf(app).hasReceiverForIntent(probe)).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(shadowOf(app).hasReceiverForIntent(probe)).isFalse()
    }

    @Test
    fun receiverFailureClosesFlowInsteadOfCrashing() = runTest {
        val exploding = object : Set<String> by emptySet() {
            override fun contains(element: String): Boolean = throw IllegalStateException("boom")
        }
        PackageEventsObserver(appContext).events(exploding).test {
            appContext.sendBroadcast(pkgIntent(Intent.ACTION_PACKAGE_ADDED, "com.any"))
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(awaitError()).hasMessageThat().isEqualTo("boom")
        }
    }

    @Test
    fun networkCallbackIsUnregisteredAfterCancel() = runTest {
        val cm = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        NetworkChangeObserver(appContext) { 30 }.changes().test {
            assertThat(shadowOf(cm).networkCallbacks).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(shadowOf(cm).networkCallbacks).isEmpty()
    }

    @Test
    fun networkCallbackFailureClosesFlow() = runTest {
        val cm = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        NetworkChangeObserver(appContext) { error("api failed") }.changes().test {
            shadowOf(cm).networkCallbacks.forEach { it.onCapabilitiesChanged(ShadowNetwork.newInstance(1), caps) }
            assertThat(awaitError()).hasMessageThat().isEqualTo("api failed")
        }
    }
}
