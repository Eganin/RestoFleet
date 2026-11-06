package ru.restofleet.agent.domain.capability

/** Группа проверок матрицы осуществимости. */
enum class CheckGroup { LIFECYCLE, READING, PROBE, COMMAND, DEVICE_OWNER, MQTT, RESOURCES }

/** Идентификаторы проверок матрицы (docs/stages/0-mvp-feasibility.md); [title] — короткое название для UI и отчёта. */
@Suppress("MagicNumber")
enum class CheckId(val group: CheckGroup, val title: String) {
    L1(CheckGroup.LIFECYCLE, "FGS живёт 12 ч"),
    L2(CheckGroup.LIFECYCLE, "Запуск FGS после перезагрузки"),
    L3(CheckGroup.LIFECYCLE, "Сторож WorkManager"),
    L4(CheckGroup.LIFECYCLE, "Исключения и разрешения мастера"),
    L5(CheckGroup.LIFECYCLE, "Учётка в Android Keystore"),

    R1(CheckGroup.READING, "Заряд и питание"),
    R2(CheckGroup.READING, "Температура батареи"),
    R3(CheckGroup.READING, "Тепловое состояние и запас"),
    R4(CheckGroup.READING, "Тип сети, RSSI, validated"),
    R5(CheckGroup.READING, "SSID"),
    R6(CheckGroup.READING, "Диск, ОЗУ, uptime, ОС, модель"),
    R7(CheckGroup.READING, "Версии приложений"),
    R8(CheckGroup.READING, "Приложение на переднем плане"),
    R9(CheckGroup.READING, "Освещённость"),
    R10(CheckGroup.READING, "Температуры CPU и корпуса (DO)"),
    R11(CheckGroup.READING, "Перемещение и падение"),
    R12(CheckGroup.READING, "Обновление и удаление приложений"),
    R13(CheckGroup.READING, "Серийный номер (DO)"),
    R14(CheckGroup.READING, "Смена сети, потеря интернета"),

    P1(CheckGroup.PROBE, "HTTPS-проверка и классы ошибок"),
    P2(CheckGroup.PROBE, "Фазы DNS, TCP, TLS, TTFB"),
    P3(CheckGroup.PROBE, "Проверка роутера и шлюза"),
    P4(CheckGroup.PROBE, "Проверки без брокера, буфер"),

    C1(CheckGroup.COMMAND, "show_message: уведомление"),
    C2(CheckGroup.COMMAND, "launch_app через уведомление"),
    C3(CheckGroup.COMMAND, "launch_app через SYSTEM_ALERT_WINDOW"),
    C4(CheckGroup.COMMAND, "beep в беззвучном режиме"),

    D1(CheckGroup.DEVICE_OWNER, "Установка Device Owner"),
    D2(CheckGroup.DEVICE_OWNER, "reboot()"),
    D3(CheckGroup.DEVICE_OWNER, "Тихая установка APK, SHA-256"),
    D4(CheckGroup.DEVICE_OWNER, "Киоск: lock task и Chrome"),
    D5(CheckGroup.DEVICE_OWNER, "Запуск приложения из фона"),
    D6(CheckGroup.DEVICE_OWNER, "Выдача себе разрешений"),
    D7(CheckGroup.DEVICE_OWNER, "Регистрация DO по QR"),

    M1(CheckGroup.MQTT, "TLS 8883 с CA стенда"),
    M2(CheckGroup.MQTT, "DynSec и ACL по топикам"),
    M3(CheckGroup.MQTT, "LWT с connId"),
    M4(CheckGroup.MQTT, "Retained desired, reported"),
    M5(CheckGroup.MQTT, "Команды: Response Topic, Expiry"),
    M6(CheckGroup.MQTT, "Постоянная сессия"),
    M7(CheckGroup.MQTT, "Outbox: 1 ч офлайна"),

    RS1(CheckGroup.RESOURCES, "ОЗУ процесса агента"),
    RS2(CheckGroup.RESOURCES, "Трафик за 1 ч"),
}
