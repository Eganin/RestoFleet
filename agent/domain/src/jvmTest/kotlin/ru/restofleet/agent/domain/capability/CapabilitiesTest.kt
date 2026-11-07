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
            Command.InstallApk("https://apk-host/a.apk", sha),
            Command.LockTask("ru.restofleet.kiosk", null),
        )
        assertChecks(normal, commands.map { it to "requires_device_owner" })
        assertChecks(owner, commands.map { it to null })
    }

    @Test
    fun showMessageNeedsNotificationPermissionFromApi33() {
        val message = Command.ShowMessage("Перезагрузите роутер", Level.WARNING)
        val granted = Capabilities(AgentMode.NORMAL, 33, setOf(Permission.POST_NOTIFICATIONS))

        assertChecks(Capabilities(AgentMode.NORMAL, 33, emptySet()), listOf(message to "notifications_denied"))
        assertChecks(Capabilities(AgentMode.NORMAL, 36, emptySet()), listOf(message to "notifications_denied"))
        assertChecks(Capabilities(AgentMode.NORMAL, 31, emptySet()), listOf(message to null))
        assertChecks(Capabilities(AgentMode.NORMAL, 32, emptySet()), listOf(message to null))
        assertChecks(granted, listOf(message to null))
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
    fun launchViaNotificationNeedsNotificationPermissionFromApi33() {
        val launch = Command.LaunchApp("ru.restofleet.kiosk", null)

        assertChecks(Capabilities(AgentMode.NORMAL, 34, emptySet()), listOf(launch to "notifications_denied"))
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
            "https://user@kitchen/", "https://ki tchen/",
        )
        val valid = listOf(
            "https://kitchen.local/station", "HTTPS://Kitchen.local", "https://10.0.0.5:8443/a?b=1#c",
            "https://kitchen", "https://kitchen?x=1", "https://kitchen#x",
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
                Command.InstallApk("http://x", "zz") to "requires_device_owner",
            ),
        )
    }

    @Test
    fun installApkNeedsHttpsUrlAndSha256() {
        val upper = "ABCDEF0123456789".repeat(4)
        assertChecks(
            owner,
            listOf(
                Command.InstallApk("https://apk-host/a.apk", sha) to null,
                Command.InstallApk("https://apk-host/a.apk", upper) to null,
                Command.InstallApk("http://apk-host/a.apk", sha) to "invalid_url",
                Command.InstallApk("file:///a.apk", sha) to "invalid_url",
                Command.InstallApk("https://", sha) to "invalid_url",
                Command.InstallApk("https://apk-host/a.apk", "a".repeat(63)) to "invalid_sha256",
                Command.InstallApk("https://apk-host/a.apk", "a".repeat(65)) to "invalid_sha256",
                Command.InstallApk("https://apk-host/a.apk", "g".repeat(64)) to "invalid_sha256",
                Command.InstallApk("https://apk-host/a.apk", "") to "invalid_sha256",
                Command.InstallApk("https://apk-host/a.apk", "a".repeat(63) + "\n") to "invalid_sha256",
                // url проверяется раньше sha256
                Command.InstallApk("http://apk-host/a.apk", "zz") to "invalid_url",
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
}
