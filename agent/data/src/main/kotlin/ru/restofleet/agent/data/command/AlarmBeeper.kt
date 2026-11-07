package ru.restofleet.agent.data.command

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import ru.restofleet.agent.domain.port.Beeper
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome
import kotlin.coroutines.cancellation.CancellationException

/**
 * C3: сигнал на потоке будильника (`USAGE_ALARM`). Возвращается сразу после старта; остановку через `durationMs`
 * делает собственный [scope] адаптера. Повторный `beep` останавливает звучащий сигнал и начинает новый.
 */
class AlarmBeeper(
    private val context: Context,
    private val scope: CoroutineScope,
    private val soundFactory: AlarmSoundFactory = MediaPlayerSoundFactory(context),
) : Beeper {
    private val lock = Any()
    private var current: Playing? = null

    private class Playing(val sound: AlarmSound, var stopper: Job? = null)

    @Suppress("TooGenericExceptionCaught") // любой сбой звука — Failure, Error и отмена вызывающего пробрасываются
    override suspend fun beep(durationMs: Long): Outcome<Map<String, String>, DomainError> =
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            synchronized(lock) {
                stopCurrent()
                val sound = soundFactory.create(attributes)
                val playing = Playing(sound)
                try {
                    sound.start()
                } catch (e: Exception) {
                    sound.release()
                    throw e
                }
                playing.stopper = scope.launch {
                    delay(durationMs)
                    synchronized(lock) { if (current === playing) stopCurrent() }
                }
                current = playing
            }
            Outcome.Success(mapOf("ringerMode" to ringerMode(), "usage" to "alarm"))
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            failure(e)
        } catch (e: Exception) {
            failure(e)
        }

    private fun stopCurrent() {
        current?.let {
            it.stopper?.cancel()
            it.sound.release()
        }
        current = null
    }

    private fun failure(e: Exception) = Outcome.Failure(CommandError("beep_failed:${e::class.simpleName ?: "Unknown"}"))

    private fun ringerMode(): String =
        when ((context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
}
