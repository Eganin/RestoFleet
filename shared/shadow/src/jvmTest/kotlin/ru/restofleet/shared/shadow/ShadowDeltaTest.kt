package ru.restofleet.shared.shadow

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import ru.restofleet.shared.shadow.ShadowValue.Bool
import ru.restofleet.shared.shadow.ShadowValue.Num
import ru.restofleet.shared.shadow.ShadowValue.Obj
import ru.restofleet.shared.shadow.ShadowValue.Str

class ShadowDeltaTest {

    private fun doc(vararg fields: Pair<String, ShadowValue>) = ShadowDocument(mapOf(*fields))
    private fun obj(vararg fields: Pair<String, ShadowValue>) = Obj(mapOf(*fields))
    private fun list(vararg items: ShadowValue) = ShadowValue.List(items.toList())
    private fun thresholds(battery: Num, temp: Num, fgAbsent: Num) =
        obj("batteryLowPct" to battery, "tempHighC" to temp, "fgAbsentMin" to fgAbsent)

    private val probeApi = obj("id" to Str("api"), "url" to Str("https://a"), "intervalSec" to Num(30))
    private val probeKds = obj("id" to Str("kds"), "url" to Str("https://k"), "intervalSec" to Num(60))

    private val desired = doc(
        "version" to Num(12),
        "telemetry" to obj("intervalSec" to Num(30)),
        "thresholds" to obj("batteryLowPct" to Num(20), "tempHighC" to Num(45.0), "fgAbsentMin" to Num(10)),
        "watchedApps" to list(Str("com.android.chrome")),
        "probes" to list(probeApi, probeKds),
    )

    private data class Case(val name: String, val reported: ShadowDocument, val expected: ShadowDocument)

    private val applied = ShadowDocument(
        desired.fields + mapOf(
            "appliedVersion" to Num(12),
            "appliedAt" to Num(1_793_865_605_000),
            "errors" to list(),
        ),
    )

    private val cases = listOf(
        Case("fully applied, metadata ignored", applied, ShadowDocument.EMPTY),
        Case(
            "empty reported -> everything except version",
            ShadowDocument.EMPTY,
            ShadowDocument(desired.fields - "version"),
        ),
        Case(
            "nested scalar differs -> only that leaf",
            ShadowDocument(applied.fields + ("telemetry" to obj("intervalSec" to Num(60)))),
            doc("telemetry" to obj("intervalSec" to Num(30))),
        ),
        Case(
            "one threshold differs -> nested object with one key",
            ShadowDocument(
                applied.fields +
                    ("thresholds" to thresholds(battery = Num(15), temp = Num(45), fgAbsent = Num(10))),
            ),
            doc("thresholds" to obj("batteryLowPct" to Num(20))),
        ),
        Case(
            "list compared whole: dropped probe -> whole desired list",
            ShadowDocument(applied.fields + ("probes" to list(probeApi))),
            doc("probes" to list(probeApi, probeKds)),
        ),
        Case(
            "list order matters",
            ShadowDocument(applied.fields + ("probes" to list(probeKds, probeApi))),
            doc("probes" to list(probeApi, probeKds)),
        ),
        Case(
            "type mismatch -> desired value whole",
            ShadowDocument(applied.fields + ("telemetry" to Num(30))),
            doc("telemetry" to obj("intervalSec" to Num(30))),
        ),
        Case(
            "extra reported keys are not in delta",
            ShadowDocument(applied.fields + ("agentOnly" to Bool(true))),
            ShadowDocument.EMPTY,
        ),
        Case(
            "integer and decimal forms are equal",
            ShadowDocument(
                applied.fields +
                    ("thresholds" to thresholds(battery = Num(20.0), temp = Num(45L), fgAbsent = Num(10))),
            ),
            ShadowDocument.EMPTY,
        ),
    )

    @Test
    fun deltaTable() {
        cases.forEach { case ->
            assertWithMessage(case.name).that(delta(desired, case.reported)).isEqualTo(case.expected)
        }
    }

    @Test
    fun metadataKeysAreIgnoredOnlyAtRoot() {
        val d = doc("nested" to obj("version" to Num(2), "errors" to list()))
        val r = doc("nested" to obj("version" to Num(1), "errors" to list()))
        assertThat(delta(d, r)).isEqualTo(doc("nested" to obj("version" to Num(2))))
    }

    @Test
    fun desiredMetadataNeverAppearsInDelta() {
        val d = doc("version" to Num(3), "appliedVersion" to Num(3), "appliedAt" to Num(1), "errors" to list(Str("x")))
        assertThat(delta(d, ShadowDocument.EMPTY).isEmpty).isTrue()
    }

    @Test
    fun documentAccessors() {
        assertThat(desired["version"]).isEqualTo(Num(12))
        assertThat(desired["missing"]).isNull()
        assertThat(desired.isEmpty).isFalse()
        assertThat(ShadowDocument().isEmpty).isTrue()
        assertThat(ShadowKeys.METADATA).containsExactly("version", "appliedVersion", "appliedAt", "errors")
    }

    @Test
    fun numberConstructorsAgree() {
        assertThat(Num(7)).isEqualTo(Num(7L))
        assertThat(Num(7L)).isEqualTo(Num(7.0))
        assertThat(Num(7.5)).isNotEqualTo(Num(7))
    }

    @Test
    fun `probe fields left at their protocol default on one side make no delta`() {
        // V-2: desired без необязательного reference, агент эхом пишет reference: false — тень применена
        val bare = obj("id" to Str("api"), "url" to Str("https://a"), "intervalSec" to Num(5))
        val echoed = obj(
            "id" to Str("api"), "url" to Str("https://a"), "intervalSec" to Num(5), "reference" to Bool(false),
        )

        assertThat(delta(doc("probes" to list(bare)), doc("probes" to list(echoed)))).isEqualTo(ShadowDocument.EMPTY)
        assertThat(delta(doc("probes" to list(echoed)), doc("probes" to list(bare)))).isEqualTo(ShadowDocument.EMPTY)
    }

    @Test
    fun `a probe value other than the default still makes a delta with the desired list as sent`() {
        val bare = obj("id" to Str("api"), "url" to Str("https://a"))
        val reference = obj("id" to Str("api"), "url" to Str("https://a"), "reference" to Bool(true))

        assertThat(delta(doc("probes" to list(reference)), doc("probes" to list(bare))))
            .isEqualTo(doc("probes" to list(reference)))
        assertThat(delta(doc("probes" to list(bare)), doc("probes" to list(reference))))
            .isEqualTo(doc("probes" to list(bare)))
    }

    @Test
    fun `defaults apply only to probe items at the root`() {
        val nested = doc("x" to obj("probes" to list(obj("id" to Str("api")))))
        val echoed = doc("x" to obj("probes" to list(obj("id" to Str("api"), "reference" to Bool(false)))))

        assertThat(delta(nested, echoed)).isEqualTo(nested)
        assertThat(ShadowDefaults.LIST_ITEMS.keys).containsExactly("probes")
    }
}
