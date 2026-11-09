package ru.restofleet.agent.domain.capability

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import ru.restofleet.agent.domain.command.Command
import ru.restofleet.agent.domain.command.Level

class CapabilitiesTest {

    private val normal = Capabilities(AgentMode.NORMAL, 34, emptySet())
    private val owner = Capabilities(AgentMode.DEVICE_OWNER, 34, emptySet())
    private val sha = "a".repeat(64)
    private val waiter = "ru.restofleet.dummy.waiter"

    private fun assertChecks(caps: Capabilities, cases: List<Pair<Command, String?>>) {
        cases.forEach { (command, expected) ->
            assertWithMessage("$command в $caps").that(caps.check(command)).isEqualTo(expected)
        }
    }

    @Test
    fun pingIsAlwaysAllowed() {
        listOf(normal, owner, Capabilities(AgentMode.NORMAL, 21, emptySet())).forEach {
            assertWithMessage("ping в $it").that(it.check(Command.Ping)).isNull()
        }
    }

    @Test
    fun deviceOwnerCommandsRequireDeviceOwner() {
        val commands = listOf(
            Command.Reboot,
            Command.InstallApk(waiter, "https://apk-host/a.apk", sha),
            Command.LockTask("ru.restofleet.kiosk", null),
        )
        assertChecks(normal, commands.map { it to "requires_device_owner" })
        assertChecks(owner, commands.map { it to null })
    }

    @Test
    fun showMessageDoesNotGateOnNotificationPermissionOnAnyApi() {
        // «Уведомления выключены» — одно условие и один код на всех API: failed/notifications_disabled даёт адаптер
        val message = Command.ShowMessage("Перезагрузите роутер", Level.WARNING)

        listOf(26, 31, 32, 33, 36).forEach {
            assertChecks(Capabilities(AgentMode.NORMAL, it, emptySet()), listOf(message to null))
        }
        assertChecks(Capabilities(AgentMode.NORMAL, 33, setOf(Permission.POST_NOTIFICATIONS)), listOf(message to null))
    }

    @Test
    fun kioskRefusalOnlyTouchesShowMessageAndLaunchAppOutsideTheAllowlist() {
        val kiosk = Capabilities(
            AgentMode.DEVICE_OWNER,
            34,
            emptySet(),
            kioskActive = true,
            kioskPackages = setOf("ru.restofleet.agent", "ru.restofleet.dummy.waiter"),
        )

        val cases = listOf(
            Command.ShowMessage("x", Level.INFO) to "kiosk_active",
            Command.LaunchApp("com.android.chrome", null) to "kiosk_active",
            Command.LaunchApp("ru.restofleet.dummy.waiter", null) to null,
            Command.LaunchApp("ru.restofleet.agent", "https://kitchen/s") to null,
            Command.Ping to null,
            Command.Beep(100) to null,
            Command.Reboot to null,
            Command.InstallApk(waiter, "https://apk-host/a.apk", sha) to null,
            Command.LockTask("com.android.chrome", null) to null,
        )
        cases.forEach { (command, expected) ->
            assertWithMessage("$command").that(kiosk.kioskRefusal(command)).isEqualTo(expected)
        }
    }

    @Test
    fun noKioskMeansNoRefusalEvenWithAnAllowlist() {
        val caps = Capabilities(AgentMode.DEVICE_OWNER, 34, emptySet(), false, setOf("a.b"))

        assertThat(caps.kioskRefusal(Command.ShowMessage("x", Level.INFO))).isNull()
        assertThat(caps.kioskRefusal(Command.LaunchApp("c.d", null))).isNull()
    }

    @Test
    fun showMessageTextMustNotBeBlank() {
        val caps = Capabilities(AgentMode.NORMAL, 31, emptySet())
        assertChecks(
            caps,
            listOf(
                Command.ShowMessage("", Level.INFO) to "empty_text",
                Command.ShowMessage("  \n\t", Level.INFO) to "empty_text",
                Command.ShowMessage(" x ", Level.INFO) to null,
            ),
        )
        // пустой текст проверяется раньше разрешения
        assertChecks(
            Capabilities(AgentMode.NORMAL, 34, emptySet()),
            listOf(Command.ShowMessage(" ", Level.INFO) to "empty_text"),
        )
    }

