package ru.restofleet.agent.data.platform

/** Эмулятор определяется по `Build.FINGERPRINT`: `generic` (AOSP/Google APIs) или `emu` (sdk_gphone…/emu64a). */
fun isEmulator(fingerprint: String): Boolean {
    val normalized = fingerprint.lowercase()
    return "generic" in normalized || "emu" in normalized
}
