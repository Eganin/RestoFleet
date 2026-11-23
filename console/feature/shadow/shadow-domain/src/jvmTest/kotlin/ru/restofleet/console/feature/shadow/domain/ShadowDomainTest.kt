package ru.restofleet.console.feature.shadow.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue

class ShadowDomainTest {
    private fun obj(vararg p: Pair<String, ShadowValue>) = ShadowValue.Obj(mapOf(*p))
    private fun num(v: Number) = ShadowValue.Num(v.toDouble())

    private val desired = ShadowDocument(
        mapOf(
            "version" to num(13),
            "telemetry" to obj("intervalSec" to num(15)),
            "thresholds" to obj("batteryLowPct" to num(20), "tempHighC" to num(45.5), "fgAbsentMin" to num(10)),
            "watchedApps" to ShadowValue.List(listOf(ShadowValue.Str("com.android.chrome"))),
            "probes" to ShadowValue.List(listOf(obj("id" to ShadowValue.Str("api"), "intervalSec" to num(30)))),
        ),
    )
    private val reported = ShadowDocument(mapOf("version" to num(12), "telemetry" to obj("intervalSec" to num(30))))
    private val delta = ShadowDocument(mapOf("telemetry" to obj("intervalSec" to num(15))))

    private fun state(pending: Boolean = true, delta: ShadowDocument = this.delta, reported: ShadowDocument? = this.reported) =
        ShadowState("d", desired, 13, 1L, reported, 12L, emptyList(), delta, pending, null, 5L)

    private class Repo(var result: Outcome<ShadowState, ConsoleError>) : ShadowRepository {
        var patch: ShadowDocument? = null
        override suspend fun load(deviceId: String) = result
        override suspend fun put(deviceId: String, patch: ShadowDocument): Outcome<ShadowState, ConsoleError> {
            this.patch = patch
            return result
        }
    }

    private class Codec(val parsed: Outcome<ShadowDocument, ConsoleError>) : ShadowCodec {
        override fun render(document: ShadowDocument) = "keys=" + document.fields.keys.sorted().joinToString(",")
        override fun parse(text: String) = parsed
    }

    @Test
    fun `form is read from desired and blank when a value is absent`() {
        val form = ShadowForm.from(desired)
        assertThat(form).isEqualTo(ShadowForm("15", "20", "45.5", "10", listOf("com.android.chrome")))
        assertThat(ShadowForm.from(ShadowDocument.EMPTY)).isEqualTo(ShadowForm("", "", "", "", emptyList()))
    }

    @Test
    fun `validation table`() {
        val ok = ShadowForm.from(desired)
        val validate = ValidateShadowForm()
        assertThat(validate(ok)).isEmpty()
        val rows = listOf(
            ok.copy(intervalSec = "9") to mapOf(ShadowFields.INTERVAL to ShadowErrors.OUT_OF_RANGE),
            ok.copy(intervalSec = "3601") to mapOf(ShadowFields.INTERVAL to ShadowErrors.OUT_OF_RANGE),
            ok.copy(intervalSec = "10") to emptyMap(),
            ok.copy(intervalSec = "3600") to emptyMap(),
            ok.copy(intervalSec = "abc") to mapOf(ShadowFields.INTERVAL to ShadowErrors.NOT_A_NUMBER),
            ok.copy(intervalSec = "") to emptyMap(),
            ok.copy(batteryLowPct = "0") to mapOf(ShadowFields.BATTERY to ShadowErrors.OUT_OF_RANGE),
            ok.copy(batteryLowPct = "100") to mapOf(ShadowFields.BATTERY to ShadowErrors.OUT_OF_RANGE),
            ok.copy(batteryLowPct = "1") to emptyMap(),
            ok.copy(tempHighC = "29.9") to mapOf(ShadowFields.TEMP to ShadowErrors.OUT_OF_RANGE),
            ok.copy(tempHighC = "x") to mapOf(ShadowFields.TEMP to ShadowErrors.NOT_A_NUMBER),
            ok.copy(fgAbsentMin = "181") to mapOf(ShadowFields.FG_ABSENT to ShadowErrors.OUT_OF_RANGE),
            ok.copy(watchedApps = listOf("not a package")) to mapOf(ShadowFields.APPS to ShadowErrors.INVALID_APP),
            ok.copy(watchedApps = listOf("")) to mapOf(ShadowFields.APPS to ShadowErrors.INVALID_APP),
        )
        rows.forEach { (form, expected) -> assertThat(validate(form)).isEqualTo(expected) }
    }

