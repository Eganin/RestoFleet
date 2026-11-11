package ru.restofleet.server.domain.common

/** Страница выборки: значения вне допустимых пределов приводятся к ним, а не отклоняются. */
class PageRequest private constructor(val limit: Int, val offset: Long) {
    override fun equals(other: Any?): Boolean = other is PageRequest && other.limit == limit && other.offset == offset

    override fun hashCode(): Int = 31 * limit + offset.hashCode()

    override fun toString(): String = "PageRequest(limit=$limit, offset=$offset)"

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 1_000

        fun of(limit: Int? = null, offset: Long? = null): PageRequest =
            PageRequest((limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT), (offset ?: 0L).coerceAtLeast(0L))
    }
}

/** Полуоткрытый интервал времени `[from, to)`, epoch ms; `null` — без границы. `from > to` даёт пустую выборку. */
data class TimeRange(val from: Long? = null, val to: Long? = null)
