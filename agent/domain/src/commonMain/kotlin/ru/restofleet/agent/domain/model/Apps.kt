package ru.restofleet.agent.domain.model

data class AppVersion(val pkg: String, val versionName: String?, val versionCode: Long)

data class ForegroundApp(val pkg: String, val sinceMillis: Long)
