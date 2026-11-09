package ru.restofleet.agent.data.mqtt.mapping

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.command.Command
import ru.restofleet.agent.domain.command.Level
import ru.restofleet.agent.domain.model.IncomingCommand
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.errorOrNull
import ru.restofleet.shared.kernel.getOrNull
import ru.restofleet.shared.protocol.dto.CommandRequestDto

class CommandMapperTest {
    private fun parse(type: String, params: String = "{}", extra: String = ""): IncomingCommand =
        CommandMapper.parse(
            """{"cmdId":"c-1","type":"$type","params":$params$extra}""".toByteArray(),
        ).getOrNull()!!

    private fun assertInvalid(c: IncomingCommand, type: String, reasonPart: String) {
        assertThat(c.command).isNull()
        assertThat(c.rawType).isEqualTo(type)
        assertThat(c.parseError).contains(reasonPart)
    }

    @Test
    fun showMessageFixtureMapsToCommandWithMqttProperties() {
        val dto = Fixtures.decode("cmd-req-show-message", CommandRequestDto.serializer())
        val correlation = "c-01J9Z3".toByteArray()

        val c = CommandMapper.fromDto(dto, responseTopic = "rf/v1/dev/x/cmd/res", correlationData = correlation)

        assertThat(c).isEqualTo(
            IncomingCommand(
                cmdId = "c-01J9Z3",
                command = Command.ShowMessage("Перезагрузите роутер", Level.WARNING),
                rawType = "show_message",
                responseTopic = "rf/v1/dev/x/cmd/res",
                correlationData = "c-01J9Z3".toByteArray(),
                expiresAt = 1793866100000,
            ),
        )
    }

    @Test
    fun installUpdateFixtureFixesTheWireContractWithPackage() {
        val dto = Fixtures.decode("cmd-req-install-update", CommandRequestDto.serializer())

        val c = CommandMapper.fromDto(dto)

        assertThat(dto.type).isEqualTo("install_update")
        assertThat(dto.params.keys).containsExactly("package", "url", "sha256")
        assertThat(c.command).isEqualTo(
            Command.InstallApk(
                "ru.restofleet.dummy.waiter",
                "https://10.0.2.2:8443/waiter-4.14.apk",
                "3f2b6c1a9d8e7f60a1b2c3d4e5f60718293a4b5c6d7e8f9012a3b4c5d6e7f809",
            ),
        )
    }

    @Test
    fun installUpdateFixtureWithoutPackageIsAParseError() {
        val text = Fixtures.text("cmd-req-install-update").replace(Regex(""""package"\s*:\s*"[^"]*",?"""), "")

        val c = CommandMapper.parse(text.toByteArray()).getOrNull()!!

        assertInvalid(c, "install_update", "package")
        assertThat(c.command).isNull()
    }

    @Test
    fun unknownTypeKeepsRawTypeWithoutCommandAndWithoutParseError() {
        val c = parse("self_destruct", """{"x": 1}""")

        assertThat(c.command).isNull()
        assertThat(c.rawType).isEqualTo("self_destruct")
        assertThat(c.parseError).isNull()
        assertThat(c.cmdId).isEqualTo("c-1")
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val c = parse("ping", extra = ""","future":true""")

        assertThat(c.command).isEqualTo(Command.Ping)
    }

    @Test
    fun missingParamsMeansEmptyParams() {
        val c = CommandMapper.parse("""{"cmdId":"c-1","type":"reboot"}""".toByteArray()).getOrNull()!!

        assertThat(c.command).isEqualTo(Command.Reboot)
    }

    @Test
    fun pingAndRebootNeedNoParams() {
        assertThat(parse("ping").command).isEqualTo(Command.Ping)
        assertThat(parse("reboot").command).isEqualTo(Command.Reboot)
    }

    @Test
    fun showMessageValidLevels() {
        val levels = listOf("info", "warning", "critical").map {
            parse("show_message", """{"text":"t","level":"$it"}""").command
        }

        assertThat(levels).containsExactly(
            Command.ShowMessage("t", Level.INFO),
            Command.ShowMessage("t", Level.WARNING),
            Command.ShowMessage("t", Level.CRITICAL),
        ).inOrder()
    }

    @Test
    fun showMessageWithoutLevelIsInfo() {
        assertThat(parse("show_message", """{"text":"t"}""").command).isEqualTo(Command.ShowMessage("t", Level.INFO))
    }

    @Test
    fun showMessageInvalid() {
        assertInvalid(parse("show_message", """{"level":"info"}"""), "show_message", "text")
        assertInvalid(parse("show_message", """{"text":"  "}"""), "show_message", "text")
        assertInvalid(parse("show_message", """{"text":5}"""), "show_message", "text")
        assertInvalid(parse("show_message", """{"text":"t","level":"panic"}"""), "show_message", "level")
        assertInvalid(parse("show_message", """{"text":"t","level":3}"""), "show_message", "level")
    }