    @Test
    fun `patch contains only changed fields and replaces lists`() {
        val build = BuildDesiredPatch(ValidateShadowForm())
        val form = ShadowForm.from(desired)

        assertThat(build(desired, form)).isEqualTo(PatchResult.NoChanges)
        val changed = build(desired, form.copy(intervalSec = "60", tempHighC = "50", watchedApps = listOf("a.b", "a.b", " c.d ")))
        val patch = (changed as PatchResult.Ready).patch
        assertThat(patch.fields.keys).containsExactly("telemetry", "thresholds", "watchedApps")
        assertThat((patch["thresholds"] as ShadowValue.Obj).fields).containsExactly("tempHighC", num(50))
        assertThat((patch["telemetry"] as ShadowValue.Obj).fields).containsExactly("intervalSec", num(60))
        assertThat(patch["watchedApps"]).isEqualTo(ShadowValue.List(listOf(ShadowValue.Str("a.b"), ShadowValue.Str("c.d"))))
        assertThat(build(desired, form.copy(intervalSec = "1"))).isEqualTo(PatchResult.Invalid(mapOf(ShadowFields.INTERVAL to ShadowErrors.OUT_OF_RANGE)))
        val fromEmpty = build(ShadowDocument.EMPTY, ShadowForm("30", "", "", "", emptyList()))
        assertThat((fromEmpty as PatchResult.Ready).patch.fields.keys).containsExactly("telemetry")
        assertThat(build(desired, form.copy(watchedApps = emptyList()))).isInstanceOf(PatchResult.Ready::class.java)
    }

    @Test
    fun `save form covers no changes, invalid, success and failure`() = runTest {
        val repo = Repo(Outcome.Success(state(pending = false)))
        val save = SaveShadowForm(repo, BuildDesiredPatch(ValidateShadowForm()))
        val form = ShadowForm.from(desired)

        assertThat(save("d", desired, form)).isEqualTo(SaveResult.NoChanges)
        assertThat(save("d", desired, form.copy(intervalSec = "x"))).isInstanceOf(SaveResult.Invalid::class.java)
        assertThat(repo.patch).isNull()
        assertThat(save("d", desired, form.copy(intervalSec = "20"))).isInstanceOf(SaveResult.Saved::class.java)
        assertThat(repo.patch).isNotNull()
        repo.result = Outcome.Failure(ConsoleError.Validation("x", "bad"))
        assertThat(save("d", desired, form.copy(intervalSec = "20"))).isEqualTo(SaveResult.Failed(ConsoleError.Validation("x", "bad")))
    }

    @Test
    fun `save json covers parse failure, empty patch, success and server failure`() = runTest {
        val repo = Repo(Outcome.Success(state()))
        val bad = ConsoleError.InvalidInput("json", "oops")
        assertThat(SaveShadowJson(repo, Codec(Outcome.Failure(bad)))("d", "{")).isEqualTo(SaveResult.Failed(bad))
        assertThat(SaveShadowJson(repo, Codec(Outcome.Success(ShadowDocument.EMPTY)))("d", "{}")).isEqualTo(SaveResult.NoChanges)
        assertThat(repo.patch).isNull()
        val doc = ShadowDocument(mapOf("telemetry" to obj("intervalSec" to num(20))))
        assertThat(SaveShadowJson(repo, Codec(Outcome.Success(doc)))("d", "x")).isInstanceOf(SaveResult.Saved::class.java)
        assertThat(repo.patch).isEqualTo(doc)
        repo.result = Outcome.Failure(ConsoleError.Network)
        assertThat(SaveShadowJson(repo, Codec(Outcome.Success(doc)))("d", "x")).isEqualTo(SaveResult.Failed(ConsoleError.Network))
    }

