package ru.restofleet.agent.data.deviceadmin

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import ru.restofleet.shared.kernel.Outcome

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockTaskLauncherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pkg = "com.android.chrome"
    private val started = mutableListOf<Pair<Intent, Bundle?>>()
    private val legacy = LegacyLockTaskEntry { p, u ->
        Intent("legacy").setPackage(context.packageName).putExtra("pkg", p).putExtra("url", u)
    }

    private fun launcher(api: Int, starter: (Intent, Bundle?) -> Unit = { i, b -> started += i to b }) =
        LockTaskLauncher(context, legacy, { api }, starter)

    private fun install(withLauncher: Boolean) {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        })
        if (withLauncher) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
            val resolve = ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = pkg
                    name = "$pkg.Main"
                    applicationInfo = ApplicationInfo().apply { packageName = pkg }
                }
            }
            pm.addResolveInfoForIntent(query, resolve)
            pm.addOrUpdateActivity(resolve.activityInfo)
        }
    }

    @Before
    fun setUp() = started.clear()

    @Test
    fun notInstalledPackageIsUnsupported() {
        assertThat(launcher(30).target(pkg, null))
            .isEqualTo(Outcome.Failure(DeviceAdminError.Unsupported("package_not_installed")))
        assertThat(launcher(30).target(pkg, "https://x.test/"))
            .isEqualTo(Outcome.Failure(DeviceAdminError.Unsupported("package_not_installed")))
    }

    @Test
    fun installedWithoutLauncherActivityAndNoUrlHasNoLaunchIntent() {
        install(withLauncher = false)
        assertThat(launcher(30).target(pkg, null))
            .isEqualTo(Outcome.Failure(DeviceAdminError.Unsupported("no_launch_intent")))
    }

    @Test
    fun launchIntentIsUsedWithoutUrl() {
        install(withLauncher = true)
        val intent = (launcher(30).target(pkg, null) as Outcome.Success).value
        assertThat(intent.component).isEqualTo(ComponentName(pkg, "$pkg.Main"))
    }

    @Test
    fun urlIsOpenedByExactlyThatPackage() {
        install(withLauncher = false)
        val intent = (launcher(30).target(pkg, "https://x.test/menu") as Outcome.Success).value
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data.toString()).isEqualTo("https://x.test/menu")
        assertThat(intent.`package`).isEqualTo(pkg)
    }

    @Test
    fun api28StartsTargetInLockTaskModeViaActivityOptions() {
        val target = Intent(Intent.ACTION_VIEW).setPackage(pkg)
        val result = launcher(28).start(target, pkg, null)
        assertThat(result).isEqualTo(Outcome.Success(Unit))
        val (intent, options) = started.single()
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        val expected = ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle()
        assertThat(options!!.keySet()).isEqualTo(expected.keySet())
        for (key in expected.keySet()) {
            assertThat(options.get(key)).isEqualTo(expected.get(key))
        }
        val plain = ActivityOptions.makeBasic().toBundle()
        assertThat(options.keySet() - plain.keySet()).isNotEmpty() // lock task действительно включён
    }

    @Test
    fun api26And27GoThroughTheAppEntryWithoutOptions() {
        val target = Intent(Intent.ACTION_VIEW).setPackage(pkg)
        for (api in listOf(26, 27)) {
            started.clear()
            assertThat(launcher(api).start(target, pkg, "https://x.test/")).isEqualTo(Outcome.Success(Unit))
            val (intent, options) = started.single()
            assertThat(intent.action).isEqualTo("legacy")
            assertThat(intent.getStringExtra("pkg")).isEqualTo(pkg)
            assertThat(intent.getStringExtra("url")).isEqualTo("https://x.test/")
            assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
            assertThat(options).isNull()
        }
    }

    @Test
    fun securityExceptionMeansLockTaskIsNotPermitted() {
        val result = launcher(30) { _, _ -> throw SecurityException("not whitelisted") }
            .start(Intent(Intent.ACTION_VIEW), pkg, null)
        assertThat(result).isEqualTo(Outcome.Failure(DeviceAdminError.Unsupported("lock_task_not_permitted")))
    }

    @Test
    fun missingActivityIsReported() {
        val result = launcher(30) { _, _ -> throw ActivityNotFoundException("none") }
            .start(Intent(Intent.ACTION_VIEW), pkg, null)
        assertThat(result).isEqualTo(Outcome.Failure(DeviceAdminError.Unsupported("activity_not_found")))
    }

    @Test
    fun defaultStarterStartsAnActivityOfTheContext() {
        val intent = Intent(context, android.app.Activity::class.java)
        LockTaskLauncher(context, legacy, { 30 }).start(intent, pkg, null)
        assertThat(shadowOf(context as android.app.Application).nextStartedActivity.component)
            .isEqualTo(intent.component)
    }
}