    @Test
    fun launchAppValidWithAndWithoutUrl() {
        assertThat(parse("launch_app", """{"pkg":"a.b"}""").command).isEqualTo(Command.LaunchApp("a.b", null))
        assertThat(parse("launch_app", """{"pkg":"a.b","url":"https://k"}""").command)
            .isEqualTo(Command.LaunchApp("a.b", "https://k"))
    }

    @Test
    fun launchAppInvalid() {
        assertInvalid(parse("launch_app"), "launch_app", "pkg")
        assertInvalid(parse("launch_app", """{"pkg":""}"""), "launch_app", "pkg")
        assertInvalid(parse("launch_app", """{"pkg":"a.b","url":7}"""), "launch_app", "url")
    }

    @Test
    fun beepValidAndInvalid() {
        assertThat(parse("beep", """{"durationMs":1500}""").command).isEqualTo(Command.Beep(1500))
        assertInvalid(parse("beep"), "beep", "durationMs")
        assertInvalid(parse("beep", """{"durationMs":"1500"}"""), "beep", "durationMs")
        assertInvalid(parse("beep", """{"durationMs":0}"""), "beep", "durationMs")
        assertInvalid(parse("beep", """{"durationMs":-5}"""), "beep", "durationMs")
        assertInvalid(parse("beep", """{"durationMs":1.5}"""), "beep", "durationMs")
    }

    @Test
    fun installUpdateValidAndInvalid() {
        assertThat(parse("install_update", """{"package":"a.b","url":"https://h/a.apk","sha256":"ab12"}""").command)
            .isEqualTo(Command.InstallApk("a.b", "https://h/a.apk", "ab12"))
        assertInvalid(parse("install_update", """{"package":"a.b","sha256":"ab12"}"""), "install_update", "url")
        assertInvalid(parse("install_update", """{"package":"a.b","url":"https://h/a"}"""), "install_update", "sha256")
        assertInvalid(parse("install_update", """{"package":"a.b","url":"u","sha256":1}"""), "install_update", "sha256")
    }

    @Test
    fun installUpdateWithoutAPackageIsAParseErrorNotAnInstallOfAnything() {
        val withoutPackage = parse("install_update", """{"url":"https://h/a.apk","sha256":"ab12"}""")
        assertInvalid(withoutPackage, "install_update", "package")
        assertThat(withoutPackage.command).isNull()
        val empty = parse("install_update", """{"package":"","url":"https://h/a","sha256":"ab"}""")
        assertInvalid(empty, "install_update", "package")
        val number = parse("install_update", """{"package":5,"url":"https://h/a","sha256":"ab"}""")
        assertInvalid(number, "install_update", "package")
    }

    @Test
    fun lockTaskValidAndInvalid() {
        assertThat(parse("lock_task", """{"pkg":"a.b","url":"https://k"}""").command)
            .isEqualTo(Command.LockTask("a.b", "https://k"))
        assertThat(parse("lock_task", """{"pkg":"a.b"}""").command).isEqualTo(Command.LockTask("a.b", null))
        assertInvalid(parse("lock_task"), "lock_task", "pkg")
        assertInvalid(parse("lock_task", """{"pkg":"a.b","url":[]}"""), "lock_task", "url")
    }

    @Test
    fun brokenJsonIsFailureNotException() {
        val result = CommandMapper.parse("{ definitely not json".toByteArray())

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(result.errorOrNull()!!.code).isEqualTo("invalid_payload")
    }

    @Test
    fun missingCmdIdOrTypeIsFailure() {
        assertThat(CommandMapper.parse("""{"type":"ping"}""".toByteArray()).errorOrNull()).isNotNull()
        assertThat(CommandMapper.parse("""{"cmdId":"c-1"}""".toByteArray()).errorOrNull()).isNotNull()
        assertThat(CommandMapper.parse("[]".toByteArray()).errorOrNull()).isNotNull()
    }

    @Test
    fun paramsThatAreNotAnObjectAreFailure() {
        val result = CommandMapper.parse("""{"cmdId":"c-1","type":"ping","params":"oops"}""".toByteArray())

        assertThat(result.errorOrNull()).isNotNull()
    }

    @Test
    fun parseCarriesMqttPropertiesAndExpiry() {
        val result = CommandMapper.parse(
            """{"cmdId":"c-9","type":"ping","expiresAt":42}""".toByteArray(),
            responseTopic = "rt",
            correlationData = byteArrayOf(7),
        ).getOrNull()!!

        assertThat(result.responseTopic).isEqualTo("rt")
        assertThat(result.correlationData).isEqualTo(byteArrayOf(7))
        assertThat(result.expiresAt).isEqualTo(42)
    }
}
