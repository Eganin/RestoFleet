// Модули консоли (этап П, `architecture.md` разд. 4): список ведёт поток B (реш. 407).
// Подключается из корневого settings.gradle.kts через apply(from = …); имя *.settings.gradle.kts — скрипт настроек.
include(":console:core:mvi")
include(":console:core:designsystem")
include(":console:core:common")
include(":console:core:testing", ":console:core:decompose", ":console:core:storage", ":console:core:network")
include(":console:core:ui")
include(":console:feature:auth:auth-domain", ":console:feature:auth:auth-data", ":console:feature:auth:auth-ui", ":console:feature:auth:auth-component")
include(":console:root")
include(":console:app-android")
include(":console:app-desktop")
