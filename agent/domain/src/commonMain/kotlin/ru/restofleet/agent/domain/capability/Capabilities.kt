package ru.restofleet.agent.domain.capability

import ru.restofleet.agent.domain.command.Command

enum class Permission {
    POST_NOTIFICATIONS,
    USAGE_ACCESS,
    SYSTEM_ALERT_WINDOW,
    FULL_SCREEN_INTENT,
    FINE_LOCATION,
    IGNORE_BATTERY_OPT,
}

enum class LaunchStrategy { DIRECT_DO, DIRECT_OVERLAY, VIA_NOTIFICATION }

data class Capabilities(val mode: AgentMode, val apiLevel: Int, val granted: Set<Permission>) {
    fun launchStrategy(): LaunchStrategy = when {
        mode == AgentMode.DEVICE_OWNER -> LaunchStrategy.DIRECT_DO
        Permission.SYSTEM_ALERT_WINDOW in granted -> LaunchStrategy.DIRECT_OVERLAY
        else -> LaunchStrategy.VIA_NOTIFICATION
    }

    /**
     * `null` — команду можно исполнять; иначе `reason` для `Unsupported`. Команда приходит по сети, поэтому формат
     * аргументов проверяется здесь же, до обращения к портам. Порядок: режим, затем формат аргументов.
     */
    fun check(command: Command): String? = when (command) {
        Command.Ping -> null
        is Command.ShowMessage -> checkShowMessage(command)
        is Command.LaunchApp -> checkLaunchApp(command)
        is Command.Beep -> if (command.durationMs in BEEP_RANGE_MS) null else "invalid_duration"
        Command.Reboot -> requireDeviceOwner()
        is Command.InstallApk -> requireDeviceOwner() ?: checkInstall(command)
        is Command.LockTask -> requireDeviceOwner() ?: checkTarget(command.pkg, command.url)
    }

    private fun requireDeviceOwner(): String? =
        if (mode == AgentMode.DEVICE_OWNER) null else "requires_device_owner"

    private fun notificationsDenied(): Boolean =
        apiLevel >= NOTIFICATION_PERMISSION_API && Permission.POST_NOTIFICATIONS !in granted

    private fun checkShowMessage(command: Command.ShowMessage): String? = when {
        command.text.isBlank() -> "empty_text"
        command.text.length > MAX_TEXT_LENGTH -> "text_too_long"
        notificationsDenied() -> "notifications_denied"
        else -> null
    }

    private fun checkLaunchApp(command: Command.LaunchApp): String? =
        checkTarget(command.pkg, command.url)
            ?: if (launchStrategy() == LaunchStrategy.VIA_NOTIFICATION && notificationsDenied()) {
                "notifications_denied"
            } else {
                null
            }

    private fun checkInstall(command: Command.InstallApk): String? = when {
        !isHttpsUrl(command.url) -> "invalid_url"
        !SHA256.matches(command.sha256) -> "invalid_sha256"
        else -> null
    }

    private fun checkTarget(pkg: String, url: String?): String? = when {
        pkg.length > MAX_PACKAGE_LENGTH || !PACKAGE_NAME.matches(pkg) -> "invalid_package"
        url != null && !isHttpsUrl(url) -> "invalid_url"
        else -> null
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_API = 33
        val BEEP_RANGE_MS = 1L..30_000L
        val PACKAGE_NAME = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
        val SHA256 = Regex("[0-9a-fA-F]{64}")
        val HOST_LABEL = Regex("[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?")
        val PORT = Regex("[0-9]{1,5}")
        val PORT_RANGE = 1..65_535
        const val MAX_TEXT_LENGTH = 1000
        const val MAX_PACKAGE_LENGTH = 255
        const val MAX_URL_LENGTH = 2048
        const val HTTPS_PREFIX = "https://"
        const val AUTHORITY_END = "/?#"

        /**
         * Чистый Kotlin (без java.net): схема строго `https://` в нижнем регистре, длина до 2048, без пробелов и
         * управляющих символов. Authority (до `/ ? #`) = хост из непустых DNS-меток `[A-Za-z0-9-]` (метка не
         * начинается и не кончается `-`) и необязательный `:порт` (1–5 цифр, 1..65535). Любое другое содержимое
         * authority (userinfo, `@`, IPv6, второй `:`) отвергается.
         */
        fun isHttpsUrl(url: String): Boolean {
            val plain = url.length <= MAX_URL_LENGTH && url.none { it.isWhitespace() || it.isISOControl() }
            if (!plain || !url.startsWith(HTTPS_PREFIX)) return false
            val authority = url.drop(HTTPS_PREFIX.length).takeWhile { it !in AUTHORITY_END }
            val port = if (':' in authority) authority.substringAfter(':') else null
            return authority.substringBefore(':').split('.').all { HOST_LABEL.matches(it) } &&
                (port == null || isPort(port))
        }

        private fun isPort(port: String): Boolean = PORT.matches(port) && port.toInt() in PORT_RANGE
    }
}
