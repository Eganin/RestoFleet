package ru.restofleet.agent.data.shadow

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.port.ShadowStore

internal class StubShadowStore(var shadow: DesiredShadow? = null, var failure: Exception? = null) : ShadowStore {
    override suspend fun appliedVersion(): Long? = shadow?.version

    override suspend fun save(shadow: DesiredShadow) {
        this.shadow = shadow
    }

    override suspend fun load(): DesiredShadow? {
        failure?.let { throw it }
        return shadow
    }

    override fun observe(): Flow<DesiredShadow?> = emptyFlow()
}

class ShadowWatchedAppsTest {
    private val static = setOf("com.android.chrome")

    private fun shadow(vararg apps: String) = DesiredShadow(1, 30, apps.toList(), emptyList())

    @Test
    fun withoutAShadowOnlyTheStaticListIsWatched() = runTest {
        assertThat(ShadowWatchedApps(static, StubShadowStore()).packages()).containsExactly("com.android.chrome")
    }

    @Test
    fun shadowPackagesAreUnitedWithTheStaticList() = runTest {
        val store = StubShadowStore(shadow("ru.restofleet.dummy.waiter", "com.android.chrome"))

        assertThat(ShadowWatchedApps(static, store).packages())
            .containsExactly("com.android.chrome", "ru.restofleet.dummy.waiter")
    }

    @Test
    fun blankAndInvalidNamesFromTheShadowAreDropped() = runTest {
        val store = StubShadowStore(shadow("", "  ", "evil name", "x", "a.b"))

        assertThat(ShadowWatchedApps(static, store).packages()).containsExactly("com.android.chrome", "a.b")
    }

    @Test
    fun newShadowIsSeenOnTheNextCall() = runTest {
        val store = StubShadowStore()
        val watched = ShadowWatchedApps(static, store)
        assertThat(watched.packages()).hasSize(1)

        store.save(shadow("a.b"))

        assertThat(watched.packages()).containsExactly("com.android.chrome", "a.b")
    }

    @Test
    fun storeFailureFallsBackToTheStaticList() = runTest {
        val store = StubShadowStore(failure = IllegalStateException("io"))

        assertThat(ShadowWatchedApps(static, store).packages()).containsExactly("com.android.chrome")
    }
}
