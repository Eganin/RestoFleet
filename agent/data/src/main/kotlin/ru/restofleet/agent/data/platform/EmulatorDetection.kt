package ru.restofleet.agent.data.platform

private val EMULATOR_HARDWARE = setOf("ranchu", "goldfish")

/**
 * Эмулятор: `Build.HARDWARE` — ranchu/goldfish, либо fingerprint начинается с `generic` (AOSP-эмулятор),
 * либо содержит сегмент `/sdk_gphone` или `sdk_google` (образы Google). Подстрока `generic`/`emu` где угодно
 * не используется: так называются бренды дешёвых планшетов и GSI-образы на реальном железе.
 */
fun isEmulator(hardware: String, fingerprint: String): Boolean =
    hardware in EMULATOR_HARDWARE ||
        fingerprint.startsWith("generic") ||
        "/sdk_gphone" in fingerprint ||
        "sdk_google" in fingerprint
