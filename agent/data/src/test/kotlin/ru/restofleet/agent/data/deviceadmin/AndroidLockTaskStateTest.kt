package ru.restofleet.agent.data.deviceadmin

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidLockTaskStateTest {
    private val dpm = mockk<DevicePolicyManager>()
    private val admin = ComponentName("ru.restofleet.agent", "ru.restofleet.agent.AdminReceiver")
    private var owner = true
    private var modeState = ActivityManager.LOCK_TASK_MODE_NONE

    private fun sut() = AndroidLockTaskState(dpm, admin, { owner }, { modeState })

    @Test
    fun anyLockTaskModeOtherThanNoneIsActive() {
        assertThat(sut().isActive()).isFalse()
        modeState = ActivityManager.LOCK_TASK_MODE_LOCKED
        assertThat(sut().isActive()).isTrue()
        modeState = ActivityManager.LOCK_TASK_MODE_PINNED
        assertThat(sut().isActive()).isTrue()
    }

    @Test
    fun deviceOwnerReadsTheDpmAllowlist() {
        every { dpm.getLockTaskPackages(admin) } returns arrayOf("ru.restofleet.agent", "ru.restofleet.dummy.waiter")

        assertThat(sut().allowedPackages()).containsExactly("ru.restofleet.agent", "ru.restofleet.dummy.waiter")
    }

    @Test
    fun withoutTheRoleTheDpmIsNotAskedAndTheAllowlistIsEmpty() {
        owner = false

        assertThat(sut().allowedPackages()).isEmpty()
        verify(exactly = 0) { dpm.getLockTaskPackages(any()) }
    }

    @Test
    fun losingTheRoleBetweenTheCheckAndTheCallGivesAnEmptyAllowlist() {
        every { dpm.getLockTaskPackages(admin) } throws SecurityException("not owner")

        assertThat(sut().allowedPackages()).isEmpty()
    }
}
