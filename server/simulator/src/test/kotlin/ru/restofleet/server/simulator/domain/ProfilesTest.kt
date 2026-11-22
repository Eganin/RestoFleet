package ru.restofleet.server.simulator.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.server.simulator.domain.model.DeviceRole
import ru.restofleet.server.simulator.domain.profile.AssemblyProfile
import ru.restofleet.server.simulator.domain.profile.DayClock
import ru.restofleet.server.simulator.domain.profile.DeviceProfile
import ru.restofleet.server.simulator.domain.profile.HallProfile
import ru.restofleet.server.simulator.domain.profile.KitchenProfile
import ru.restofleet.server.simulator.domain.profile.ProfileState
import ru.restofleet.server.simulator.domain.profile.Thermal
import ru.restofleet.server.simulator.domain.profile.profileOf
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** FR-SM-03: профили — чистые генераторы, детерминированные от seed, с динамикой своей роли. */
class ProfilesTest {
    /** Прогон профиля: [steps] шагов по [dtSec] начиная с часа [fromHour]. */
    private fun simulate(
        profile: DeviceProfile,
        seed: Long,
        fromHour: Double,
        steps: Int,
        dtSec: Double,
    ): List<ProfileState> {
        val random = Random(seed)
        var state = profile.initial(random)
        return (0 until steps).map { step ->
            val hour = (fromHour + step * dtSec / 3_600.0) % 24.0
            state = profile.next(state, hour, dtSec, random)
            state
        }
    }

    @Test
    fun sameSeedGivesSameSequenceAndOtherSeedDiffers() {
        listOf(KitchenProfile, HallProfile, AssemblyProfile).forEach { profile ->
            val a = simulate(profile, seed = 7, fromHour = 9.0, steps = 50, dtSec = 30.0)
            val b = simulate(profile, seed = 7, fromHour = 9.0, steps = 50, dtSec = 30.0)
            val c = simulate(profile, seed = 8, fromHour = 9.0, steps = 50, dtSec = 30.0)
            assertThat(a).isEqualTo(b)
            assertThat(a).isNotEqualTo(c)
        }
    }

    @Test
    fun kitchenIsAlwaysChargingAndHeatsUpAtPeakHours() {
        val offPeak = simulate(KitchenProfile, seed = 1, fromHour = 9.0, steps = 120, dtSec = 30.0)
        val peak = simulate(KitchenProfile, seed = 1, fromHour = 12.0, steps = 120, dtSec = 30.0)
        assertThat((offPeak + peak).all { it.charging }).isTrue()
        assertThat(KitchenProfile.plugged).isEqualTo("ac")
        assertThat(peak.last().tempC).isGreaterThan(offPeak.last().tempC + 5)
        assertThat(peak.last().tempC).isAtLeast(39.0)
        assertThat(offPeak.last().tempC).isLessThan(33.0)
        assertThat(KitchenProfile.isPeak(12.0)).isTrue()
        assertThat(KitchenProfile.isPeak(15.0)).isFalse()
        assertThat(KitchenProfile.isPeak(19.5)).isTrue()
        assertThat(KitchenProfile.isPeak(11.99)).isFalse()
    }

    @Test
    fun hallDrainsDuringShiftAndChargesAfterIt() {
        val shift = simulate(HallProfile, seed = 2, fromHour = 11.0, steps = 240, dtSec = 30.0)
        assertThat(shift.none { it.charging }).isTrue()
        assertThat(shift.first().batteryPct - shift.last().batteryPct).isWithin(0.5).of(6.0 * 2 - 6.0 * 2 / 240)
        val night = simulate(HallProfile, seed = 2, fromHour = 23.5, steps = 120, dtSec = 30.0)
        assertThat(night.all { it.charging }).isTrue()
        assertThat(night.last().batteryPct).isGreaterThan(night.first().batteryPct)
        assertThat(HallProfile.inShift(10.0)).isTrue()
        assertThat(HallProfile.inShift(23.0)).isFalse()
    }

    @Test
    fun assemblyDocksFifteenMinutesOfEveryFortyFive() {
        assertThat(AssemblyProfile.docked(0.0)).isTrue()
        assertThat(AssemblyProfile.docked(14.9 / 60)).isTrue()
        assertThat(AssemblyProfile.docked(15.0 / 60)).isFalse()
        assertThat(AssemblyProfile.docked(46.0 / 60)).isTrue()
        val day = simulate(AssemblyProfile, seed = 3, fromHour = 8.0, steps = 270, dtSec = 60.0)
        val dockedShare = day.count { it.charging }.toDouble() / day.size
        assertThat(dockedShare).isWithin(0.05).of(1.0 / 3)
    }

    @Test
    fun valuesStayWithinPhysicalBounds() {
        DeviceRole.entries.map(::profileOf).forEach { profile ->
            simulate(profile, seed = 4, fromHour = 0.0, steps = 2_880, dtSec = 60.0).forEach { s ->
                assertThat(s.batteryPct).isIn(com.google.common.collect.Range.closed(1.0, 100.0))
                assertThat(s.rssi).isIn(com.google.common.collect.Range.closed(-85, -40))
                assertThat(s.availMb).isIn(com.google.common.collect.Range.closed(300L, 2_000L))
                assertThat(s.freeMb).isAtLeast(500L)
                assertThat(s.tempC).isIn(com.google.common.collect.Range.closed(20.0, 50.0))
            }
            assertThat(profile.role).isEqualTo(DeviceRole.entries.first { profileOf(it) == profile })
        }
    }

    @Test
    fun thermalCodeFollowsTemperatureBands() {
        assertThat(Thermal.code(30.0)).isEqualTo(0)
        assertThat(Thermal.code(37.0)).isEqualTo(1)
        assertThat(Thermal.code(42.9)).isEqualTo(2)
        assertThat(Thermal.code(43.0)).isEqualTo(3)
        assertThat(Thermal.code(50.0)).isEqualTo(4)
        assertThat(Thermal.name(3)).isEqualTo("SEVERE")
        assertThat(Thermal.name(99)).isEqualTo("SHUTDOWN")
    }

    @Test
    fun dayClockGivesLocalHourAndScalesTime() {
        val zone = ZoneId.of("Europe/Moscow")
        val start = ZonedDateTime.of(2026, 11, 11, 12, 30, 0, 0, zone).toInstant().toEpochMilli()
        val real = DayClock(zone, timeScale = 1.0, startMillis = start)
        assertThat(real.hour(start)).isWithin(1e-9).of(12.5)
        assertThat(real.hour(start + 30 * 60_000)).isWithin(1e-9).of(13.0)
        val fast = DayClock(zone, timeScale = 60.0, startMillis = start)
        assertThat(fast.simulatedMillis(start + 60_000)).isEqualTo(start + 3_600_000)
        assertThat(fast.hour(start + 60_000)).isWithin(1e-9).of(13.5)
    }
}
