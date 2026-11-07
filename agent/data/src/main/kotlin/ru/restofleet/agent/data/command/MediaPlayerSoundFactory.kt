package ru.restofleet.agent.data.command

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri

/** Сигнал — мелодия будильника по умолчанию, зацикленная до [AlarmSound.release], через `MediaPlayer`. */
class MediaPlayerSoundFactory(
    private val context: Context,
    private val playerProvider: () -> MediaPlayer = { MediaPlayer() },
    private val uriProvider: () -> Uri? = {
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    },
) : AlarmSoundFactory {
    /**
     * Блокирует (`prepare`): вызывать вне главного потока. Плеер освобождается при любом сбое подготовки, включая
     * `Error` (он не перехватывается, только `finally`); отсутствие мелодии — ожидаемый отказ [NoAlarmSoundException].
     */
    override fun create(attributes: AudioAttributes): AlarmSound {
        val uri = uriProvider() ?: throw NoAlarmSoundException()
        val player = playerProvider()
        var prepared = false
        try {
            player.setAudioAttributes(attributes)
            player.setDataSource(context, uri)
            player.isLooping = true
            player.prepare()
            prepared = true
        } finally {
            if (!prepared) player.release()
        }
        return object : AlarmSound {
            override fun start() = player.start()
            override fun release() = player.release()
        }
    }
}
