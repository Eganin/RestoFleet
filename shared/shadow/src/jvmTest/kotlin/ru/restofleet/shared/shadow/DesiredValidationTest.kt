package ru.restofleet.shared.shadow

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class DesiredValidationTest {

    private data class Target(val id: String, val url: String)

    private val api = Target("api", "https://api.example/health")
    private val kds = Target("kds", "https://kds.example/")

    private fun validate(
        interval: Int = 30,
        apps: List<String> = listOf("com.android.chrome"),
        probes: List<Target> = listOf(api, kds),
    ) = validateDesired(interval, apps, probes, Target::id, Target::url)

    @Test
    fun validDesiredPassesUnchanged() {
        val result = validate()
        assertThat(result.errors).isEmpty()
        assertThat(result.isValid).isTrue()
        assertThat(result.telemetryIntervalSec).isEqualTo(30)
        assertThat(result.watchedApps).containsExactly("com.android.chrome")
        assertThat(result.probes).containsExactly(api, kds).inOrder()
    }

    @Test
    fun intervalBoundaries() {
        val cases = mapOf(9 to null, 10 to 10, 3_600 to 3_600, 3_601 to null, 0 to null, -30 to null)
        cases.forEach { (input, expected) ->
            val result = validate(interval = input)
            assertWithMessage("interval $input").that(result.telemetryIntervalSec).isEqualTo(expected)
            val errors = if (expected == null) listOf("telemetryIntervalSec:out_of_range") else emptyList()
            assertWithMessage("errors for $input").that(result.errors).isEqualTo(errors)
        }
        assertThat(DesiredLimits.TELEMETRY_INTERVAL_SEC).isEqualTo(10..3_600)
        assertThat(DesiredLimits.DEFAULT_TELEMETRY_INTERVAL_SEC).isEqualTo(30)
    }

    @Test
    fun watchedAppsTable() {
        val result = validate(apps = listOf("com.android.chrome", "", " ", "Chrome", "com.android.chrome", "a.b"))
        assertThat(result.watchedApps).containsExactly("com.android.chrome", "a.b").inOrder()
        assertThat(result.errors).containsExactly(
            "watchedApps:empty", "watchedApps:empty", "watchedApps:invalid_name:Chrome",
        ).inOrder()
    }

    @Test
    fun probesTable() {
        val result = validate(
            probes = listOf(
                api,
                Target("", "https://x"),
                Target(" ", "https://y"),
                Target("kds", ""),
                Target("api", "https://dup"),
                kds,
            ),
        )
        assertThat(result.probes).containsExactly(api, kds).inOrder()
        assertThat(result.errors).containsExactly(
            "probes:empty_id", "probes:empty_id", "probes:empty_url:kds", "probes:duplicate_id:api",
        ).inOrder()
    }

    @Test
    fun errorOrderIsIntervalThenAppsThenProbes() {
        val result = validate(interval = 5, apps = listOf(""), probes = listOf(Target("", "u")))
        assertThat(result.errors)
            .containsExactly("telemetryIntervalSec:out_of_range", "watchedApps:empty", "probes:empty_id")
            .inOrder()
        assertThat(result.isValid).isFalse()
    }

    @Test
    fun errorCodesMatchSpec() {
        assertThat(DesiredErrors.INTERVAL_OUT_OF_RANGE).isEqualTo("telemetryIntervalSec:out_of_range")
        assertThat(DesiredErrors.PROBE_EMPTY_ID).isEqualTo("probes:empty_id")
        assertThat(DesiredErrors.probeEmptyUrl("a")).isEqualTo("probes:empty_url:a")
        assertThat(DesiredErrors.probeDuplicateId("a")).isEqualTo("probes:duplicate_id:a")
        assertThat(DesiredErrors.WATCHED_APP_EMPTY).isEqualTo("watchedApps:empty")
        assertThat(DesiredErrors.watchedAppInvalidName("X")).isEqualTo("watchedApps:invalid_name:X")
    }
}
