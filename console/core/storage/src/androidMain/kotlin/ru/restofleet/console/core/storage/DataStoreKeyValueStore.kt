package ru.restofleet.console.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** [KeyValueStore] поверх Preferences DataStore (в нём лежит только шифротекст). */
class DataStoreKeyValueStore(private val dataStore: DataStore<Preferences>) : KeyValueStore {
    override suspend fun get(key: String): String? = dataStore.data.first()[stringPreferencesKey(key)]

    override suspend fun put(key: String, value: String) {
        dataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    override suspend fun remove(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }
}
