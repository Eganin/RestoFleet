package ru.restofleet.agent.data.command

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.restofleet.agent.domain.port.Beeper
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome
import kotlin.coroutines.cancellation.CancellationException

/**
 * C3: сигнал на потоке будильника (`USAGE_ALARM`). Возвращается сразу после старта; остановку через `durationMs`
 * делает собственный [scope] адаптера. Повторный `beep` останавливает звучащий сигнал и начинает новый.
 * Блокирующая подготовка звука идёт на [ioDispatcher]; параллельные вызовы выстраивает [Mutex] (поток не занят).
 */
class AlarmBeeper(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val soundFactory: AlarmSoundFactory = MediaPlayerSoundFactory(context),
) : Beeper {
    private val mutex = Mutex()
    private var current: Playing? = null

    private class Playing(val sound: AlarmSound, var stopper: Job? = null)

    // Любой сбой звука — Failure, тип исключения и есть причина; Error и отмена вызывающего пробрасываются
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun beep(durationMs: Long): Outcome<Map<String, String>, DomainError> =
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            mutex.withLock {
                stopCurrent()
                // Всё в одном withContext: отмена после возврата не должна потерять уже запущенный звук
                withContext(ioDispatcher) { startNew(attributes, durationMs) }
            }
            Outcome.Success(streamState())
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            failure(e)
        } catch (e: NoAlarmSoundException) {
            Outcome.Failure(CommandError("no_alarm_sound"))
        } catch (e: Exception) {
            failure(e)
        }

    /** Вызывается под [mutex]; освобождает звук при любом сбое старта, включая `Error`. */
    private fun startNew(attributes: AudioAttributes, durationMs: Long) {
        val sound = soundFactory.create(attributes)
        var started = false
        try {
            sound.start()
            started = true
        } finally {
            if (!started) sound.release()
        }
        val playing = Playing(sound)
        playing.stopper = scope.launch {
            delay(durationMs)
            mutex.withLock { if (current === playing) stopCurrent() }
        }
        current = playing
    }

    private fun stopCurrent() {
        current?.let {
            it.stopper?.cancel()
            it.sound.release()
        }
        current = null
    }

    private fun failure(e: Exception) = Outcome.Failure(CommandError("beep_failed:${e::class.simpleName ?: "Unknown"}"))

    /**
     * `USAGE_ALARM` звучит и в `silent`; беззвучным сигнал делает нулевая громкость потока будильника, поэтому
     * громкость идёт в результат рядом с режимом звонка.
     */
    private fun streamState(): Map<String, String> {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ringer = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        return mapOf(
            "ringerMode" to ringer,
            "usage" to "alarm",
            "alarmVolume" to "${audio.getStreamVolume(AudioManager.STREAM_ALARM)}",
            "alarmVolumeMax" to "${audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)}",
        )
    }
}
