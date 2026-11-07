package ru.restofleet.agent

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = android.app.Application::class)
class LockTaskActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pkg = "com.android.chrome"

    private fun installChrome() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
            },
        )
    }

    @Test
    fun startsTargetUrlInsideLockTask() {
        installChrome()
        val activity = Robolectric.buildActivity(
            LockTaskActivity::class.java,
            LockTaskActivity.intent(context, pkg, "https://x.test/menu"),
        ).create().get()

        val started = shadowOf(activity).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(started.data.toString()).isEqualTo("https://x.test/menu")
        assertThat(started.`package`).isEqualTo(pkg)
        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun missingPackageFinishesWithoutStartingAnything() {
        val activity = Robolectric.buildActivity(
            LockTaskActivity::class.java,
            LockTaskActivity.intent(context, pkg, "https://x.test/"),
        ).create().get()

        assertThat(shadowOf(activity).nextStartedActivity).isNull()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun intentWithoutPackageFinishes() {
        val activity = Robolectric.buildActivity(LockTaskActivity::class.java, Intent()).create().get()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun newIntentRestartsTheTarget() {
        installChrome()
        val controller = Robolectric.buildActivity(
            LockTaskActivity::class.java,
            LockTaskActivity.intent(context, pkg, "https://x.test/one"),
        ).create()
        shadowOf(controller.get()).nextStartedActivity
        controller.newIntent(LockTaskActivity.intent(context, pkg, "https://x.test/two"))

        assertThat(shadowOf(controller.get()).nextStartedActivity.data.toString()).isEqualTo("https://x.test/two")
    }
}
