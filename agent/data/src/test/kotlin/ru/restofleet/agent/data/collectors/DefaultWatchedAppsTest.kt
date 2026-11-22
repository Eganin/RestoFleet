package ru.restofleet.agent.data.collectors

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** Отслеживаемые приложения по умолчанию из сборки и видимость пакетов (ПМ5 5.2, реш. 425). */
class DefaultWatchedAppsTest {
    @Test
    fun buildPropertyIsParsedIntoPackages() {
        assertThat(DefaultWatchedApps.parse("com.android.chrome, ru.restofleet.dummy.waiter,,").packages)
            .containsExactly("com.android.chrome", "ru.restofleet.dummy.waiter").inOrder()
        assertThat(DefaultWatchedApps.parse("").packages).isEmpty()
    }

    @Test
    fun manifestSeesLauncherAppsWithoutListingPackagesOrQueryAll() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        assertThat(queries).contains("android.intent.category.LAUNCHER")
        assertThat(queries).doesNotContain("<package")
        assertThat(manifest).doesNotContain("android.permission.QUERY_ALL_PACKAGES")
    }
}
