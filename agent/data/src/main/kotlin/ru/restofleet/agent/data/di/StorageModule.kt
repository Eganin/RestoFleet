package ru.restofleet.agent.data.di

import android.content.Context
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import ru.restofleet.agent.data.credentials.AesGcmCipher
import ru.restofleet.agent.data.credentials.AndroidKeystoreKeySource
import ru.restofleet.agent.data.credentials.BlobCipher
import ru.restofleet.agent.data.credentials.CredentialInspector
import ru.restofleet.agent.data.credentials.KeystoreCredentialStore
import ru.restofleet.agent.data.mqtt.CredentialChanges
import ru.restofleet.agent.data.mqtt.NotifyingCredentialStore
import ru.restofleet.agent.data.outbox.AgentDatabase
import ru.restofleet.agent.data.outbox.RoomOutbox
import ru.restofleet.agent.data.outbox.RoomProcessedCommands
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.agent.domain.port.Outbox
import ru.restofleet.agent.domain.port.ProcessedCommands

/**
 * Проводка Koin хранилища: база `agent.db`, очередь исходящих (10 000), журнал команд (200) и учётка брокера в
 * Android Keystore (`CredentialStore`, она же `CredentialInspector` для L5); логики здесь нет.
 * Нужен `AGENT_DISPATCHER`.
 */
val storageModule: Module = module {
    single { AgentDatabase.open(androidContext(), AgentDatabase.FILE_NAME) }
    single<Outbox> { RoomOutbox(get(), RoomOutbox.DEFAULT_CAPACITY) }
    single<ProcessedCommands> { RoomProcessedCommands(get(), RoomProcessedCommands.DEFAULT_CAPACITY) }
    single<BlobCipher> { AesGcmCipher(AndroidKeystoreKeySource()) }
    single {
        val prefs = androidContext().getSharedPreferences(KeystoreCredentialStore.PREFS_NAME, Context.MODE_PRIVATE)
        KeystoreCredentialStore(prefs, get(), get(AGENT_DISPATCHER))
    }
    // Смена учётки сообщается работающей MQTT-сессии (решение 23): запись идёт через декоратор
    single { CredentialChanges() }
    single<CredentialStore> { NotifyingCredentialStore(get<KeystoreCredentialStore>(), get()) }
    single<CredentialInspector> { get<KeystoreCredentialStore>() }
}
