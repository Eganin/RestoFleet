package ru.restofleet.console.feature.commands.component.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.console.core.network.HttpQualifiers
import ru.restofleet.console.feature.commands.component.CommandsComponent
import ru.restofleet.console.feature.commands.component.CommandsComponentFactory
import ru.restofleet.console.feature.commands.data.CommandsApi
import ru.restofleet.console.feature.commands.data.CommandsRepositoryImpl
import ru.restofleet.console.feature.commands.domain.AvailableCommands
import ru.restofleet.console.feature.commands.domain.CommandsRepository
import ru.restofleet.console.feature.commands.domain.LoadCommandsOverview
import ru.restofleet.console.feature.commands.domain.RefreshHistory
import ru.restofleet.console.feature.commands.domain.SendCommand
import ru.restofleet.console.feature.commands.domain.TrackCommand
import ru.restofleet.console.feature.commands.ui.viewmodel.CommandsViewModel

val commandsModule = module {
    single { CommandsApi(get(named(HttpQualifiers.AUTHORIZED))) }
    single<CommandsRepository> { CommandsRepositoryImpl(get()) }
    factory { AvailableCommands() }
    factory { LoadCommandsOverview(get(), get()) }
    factory { RefreshHistory(get()) }
    factory { SendCommand(get()) }
    factory { TrackCommand(get(), get()) }
    single<CommandsComponentFactory> {
        CommandsComponentFactory { context, deviceId ->
            CommandsComponent(context) { CommandsViewModel(deviceId, get(), get(), get(), get()) }
        }
    }
}
