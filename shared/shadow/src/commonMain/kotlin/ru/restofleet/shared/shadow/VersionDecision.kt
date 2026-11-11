package ru.restofleet.shared.shadow

/**
 * Что делать с пришедшей версией `desired` (ТЗ 7.2): старее сохранённой — игнорировать, равная — только повторить
 * `reported`, новее (или ничего не сохранено) — применить и сохранить.
 */
enum class VersionDecision {
    IGNORE_OLDER,
    ECHO_EQUAL,
    APPLY_NEWER,
    ;

    companion object {
        fun of(stored: Long?, incoming: Long): VersionDecision = when {
            stored == null || incoming > stored -> APPLY_NEWER
            incoming == stored -> ECHO_EQUAL
            else -> IGNORE_OLDER
        }
    }
}
