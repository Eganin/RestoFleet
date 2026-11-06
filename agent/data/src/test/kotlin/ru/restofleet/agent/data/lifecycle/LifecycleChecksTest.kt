package ru.restofleet.agent.data.lifecycle

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.shared.kernel.Clock
import java.io.File

class LifecycleChecksTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 0L
    private val clock = Clock { now }
    private var scheduled = true
    private var notifications = true
    private var usage = true
    private var battery = true

    private val heartbeat by lazy { HeartbeatLog(File(tmp.root, "hb"), clock, 100_000) }
    private val journal by lazy { LifecycleJournal(File(tmp.root, "j"), clock) }

    private val permissions = object : WizardPermissions {
        override fun notificationsEnabled() = notifications
        override fun usageAccessGranted() = usage
        override fun ignoringBatteryOptimizations() = battery
    }

    private suspend fun run(id: CheckId): CheckResult =
        LifecycleChecks(clock, heartbeat, journal, WatchdogSchedule { scheduled }, permissions)
            .create().single { it.id == id }.run()

    private fun beats(from: Long, count: Int, step: Long = 30_000) = (0 until count).forEach {
        now = from + it * step
        heartbeat.append()
    }

    @Test
    fun createsExactlyL1ToL4() {
        val checks = LifecycleChecks(clock, heartbeat, journal, WatchdogSchedule { true }, permissions).create()
        val ids = checks.map { it.id }

        assertThat(ids).containsExactly(CheckId.L1, CheckId.L2, CheckId.L3, CheckId.L4).inOrder()
    }

    // ---- L1

    @Test
    fun l1OkWhenNoGapsAndCoverageAtLeast12Hours() = runTest {
        beats(from = 0, count = 1_441) // ровно 12 ч шагом 30 с
        now = 99
        val r = run(CheckId.L1)

        assertThat(r.status).isEqualTo(CheckStatus.OK)
        assertThat(r.raw["gaps"]).isEqualTo("0")
        assertThat(r.raw["coverageMs"]).isEqualTo("43200000")
        assertThat(r.checkedAt).isEqualTo(99L)
    }

    @Test
    fun l1LimitedWhenGapExceeds120Seconds() = runTest {
        beats(0, 10)
        beats(270_000 + 120_001, 10)
        val r = run(CheckId.L1)

        assertThat(r.status).isEqualTo(CheckStatus.LIMITED)
        assertThat(r.raw["gaps"]).isEqualTo("1")
        assertThat(r.raw["maxGapMs"]).isEqualTo("120001")
    }

    @Test
    fun l1GapExactly120SecondsIsNotAGap() = runTest {
        beats(0, 1_441, step = 120_000 / 4) // шаг 30 с, затем один разрыв ровно 120 с
        now = 1_440 * 30_000L + 120_000
        heartbeat.append()
        val r = run(CheckId.L1)

        assertThat(r.raw["gaps"]).isEqualTo("0")
        assertThat(r.status).isEqualTo(CheckStatus.OK)
    }

    @Test
    fun l1NotCheckedWhenCoverageShorterThan12Hours() = runTest {
        beats(0, 100)
        val r = run(CheckId.L1)

        assertThat(r.status).isEqualTo(CheckStatus.NOT_CHECKED)
        assertThat(r.note).contains("12")
        assertThat(r.raw["coverageMs"]).isEqualTo("2970000")
    }

    @Test
    fun l1NotCheckedWithoutAnyHeartbeat() = runTest {
        val r = run(CheckId.L1)

        assertThat(r.status).isEqualTo(CheckStatus.NOT_CHECKED)
        assertThat(r.raw["samples"]).isEqualTo("0")
    }

    // ---- L2

    @Test
    fun l2NotCheckedWhenBootNotRecorded() = runTest {
        val r = run(CheckId.L2)

        assertThat(r.status).isEqualTo(CheckStatus.NOT_CHECKED)
    }

    @Test
    fun l2OkWithDelayToFirstHeartbeatAfterBoot() = runTest {
        now = 1_000
        heartbeat.append() // до перезагрузки — не считается
        now = 10_000
        journal.recordBoot()
        now = 25_000
        heartbeat.append()
        now = 55_000
        heartbeat.append()
        val r = run(CheckId.L2)

        assertThat(r.status).isEqualTo(CheckStatus.OK)
        assertThat(r.raw["bootAt"]).isEqualTo("10000")
        assertThat(r.raw["firstHeartbeatAt"]).isEqualTo("25000")
        assertThat(r.raw["delayMs"]).isEqualTo("15000")
    }

    @Test
    fun l2UnavailableWhenNoHeartbeatLongAfterBoot() = runTest {
        now = 1_000
        journal.recordBoot()
        now = 1_000 + 120_000
        val r = run(CheckId.L2)

        assertThat(r.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(r.raw["bootAt"]).isEqualTo("1000")
    }

    @Test
    fun l2NotCheckedWhileWaitingForFirstHeartbeat() = runTest {
        now = 1_000
        journal.recordBoot()
        now = 1_000 + 119_999
        val r = run(CheckId.L2)

        assertThat(r.status).isEqualTo(CheckStatus.NOT_CHECKED)
    }

    // ---- L3

    @Test
    fun l3UnavailableWhenWatchdogNotScheduled() = runTest {
        scheduled = false
        val r = run(CheckId.L3)

        assertThat(r.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(r.raw["scheduled"]).isEqualTo("false")
    }

    @Test
    fun l3OkWhenScheduledEvenIfNeverRestarted() = runTest {
        val r = run(CheckId.L3)

        assertThat(r.status).isEqualTo(CheckStatus.OK)
        assertThat(r.raw["lastWatchdogStartAt"]).isEqualTo("none")
        assertThat(r.raw["lastWatchdogDeniedAt"]).isEqualTo("none")
    }

    @Test
    fun l3OkWithLastWatchdogRestartTime() = runTest {
        now = 700
        journal.recordStart("watchdog")
        now = 800
        journal.recordStart("manual")
        val r = run(CheckId.L3)

        assertThat(r.status).isEqualTo(CheckStatus.OK)
        assertThat(r.raw["lastWatchdogStartAt"]).isEqualTo("700")
    }

    @Test
    fun l3LimitedWhenLastWatchdogLaunchWasDenied() = runTest {
        now = 700
        journal.recordStart("watchdog")
        now = 900
        journal.recordStartDenied("watchdog")
        val r = run(CheckId.L3)

        assertThat(r.status).isEqualTo(CheckStatus.LIMITED)
        assertThat(r.raw["lastWatchdogStartAt"]).isEqualTo("700")
        assertThat(r.raw["lastWatchdogDeniedAt"]).isEqualTo("900")
    }

    @Test
    fun l3OkWhenRestartFollowedEarlierDenial() = runTest {
        now = 500
        journal.recordStartDenied("watchdog")
        now = 900
        journal.recordStart("watchdog")
        val r = run(CheckId.L3)

        assertThat(r.status).isEqualTo(CheckStatus.OK)
        assertThat(r.raw["lastWatchdogDeniedAt"]).isEqualTo("500")
    }

    // ---- L4

    @Test
    fun l4OkWhenAllThreeFlagsTrue() = runTest {
        val r = run(CheckId.L4)

        assertThat(r.status).isEqualTo(CheckStatus.OK)
        assertThat(r.raw).containsExactly(
            "notifications", "true",
            "usageAccess", "true",
            "ignoreBatteryOptimizations", "true",
        )
    }

    @Test
    fun l4LimitedListsMissingItems() = runTest {
        notifications = false
        battery = false
        val r = run(CheckId.L4)

        assertThat(r.status).isEqualTo(CheckStatus.LIMITED)
        assertThat(r.raw["notifications"]).isEqualTo("false")
        assertThat(r.raw["usageAccess"]).isEqualTo("true")
        assertThat(r.note).contains("notifications")
        assertThat(r.note).contains("ignoreBatteryOptimizations")
        assertThat(r.note).doesNotContain("usageAccess")
    }

    @Test
    fun l4LimitedWhenOnlyUsageAccessMissing() = runTest {
        usage = false
        val r = run(CheckId.L4)

        assertThat(r.status).isEqualTo(CheckStatus.LIMITED)
        assertThat(r.note).isEqualTo("missing: usageAccess")
    }
}
