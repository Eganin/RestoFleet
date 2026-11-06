package ru.restofleet.agent.domain.port

fun interface SequenceSource {
    fun next(): Long
}
