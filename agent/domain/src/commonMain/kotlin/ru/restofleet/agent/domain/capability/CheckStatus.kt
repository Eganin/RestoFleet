package ru.restofleet.agent.domain.capability

/** Итог проверки: OK ✅, LIMITED ⚠️, UNAVAILABLE ❌, NOT_APPLICABLE —, NOT_CHECKED ⏳. */
enum class CheckStatus { OK, LIMITED, UNAVAILABLE, NOT_APPLICABLE, NOT_CHECKED }
