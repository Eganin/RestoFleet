package ru.restofleet.server.domain.common

/** Порт хэширования паролей (bcrypt в data, реш. MVP; cost 10). */
interface PasswordHasher {
    fun hash(password: String): String

    /** Проверка без исключений: битый хэш — `false`. */
    fun verify(password: String, hash: String): Boolean
}

/** Порт криптостойких секретов (`SecureRandom` в data). */
interface SecretGenerator {
    /** Код регистрации: 128 бит (арх. разд. 12), удобный для ручного ввода. */
    fun provisioningCode(): String

    /** Пароль устройства: 32 случайных байта (арх. разд. 12), отдаётся один раз. */
    fun devicePassword(): String

    /** Refresh-токен пользователя: 32 случайных байта (реш. 216). */
    fun refreshToken(): String
}

/**
 * Хэш секретов для хранения и поиска: SHA-256 (hex). Коды регистрации и refresh-токены в БД хранятся только хэшем
 * (арх. разд. 12, реш. 216); тот же хэш — ключ кэша Basic-проверки устройства (реш. 213).
 */
fun interface SecretDigest {
    fun sha256(value: String): String
}

/** Сравнение строк за время, не зависящее от позиции первого различия. */
fun constantTimeEquals(a: String, b: String): Boolean {
    var diff = a.length xor b.length
    val n = minOf(a.length, b.length)
    for (i in 0 until n) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}
