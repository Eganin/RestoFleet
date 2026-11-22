package ru.restofleet.shared.shadow

/**
 * Имя пакета Android: сегменты `[A-Za-z][A-Za-z0-9_]*` через точку, не меньше двух (перенесено из агента, реш. 225).
 */
fun isValidPackageName(name: String): Boolean = PACKAGE_NAME.matches(name)

private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
