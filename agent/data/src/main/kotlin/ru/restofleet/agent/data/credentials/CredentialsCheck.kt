package ru.restofleet.agent.data.credentials

import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.shared.kernel.Clock

/**
 * L5: учётка брокера читается из хранилища на Android Keystore. В `raw` — только `deviceId` и `host:port`, пароль
 * не попадает ни в `raw`, ни в `note`. Запись есть, но не расшифровалась (ключ потерян или запись повреждена) —
 * UNAVAILABLE с классом причины; записи нет — NOT_CHECKED.
 */
class CredentialsCheck(
    private val clock: Clock,
    private val inspector: CredentialInspector,
) : CapabilityCheck {
    override val id = CheckId.L5

    override suspend fun run(): CheckResult {
        val now = clock.nowMillis()
        return when (val state = inspector.inspect()) {
            is StoredCredentials.Present -> CheckResult(
                id,
                CheckStatus.OK,
                mapOf(
                    "deviceId" to state.settings.deviceId.value,
                    "endpoint" to "${state.settings.host}:${state.settings.port}",
                ),
                null,
                now,
            )
            StoredCredentials.Missing -> CheckResult(
                id,
                CheckStatus.NOT_CHECKED,
                emptyMap(),
                "no credentials saved: provision the device first (debug: DEV_CONFIG broadcast)",
                now,
            )
            is StoredCredentials.Unreadable -> CheckResult(
                id,
                CheckStatus.UNAVAILABLE,
                mapOf("cause" to state.reason),
                "stored credentials could not be decrypted and were erased (${state.reason}); provision again",
                now,
            )
        }
    }
}
