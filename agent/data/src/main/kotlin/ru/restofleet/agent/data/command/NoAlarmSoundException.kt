package ru.restofleet.agent.data.command

/** На устройстве нет ни мелодии будильника, ни мелодии уведомления по умолчанию. */
class NoAlarmSoundException : IllegalStateException("no default alarm or notification sound")
