package ru.restofleet.agent.data.command

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager

/** Сигнал — мелодия будильника по умолчанию, зацикленная до [AlarmSound.release], через `MediaPlayer`. */
class MediaPlayerSoundFactory(private val context: Context) : AlarmSoundFactory {
    override fun create(attributes: AudioAttributes): AlarmSound {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(attributes)
            player.setDataSource(context, uri)
            player.isLooping = true
            player.prepare()
        } catch (e: java.io.IOException) {
            player.release()
            throw e
        }
        return object : AlarmSound {
            override fun start() = player.start()
            override fun release() = player.release()
        }
    }
}
