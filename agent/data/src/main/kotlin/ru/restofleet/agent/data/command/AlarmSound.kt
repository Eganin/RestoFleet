package ru.restofleet.agent.data.command

import android.media.AudioAttributes

/** Один проигрываемый сигнал; [release] освобождает ресурс и может вызываться повторно. */
interface AlarmSound {
    fun start()
    fun release()
}

/** Создаёт сигнал с заданными атрибутами; бросает, если звук не удалось подготовить. */
fun interface AlarmSoundFactory {
    fun create(attributes: AudioAttributes): AlarmSound
}
