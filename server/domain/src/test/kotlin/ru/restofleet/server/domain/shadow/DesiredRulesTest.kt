package ru.restofleet.server.domain.shadow

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue
import ru.restofleet.shared.shadow.ShadowValue.Bool
import ru.restofleet.shared.shadow.ShadowValue.Num
import ru.restofleet.shared.shadow.ShadowValue.Obj
import ru.restofleet.shared.shadow.ShadowValue.Str

class DesiredRulesTest {
    private fun list(vararg items: ShadowValue) = ShadowValue.List(items.toList())

    private fun obj(vararg pairs: Pair<String, ShadowValue>) = Obj(mapOf(*pairs))

    private val probe = obj(
        "id" to Str("api"), "type" to Str("http"), "url" to Str("https://api/health"),
        "intervalSec" to Num(30), "timeoutMs" to Num(5000), "failThreshold" to Num(3),
    )

    private fun with(vararg pairs: Pair<String, ShadowValue>) =
        DesiredMerge.merge(DefaultDesired.document, ShadowDocument(mapOf(*pairs)))

    @Test
    fun defaultIsValid() {
        assertThat(DesiredRules.validate(DefaultDesired.document)).isNull()
    }

    @Test
    fun fullDesiredWithProbesIsValid() {
        val extra = mapOf("id" to Str("kds"), "reference" to Bool(true), "expectedStatus" to Num(204))
        val probe2 = Obj(probe.fields + extra)
        val doc = with("watchedApps" to list(Str("com.android.chrome")), "probes" to list(probe, probe2))
        assertThat(DesiredRules.validate(doc)).isNull()
    }

    @Test
    fun violations() {
        val noTelemetry = ShadowDocument(DefaultDesired.document.fields - "telemetry")
        val cases: Map<ShadowDocument, ServerError.Validation> = mapOf(
            with("extra" to Num(1)) to v("extra", "unknown_field"),
            noTelemetry to v("telemetry", "required"),
            with("telemetry" to Num(1)) to v("telemetry", "type"),
            with("telemetry" to obj("intervalSec" to Num(30), "x" to Num(1))) to v("telemetry.x", "unknown_field"),
            with("telemetry" to obj("intervalSec" to Num(30.5))) to v("telemetry.intervalSec", "not_integer"),
            with("telemetry" to obj("intervalSec" to Str("30"))) to v("telemetry.intervalSec", "type"),
            with("telemetry" to obj("intervalSec" to Num(5))) to
                v("telemetryIntervalSec", "telemetryIntervalSec:out_of_range"),
            with("thresholds" to obj("batteryLowPct" to Num(0))) to v("thresholds.batteryLowPct", "out_of_range"),
            with("thresholds" to obj("tempHighC" to Num(101))) to v("thresholds.tempHighC", "out_of_range"),
            with("thresholds" to obj("tempHighC" to Bool(true))) to v("thresholds.tempHighC", "type"),
            with("thresholds" to obj("fgAbsentMin" to Num(0))) to v("thresholds.fgAbsentMin", "out_of_range"),
            with("thresholds" to obj("q" to Num(0))) to v("thresholds.q", "unknown_field"),
            with("watchedApps" to Str("x")) to v("watchedApps", "type"),
            with("watchedApps" to list(Num(1))) to v("watchedApps[0]", "type"),
            with("watchedApps" to list(Str("bad name"))) to v("watchedApps", "watchedApps:invalid_name:bad name"),
            with("probes" to list(Num(1))) to v("probes[0]", "type"),
            with("probes" to list(Obj(probe.fields - "url"))) to v("probes[0].url", "required"),
            with("probes" to list(Obj(probe.fields + ("zz" to Num(1))))) to v("probes[0].zz", "unknown_field"),
            with("probes" to list(Obj(probe.fields + ("type" to Str("icmp"))))) to v("probes[0].type", "unknown_type"),
            with("probes" to list(Obj(probe.fields + ("id" to Num(1))))) to v("probes[0].id", "type"),
            with("probes" to list(Obj(probe.fields + ("intervalSec" to Num(1))))) to
                v("probes[0].intervalSec", "out_of_range"),
            with("probes" to list(Obj(probe.fields + ("timeoutMs" to Num(99))))) to
                v("probes[0].timeoutMs", "out_of_range"),
            with("probes" to list(Obj(probe.fields + ("failThreshold" to Num(0))))) to
                v("probes[0].failThreshold", "out_of_range"),
            with("probes" to list(Obj(probe.fields + ("reference" to Str("y"))))) to v("probes[0].reference", "type"),
            with("probes" to list(Obj(probe.fields + ("expectedStatus" to Num(600))))) to
                v("probes[0].expectedStatus", "out_of_range"),
            with("probes" to list(probe, probe)) to v("probes", "probes:duplicate_id:api"),
            with("probes" to list(Obj(probe.fields + ("url" to Str(" "))))) to v("probes", "probes:empty_url:api"),
            with("probes" to Num(1)) to v("probes", "type"),
            ShadowDocument(DefaultDesired.document.fields - "probes") to v("probes", "required"),
            ShadowDocument(DefaultDesired.document.fields - "thresholds") to v("thresholds", "required"),
        )
        cases.forEach { (doc, error) -> assertThat(DesiredRules.validate(doc)).isEqualTo(error) }
    }

    @Test
    fun mergeIsRecursiveReplacesListsAndDropsMetadata() {
        val base = with("watchedApps" to list(Str("a.b"), Str("c.d")))
        val patch = ShadowDocument(
            mapOf(
                "version" to Num(99),
                "thresholds" to obj("tempHighC" to Num(50)),
                "watchedApps" to list(Str("e.f")),
            ),
        )
        val merged = DesiredMerge.merge(base, patch)
        val thresholds = merged["thresholds"] as Obj
        assertThat(thresholds.fields["tempHighC"]).isEqualTo(Num(50))
        assertThat(thresholds.fields["batteryLowPct"]).isEqualTo(Num(DefaultDesired.BATTERY_LOW_PCT))
        assertThat(merged["watchedApps"]).isEqualTo(list(Str("e.f")))
        assertThat(merged["version"]).isNull()
    }

    private fun v(field: String, reason: String) = ServerError.Validation(field, reason)
}