    @Test
    fun launchStrategyDependsOnModeAndOverlay() {
        val overlay = setOf(Permission.SYSTEM_ALERT_WINDOW)
        assertThat(Capabilities(AgentMode.DEVICE_OWNER, 34, emptySet()).launchStrategy())
            .isEqualTo(LaunchStrategy.DIRECT_DO)
        assertThat(Capabilities(AgentMode.DEVICE_OWNER, 34, overlay).launchStrategy())
            .isEqualTo(LaunchStrategy.DIRECT_DO)
        assertThat(Capabilities(AgentMode.NORMAL, 34, overlay).launchStrategy())
            .isEqualTo(LaunchStrategy.DIRECT_OVERLAY)
        assertThat(Capabilities(AgentMode.NORMAL, 34, emptySet()).launchStrategy())
            .isEqualTo(LaunchStrategy.VIA_NOTIFICATION)
        assertThat(Capabilities(AgentMode.NORMAL, 34, setOf(Permission.POST_NOTIFICATIONS)).launchStrategy())
            .isEqualTo(LaunchStrategy.VIA_NOTIFICATION)
    }

    @Test
    fun launchViaNotificationDoesNotGateOnNotificationPermissionOnAnyApi() {
        val launch = Command.LaunchApp("ru.restofleet.kiosk", null)

        assertChecks(Capabilities(AgentMode.NORMAL, 34, emptySet()), listOf(launch to null))
        assertChecks(Capabilities(AgentMode.NORMAL, 31, emptySet()), listOf(launch to null))
        assertChecks(
            Capabilities(AgentMode.NORMAL, 34, setOf(Permission.POST_NOTIFICATIONS)),
            listOf(launch to null),
        )
        // прямые стратегии уведомления не требуют
        assertChecks(
            Capabilities(AgentMode.NORMAL, 34, setOf(Permission.SYSTEM_ALERT_WINDOW)),
            listOf(launch to null),
        )
        assertChecks(owner, listOf(launch to null))
    }

    @Test
    fun packageNameMustBeValidForLaunchAndLockTask() {
        val invalid = listOf(
            "", "kiosk", "ru..kiosk", "ru.kiosk.", ".ru.kiosk", "1ru.kiosk", "ru.1kiosk", "ru.kiosk app",
            "ru.kiosk;rm", "ru.kiosk-x", "ru/kiosk", "ru.kiosk\n",
        )
        val valid = listOf("ru.restofleet.kiosk", "a.b", "Com.Example_x.App2")
        val launchCaps = Capabilities(AgentMode.DEVICE_OWNER, 34, emptySet())

        assertChecks(launchCaps, invalid.map { Command.LaunchApp(it, null) to "invalid_package" })
        assertChecks(launchCaps, invalid.map { Command.LockTask(it, null) to "invalid_package" })
        assertChecks(launchCaps, valid.map { Command.LaunchApp(it, null) to null })
        assertChecks(launchCaps, valid.map { Command.LockTask(it, null) to null })
    }

    @Test
    fun urlMustBeHttpsWithHostForLaunchAndLockTask() {
        val invalid = listOf(
            "", "http://kitchen/", "intent://x#Intent;end", "file:///sdcard/a", "javascript:alert(1)",
            "content://x/y", "https://", "https:///path", "https://:443/", "https://?q=1", "https://#f",
            "https://kitchen/a b", "https:/kitchen", "kitchen.local", " https://kitchen/",
            "https://user@kitchen/", "https://ki tchen/", "HTTPS://kitchen/", "Https://kitchen/",
            "https://user:pw@evil/", "https://kitchen:443@evil/", "https://a:b@evil/", "https://kitchen:@evil",
            "https://kitchen:abc/", "https://host:abc", "https://kitchen:70000/", "https://kitchen:65536",
            "https://kitchen:99999999", "https://kitchen:0", "https://kitchen:/x", "https://kitchen:80:90/",
            "https://kitchen:80x", "https://kitchen:80@evil", "https://.", "https://..", "https://-",
            "https://a..b", "https://-a.b", "https://a-.b", "https://a.b.", "https://.a", "https://_a",
            "https://" + "a".repeat(2048),
        )
        val valid = listOf(
            "https://kitchen.local/station", "https://Kitchen.local", "https://10.0.0.5:8443/a?b=1#c",
            "https://kitchen", "https://kitchen?x=1", "https://kitchen#x", "https://kitchen:65535",
            "https://kitchen:1/x", "https://kitchen:00080?q", "https://xn--80a-b.local/", "https://a-b.c-d.e1",
            "https://10.0.2.2:8443", "https://kitchen:8443#f",
            "https://a.b/" + "x".repeat(2048 - "https://a.b/".length),
        )
        val owner = Capabilities(AgentMode.DEVICE_OWNER, 34, emptySet())
        val pkg = "ru.restofleet.kiosk"

        assertChecks(owner, invalid.map { Command.LaunchApp(pkg, it) to "invalid_url" })
        assertChecks(owner, invalid.map { Command.LockTask(pkg, it) to "invalid_url" })
        assertChecks(owner, valid.map { Command.LaunchApp(pkg, it) to null })
        assertChecks(owner, valid.map { Command.LockTask(pkg, it) to null })
    }

