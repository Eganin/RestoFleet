package ru.restofleet.console.feature.devicedetails.ui.viewmodel.mapper

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.common.RelativeTime
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.core.ui.ChartPoint
import ru.restofleet.console.core.ui.PhaseSegment
import ru.restofleet.console.feature.devicedetails.domain.DeviceCard
import ru.restofleet.console.feature.devicedetails.domain.DeviceEvent
import ru.restofleet.console.feature.devicedetails.domain.ProbeResult
import ru.restofleet.console.feature.devicedetails.domain.TempThreshold
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.DetailsUiState
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.EventRowUi
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.ProbeLastUi
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.ProbeRowUi
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.TileUi
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.model.DetailsSession
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.model.DetailsTab

class DetailsUiStateMapper {
    fun map(s: DetailsSession): DetailsUiState {
        val card = s.card
        val now = s.knownNow
        val alerts = card?.alerts.orEmpty()
        val probes = s.probes.orEmpty()
        return DetailsUiState(
            title = card?.let { listOfNotNull(it.name ?: it.id, it.restaurantName).joinToString(" · ") }.orEmpty(),
            subtitle = card?.let(::subtitle).orEmpty(),
            statusText = card?.status?.let { STATUS_TEXT[it] ?: it }.orEmpty(),
            statusTone = tone(card),
            rules = alerts.map { it.ruleId }.distinct(),
            tabs = DetailsTab.entries.map { it.title },
            selectedTab = s.tab.ordinal,
            tiles = card?.let { tiles(it) }.orEmpty(),
            chart = s.temp.map { ChartPoint(it.ts, it.tempC) },
            chartThreshold = TempThreshold.of(alerts),
            chartCaption = "Температура · 30 мин · порог ${TempThreshold.of(alerts).let(::number)}",
            footer = card?.telemetry?.let { "Последние данные ${RelativeTime.ago(now, it.ts)}" } ?: "Данных телеметрии нет",
            probesLast = probes.groupBy { it.target }.map { (target, list) -> last(target, list.maxBy { it.ts }) },
            probeRows = probes.take(MAX_PROBE_ROWS).map { probeRow(it, now) },
            probesLoaded = s.probes != null,
            probesErrorText = s.probesError?.let(::errorText),
            eventRows = s.events.orEmpty().map { eventRow(it, now) },
            eventsLoaded = s.events != null,
            canLoadMoreEvents = s.eventsHasMore && !s.eventsLoadingMore,
            eventsErrorText = s.eventsError?.let(::errorText),
            loading = s.loading && card == null,
            errorText = s.error?.let(::errorText),
            commandSheet = s.commandSheet,
            commandsEnabled = card != null,
            shadowSelected = s.tab == DetailsTab.Shadow,
        )
    }

    private fun subtitle(c: DeviceCard): String {
        val since = c.session?.let {
            if (c.status == "online") "online ${RelativeTime.duration(c.loadedAt - it.connectedAt)}" else null
        }
        val model = listOfNotNull(c.model, c.osVersion?.let { "A$it" }).joinToString(" · ")
        return listOfNotNull(since ?: STATUS_TEXT[c.status], model.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    private fun tone(c: DeviceCard?): StatusTone = when {
        c == null -> StatusTone.Off
        c.alerts.any { it.critical } -> StatusTone.Crit
        c.alerts.isNotEmpty() -> StatusTone.Warn
        c.status == "online" -> StatusTone.Ok
        else -> StatusTone.Off
    }

    private fun tiles(c: DeviceCard): List<TileUi> {
        val t = c.telemetry
        val rules = c.alerts.map { it.ruleId }.toSet()
        fun alert(rule: String) = rule in rules
        return listOf(
            TileUi(
                "t батареи", t?.tempC?.let { "${number(it)} °C" } ?: "—", t?.thermal?.takeIf { it != "NONE" },
                if (alert("R-03")) StatusTone.Crit else null, alert("R-03"),
            ),
            TileUi(
                "Заряд", t?.batteryLevel?.let { "$it %" } ?: "—",
                t?.charging?.let { if (it) "⚡ от сети" else "от батареи" },
                if (alert("R-02")) StatusTone.Warn else null, alert("R-02"),
            ),
            TileUi("Wi-Fi", t?.rssi?.let { "$it dBm" } ?: "—", t?.rssi?.let(::signal), null, false),
            TileUi(
                "На экране", t?.foregroundApp?.substringAfterLast('.') ?: "—", t?.foregroundApp,
                if (alert("R-04")) StatusTone.Warn else null, alert("R-04"),
            ),
            TileUi(
                "Память", t?.availMb?.let { "${gb(it)} ГБ" } ?: "—", t?.freeMb?.let { "диск ${gb(it)} ГБ" },
                if (alert("R-06")) StatusTone.Warn else null, alert("R-06"),
            ),
            TileUi("Агент", c.agentVersion ?: "—", c.mode?.let { "режим $it" }, null, false),
        )
    }

    private fun last(target: String, p: ProbeResult): ProbeLastUi = ProbeLastUi(
        target = target,
        ok = p.ok,
        summary = probeText(p),
        phases = listOf(
            PhaseSegment("DNS", p.dnsMs ?: 0), PhaseSegment("TCP", p.connectMs ?: 0),
            PhaseSegment("TLS", p.tlsMs ?: 0), PhaseSegment("TTFB", p.ttfbMs ?: 0),
        ),
    )

    private fun probeText(p: ProbeResult): String =
        if (p.ok) "${p.status ?: "ok"} · ${p.totalMs} мс" else "${p.error ?: "ошибка"} · ${p.totalMs} мс"

    private fun probeRow(p: ProbeResult, now: Long) = ProbeRowUi(RelativeTime.ago(now, p.ts), p.target, p.ok, probeText(p))

    private fun eventRow(e: DeviceEvent, now: Long): EventRowUi {
        val f = e.fields
        val text = when (e.type) {
            "probe_state" -> listOfNotNull(f["target"], f["state"], f["error"], f["failures"]?.let { "×$it" }).joinToString(" ")
            "package_changed" -> listOfNotNull(f["pkg"], f["action"]).joinToString(" · ")
            else -> f.entries.joinToString(" · ") { "${it.key}=${it.value}" }
        }
        return EventRowUi(RelativeTime.ago(now, e.ts), e.type, text)
    }

    private fun errorText(e: ConsoleError): String = when (e) {
        ConsoleError.Network -> "Нет связи с сервером"
        ConsoleError.NotFound -> "Устройство не найдено"
        ConsoleError.Unauthorized -> "Сессия истекла, войдите заново"
        else -> "Не удалось загрузить данные"
    }

    private fun number(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    private fun gb(mb: Long): String = (mb / GB_STEP_TENTHS).let { "${it / TENTH}.${it % TENTH}" }

    private fun signal(rssi: Int): String = when {
        rssi >= RSSI_GOOD -> "сигнал хороший"
        rssi >= RSSI_OK -> "сигнал средний"
        else -> "сигнал слабый"
    }

    private companion object {
        const val MAX_PROBE_ROWS = 30
        const val GB_STEP_TENTHS = 102L
        const val TENTH = 10L
        const val RSSI_GOOD = -67
        const val RSSI_OK = -80
        val STATUS_TEXT = mapOf("online" to "online", "offline" to "офлайн", "provisioned" to "не подключено")
    }
}
