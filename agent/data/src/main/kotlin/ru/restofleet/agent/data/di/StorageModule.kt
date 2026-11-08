package ru.restofleet.agent.data.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import ru.restofleet.agent.data.outbox.AgentDatabase
import ru.restofleet.agent.data.outbox.RoomOutbox
import ru.restofleet.agent.data.outbox.RoomProcessedCommands
import ru.restofleet.agent.domain.port.Outbox
import ru.restofleet.agent.domain.port.ProcessedCommands

/** Проводка Koin хранилища: база `agent.db`, очередь исходящих (10 000) и журнал команд (200); логики здесь нет. */
val storageModule: Module = module {
    single { AgentDatabase.open(androidContext(), AgentDatabase.FILE_NAME) }
    single<Outbox> { RoomOutbox(get(), RoomOutbox.DEFAULT_CAPACITY) }
    single<ProcessedCommands> { RoomProcessedCommands(get(), RoomProcessedCommands.DEFAULT_CAPACITY) }
}
