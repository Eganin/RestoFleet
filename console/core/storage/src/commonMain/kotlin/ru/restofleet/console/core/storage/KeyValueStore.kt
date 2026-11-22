package ru.restofleet.console.core.storage

/** Простейшее строковое хранилище; на Android — DataStore, в тестах — память. */
interface KeyValueStore {
    suspend fun get(key: String): String?

    suspend fun put(key: String, value: String)

    suspend fun remove(key: String)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()

    override suspend fun get(key: String): String? = map[key]

    override suspend fun put(key: String, value: String) {
        map[key] = value
    }

    override suspend fun remove(key: String) {
        map.remove(key)
    }
}