    @Test
    fun `editable desired drops service keys and renders through the codec`() {
        assertThat(EditableDesired(desired).fields).doesNotContainKey("version")
        assertThat(RenderEditableJson(Codec(Outcome.Success(ShadowDocument.EMPTY)))(desired))
            .isEqualTo("keys=probes,telemetry,thresholds,watchedApps")
    }

    @Test
    fun `flatten marks rows that are not applied yet`() {
        val rows = FlattenShadow()(state()).associateBy { it.path }

        assertThat(rows["telemetry.intervalSec"]).isEqualTo(ShadowRow("telemetry.intervalSec", "15", "30", true))
        assertThat(rows["thresholds.tempHighC"]).isEqualTo(ShadowRow("thresholds.tempHighC", "45.5", null, false))
        assertThat(rows["watchedApps"]!!.desired).isEqualTo("com.android.chrome")
        assertThat(rows["probes"]!!.desired).isEqualTo("api")
        assertThat(rows).doesNotContainKey("version")
        val applied = FlattenShadow()(state(pending = false, delta = ShadowDocument.EMPTY, reported = desired)).associateBy { it.path }
        assertThat(applied.values.none { it.changed }).isTrue()
        assertThat(applied["telemetry.intervalSec"]!!.reported).isEqualTo("15")
        val unreported = FlattenShadow()(state(reported = null)).first { it.path == "telemetry.intervalSec" }
        assertThat(unreported.reported).isNull()
        assertThat(unreported.changed).isTrue()
    }

    @Test
    fun `flatten renders odd values`() {
        val odd = ShadowDocument(
            mapOf(
                "flag" to ShadowValue.Bool(true),
                "mixed" to ShadowValue.List(listOf(num(1), obj("k" to ShadowValue.Str("v")))),
            ),
        )
        val rows = FlattenShadow()(state().copy(desired = odd, delta = ShadowDocument.EMPTY)).associateBy { it.path }
        assertThat(rows["flag"]!!.desired).isEqualTo("true")
        assertThat(rows["mixed"]!!.desired).isEqualTo("1, k=v")
    }

    @Test
    fun `load delegates`() = runTest {
        assertThat(LoadShadow(Repo(Outcome.Success(state())))("d")).isEqualTo(Outcome.Success(state()))
    }

    /** V-4: события тени в WS нет — после сохранения тень перечитывается, пока агент не применит desired. */
    @Test
    fun `await applied polls until the shadow is no longer pending and gives up after the limit`() = runTest {
        val repo = Repo(Outcome.Success(state(pending = true)))
        val await = AwaitShadowApplied(repo, intervalMs = 1_000, attempts = 3)
        val seen = mutableListOf<Boolean>()
        val job = launch { await("d").collect { seen += it.pending } }
        advanceTimeBy(1_001)
        repo.result = Outcome.Failure(ConsoleError.Network)
        advanceTimeBy(1_000)
        repo.result = Outcome.Success(state(pending = false))
        advanceTimeBy(1_000)
        // после «применено» опрос заканчивается сам, не дожидаясь лимита попыток
        assertThat(job.isCompleted).isTrue()
        assertThat(seen).containsExactly(true, false).inOrder()

        val stuck = mutableListOf<Boolean>()
        repo.result = Outcome.Success(state(pending = true))
        AwaitShadowApplied(repo, intervalMs = 1_000, attempts = 3)("d").collect { stuck += it.pending }
        assertThat(stuck).containsExactly(true, true, true)
    }
}