    @Test
    fun packageIsCheckedBeforeUrlAndModeBeforeFormat() {
        assertChecks(owner, listOf(Command.LaunchApp("bad", "http://x") to "invalid_package"))
        assertChecks(
            normal,
            listOf(
                Command.LockTask("bad", "http://x") to "requires_device_owner",
                Command.Reboot to "requires_device_owner",
                Command.InstallApk(waiter, "http://x", "zz") to "requires_device_owner",
            ),
        )
    }

    @Test
    fun installApkNeedsPackageHttpsUrlAndSha256() {
        val upper = "ABCDEF0123456789".repeat(4)
        assertChecks(
            owner,
            listOf(
                Command.InstallApk(waiter, "https://apk-host/a.apk", sha) to null,
                Command.InstallApk(waiter, "https://apk-host/a.apk", upper) to null,
                Command.InstallApk(waiter, "http://apk-host/a.apk", sha) to "invalid_url",
                Command.InstallApk(waiter, "file:///a.apk", sha) to "invalid_url",
                Command.InstallApk(waiter, "https://", sha) to "invalid_url",
                Command.InstallApk(waiter, "https://apk-host/a.apk", "a".repeat(63)) to "invalid_sha256",
                Command.InstallApk(waiter, "https://apk-host/a.apk", "a".repeat(65)) to "invalid_sha256",
                Command.InstallApk(waiter, "https://apk-host/a.apk", "g".repeat(64)) to "invalid_sha256",
                Command.InstallApk(waiter, "https://apk-host/a.apk", "") to "invalid_sha256",
                Command.InstallApk(waiter, "https://apk-host/a.apk", "a".repeat(63) + "\n") to "invalid_sha256",
                // url проверяется раньше sha256
                Command.InstallApk(waiter, "http://apk-host/a.apk", "zz") to "invalid_url",
                // пакет проверяется раньше url и sha256
                Command.InstallApk("bad", "http://apk-host/a.apk", "zz") to "invalid_package",
                Command.InstallApk("", "https://apk-host/a.apk", sha) to "invalid_package",
                Command.InstallApk("a.b/c", "https://apk-host/a.apk", sha) to "invalid_package",
                Command.InstallApk("1a.b", "https://apk-host/a.apk", sha) to "invalid_package",
                Command.InstallApk("a." + "b".repeat(254), "https://apk-host/a.apk", sha) to "invalid_package",
                Command.InstallApk("com.example.other", "https://apk-host/a.apk", sha) to null,
            ),
        )
    }

    @Test
    fun beepDurationMustBeWithinLimits() {
        assertChecks(
            normal,
            listOf(
                Command.Beep(1) to null,
                Command.Beep(30_000) to null,
                Command.Beep(0) to "invalid_duration",
                Command.Beep(-5) to "invalid_duration",
                Command.Beep(30_001) to "invalid_duration",
                Command.Beep(Long.MAX_VALUE) to "invalid_duration",
            ),
        )
    }

    @Test
    fun lengthLimitsAreEnforced() {
        val caps = Capabilities(AgentMode.DEVICE_OWNER, 34, setOf(Permission.POST_NOTIFICATIONS))
        val pkg255 = "a." + "b".repeat(253)
        val longPath = "x".repeat(2048 - "https://a.b/".length + 1)

        assertChecks(
            caps,
            listOf(
                Command.LaunchApp(pkg255, null) to null,
                Command.LaunchApp(pkg255 + "b", null) to "invalid_package",
                Command.LockTask(pkg255 + "b", null) to "invalid_package",
                Command.LaunchApp("a.b", "https://a.b/$longPath") to "invalid_url",
                Command.InstallApk(waiter, "https://a.b/$longPath", sha) to "invalid_url",
                Command.ShowMessage("x".repeat(1000), Level.INFO) to null,
                Command.ShowMessage("x".repeat(1001), Level.INFO) to "text_too_long",
                Command.ShowMessage(" ".repeat(1001), Level.INFO) to "empty_text",
            ),
        )
        // длина текста проверяется раньше разрешения на уведомления
        assertChecks(
            Capabilities(AgentMode.NORMAL, 34, emptySet()),
            listOf(Command.ShowMessage("x".repeat(1001), Level.INFO) to "text_too_long"),
        )
    }
}
