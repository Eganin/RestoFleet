package ru.restofleet.console.feature.shadow.ui.viewmodel

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.feature.shadow.domain.AwaitShadowApplied
import ru.restofleet.console.feature.shadow.domain.BuildDesiredPatch
import ru.restofleet.console.feature.shadow.domain.LoadShadow
import ru.restofleet.console.feature.shadow.domain.RenderEditableJson
import ru.restofleet.console.feature.shadow.domain.SaveShadowForm
import ru.restofleet.console.feature.shadow.domain.SaveShadowJson
import ru.restofleet.console.feature.shadow.domain.ShadowCodec
import ru.restofleet.console.feature.shadow.domain.ShadowErrors
import ru.restofleet.console.feature.shadow.domain.ShadowFields
import ru.restofleet.console.feature.shadow.domain.ShadowForm
import ru.restofleet.console.feature.shadow.domain.ShadowRepository
import ru.restofleet.console.feature.shadow.domain.ShadowState
import ru.restofleet.console.feature.shadow.domain.ValidateShadowForm
import ru.restofleet.console.feature.shadow.ui.viewmodel.mapper.ShadowUiStateMapper
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue

private fun obj(vararg p: Pair<String, ShadowValue>) = ShadowValue.Obj(mapOf(*p))
private fun num(v: Number) = ShadowValue.Num(v.toDouble())

private val desired = ShadowDocument(
    mapOf(
        "version" to num(13),
        "telemetry" to obj("intervalSec" to num(15)),
        "thresholds" to obj("batteryLowPct" to num(20), "tempHighC" to num(45), "fgAbsentMin" to num(10)),
        "watchedApps" to ShadowValue.List(listOf(ShadowValue.Str("com.android.chrome"))),
    ),
)

private fun shadow(pending: Boolean = true, published: Boolean? = null, errors: List<String> = emptyList(), reportedVersion: Long? = 12L) = ShadowState(
    "d1", desired, 13, 1_000L, ShadowDocument(mapOf("telemetry" to obj("intervalSec" to num(30)))), reportedVersion, errors,
    if (pending) ShadowDocument(mapOf("telemetry" to obj("intervalSec" to num(15)))) else ShadowDocument.EMPTY, pending, published, 43_000L,
)

class ShadowTest {
    private val reducer = ShadowReducer()
    private val mapper = ShadowUiStateMapper()

    @Test
    fun `reducer load, edit and cancel`() {
        val loaded = reducer.reduce(ShadowAction.Loaded(shadow()), ShadowSession())
        assertThat(loaded.loading).isFalse()
        assertThat(reducer.reduce(ShadowAction.LoadStarted, loaded).loading).isFalse()
        assertThat(reducer.reduce(ShadowAction.LoadStarted, ShadowSession()).loading).isTrue()
        assertThat(reducer.reduce(ShadowAction.LoadFailed(ConsoleError.Network), loaded).error).isEqualTo(ConsoleError.Network)

        val form = ShadowForm.from(desired)
        val editing = reducer.reduce(ShadowAction.FormOpened(form), loaded)
        assertThat(editing.mode).isEqualTo(ShadowMode.Form)
        assertThat(reducer.reduce(ShadowAction.EditCancelled, editing).mode).isEqualTo(ShadowMode.View)
        assertThat(reducer.reduce(ShadowAction.EditCancelled, editing).form).isNull()
        val json = reducer.reduce(ShadowAction.JsonOpened("{}"), loaded)
        assertThat(json.mode).isEqualTo(ShadowMode.Json)
        assertThat(reducer.reduce(ShadowAction.JsonChanged("{\"a\":1}"), json).jsonText).isEqualTo("{\"a\":1}")
    }

    @Test
    fun `reducer field edits clear their own error`() {
        val form = ShadowForm.from(desired)
        val s = ShadowSession(form = form, mode = ShadowMode.Form, fieldErrors = mapOf(ShadowFields.INTERVAL to "x", ShadowFields.TEMP to "y"))
        val edited = reducer.reduce(ShadowAction.FieldChanged(ShadowFields.INTERVAL, "60"), s)
        assertThat(edited.form!!.intervalSec).isEqualTo("60")
        assertThat(edited.fieldErrors.keys).containsExactly(ShadowFields.TEMP)
        assertThat(reducer.reduce(ShadowAction.FieldChanged(ShadowFields.BATTERY, "15"), s).form!!.batteryLowPct).isEqualTo("15")
        assertThat(reducer.reduce(ShadowAction.FieldChanged(ShadowFields.TEMP, "50"), s).form!!.tempHighC).isEqualTo("50")
        assertThat(reducer.reduce(ShadowAction.FieldChanged(ShadowFields.FG_ABSENT, "5"), s).form!!.fgAbsentMin).isEqualTo("5")
        assertThat(reducer.reduce(ShadowAction.FieldChanged("unknown", "5"), s).form).isEqualTo(form)
        val apps = reducer.reduce(ShadowAction.AppAdded(" a.b "), s.copy(fieldErrors = mapOf(ShadowFields.APPS to "bad")))
        assertThat(apps.form!!.watchedApps).containsExactly("com.android.chrome", "a.b").inOrder()
        assertThat(apps.fieldErrors).isEmpty()
        assertThat(reducer.reduce(ShadowAction.AppAdded("com.android.chrome"), s).form!!.watchedApps).hasSize(1)
        assertThat(reducer.reduce(ShadowAction.AppAdded("  "), s).form!!.watchedApps).hasSize(1)
        assertThat(reducer.reduce(ShadowAction.AppRemoved("com.android.chrome"), s).form!!.watchedApps).isEmpty()
    }

    @Test
    fun `reducer save outcomes`() {
        val editing = ShadowSession(state = shadow(), mode = ShadowMode.Form, form = ShadowForm.from(desired))
        assertThat(reducer.reduce(ShadowAction.SaveStarted, editing).saving).isTrue()
        val saved = reducer.reduce(ShadowAction.Saved(shadow(published = true)), editing.copy(saving = true))
        assertThat(saved.mode).isEqualTo(ShadowMode.View)
        assertThat(saved.notice).isEqualTo(Notice.Saved)
        assertThat(reducer.reduce(ShadowAction.Saved(shadow(published = false)), editing).notice).isEqualTo(Notice.SavedNotPublished)
        assertThat(reducer.reduce(ShadowAction.NothingToSave, editing.copy(saving = true)).notice).isEqualTo(Notice.NoChanges)
        val rejected = reducer.reduce(ShadowAction.Rejected(mapOf(ShadowFields.INTERVAL to ShadowErrors.OUT_OF_RANGE)), editing.copy(saving = true))
        assertThat(rejected.saving).isFalse()
        assertThat(rejected.fieldErrors).containsKey(ShadowFields.INTERVAL)
        assertThat(reducer.reduce(ShadowAction.SaveFailed(ConsoleError.Network), editing.copy(saving = true)).saveError).isEqualTo(ConsoleError.Network)
    }

    @Test
    fun `mapper view like the mockup`() {
        val ui = mapper.map(ShadowSession(state = shadow(), loading = false))

        assertThat(ui.header).isEqualTo("desired v13 · reported v12 · delta · 42 с")
        assertThat(ui.banner).contains("Ожидает применения")
        assertThat(ui.bannerPending).isTrue()
        assertThat(ui.rows.first { it.path == "telemetry.intervalSec" }.text).isEqualTo("30 → 15")
        assertThat(ui.rows.first { it.path == "telemetry.intervalSec" }.changed).isTrue()
        assertThat(ui.rows.first { it.path == "thresholds.tempHighC" }.text).isEqualTo("45")
        assertThat(ui.canEdit).isTrue()

        val applied = mapper.map(ShadowSession(state = shadow(pending = false, reportedVersion = null, errors = listOf("bad value")), loading = false))
        assertThat(applied.header).isEqualTo("desired v13 · reported — · delta пуста")
        assertThat(applied.banner).isEqualTo("Применено на устройстве")
        assertThat(applied.deviceErrors).isEqualTo("Устройство сообщило: bad value")
    }

    @Test
    fun `mapper form fields, errors, notices and json errors`() {
        val form = ShadowForm.from(desired)
        val editing = ShadowSession(
            state = shadow(), loading = false, mode = ShadowMode.Form, form = form,
            fieldErrors = mapOf(
                ShadowFields.INTERVAL to ShadowErrors.OUT_OF_RANGE, ShadowFields.TEMP to ShadowErrors.NOT_A_NUMBER,
                ShadowFields.APPS to ShadowErrors.INVALID_APP, ShadowFields.BATTERY to "weird",
            ),
        )
        val ui = mapper.map(editing)
        assertThat(ui.fields.map { it.field }).containsExactly(
            ShadowFields.INTERVAL, ShadowFields.BATTERY, ShadowFields.TEMP, ShadowFields.FG_ABSENT,
        ).inOrder()
        assertThat(ui.fields[0].error).isEqualTo("Вне допустимого диапазона")
        assertThat(ui.fields[2].error).isEqualTo("Нужно число")
        assertThat(ui.fields[1].error).isEqualTo("weird")
        assertThat(ui.fields[3].error).isNull()
        assertThat(ui.appsError).isEqualTo("Неверное имя пакета")
        assertThat(ui.apps).containsExactly("com.android.chrome")
        assertThat(ui.canEdit).isFalse()

        assertThat(mapper.map(ShadowSession(notice = Notice.Saved)).noticeText).contains("Сохранено")
        assertThat(mapper.map(ShadowSession(notice = Notice.SavedNotPublished)).noticeText).contains("брокер недоступен")
        assertThat(mapper.map(ShadowSession(notice = Notice.NoChanges)).noticeText).isEqualTo("Изменений нет")
        val json = mapper.map(ShadowSession(mode = ShadowMode.Json, jsonText = "{", saveError = ConsoleError.InvalidInput("json", "eof")))
        assertThat(json.jsonError).isEqualTo("Некорректный JSON: eof")
        assertThat(json.saveErrorText).isNull()
        fun err(e: ConsoleError) = mapper.map(ShadowSession(saveError = e)).saveErrorText
        assertThat(err(ConsoleError.Validation("telemetry", "bad"))).isEqualTo("Сервер отклонил значение (telemetry): bad")
        assertThat(err(ConsoleError.Validation(null, "bad"))).isEqualTo("Сервер отклонил значение: bad")
        assertThat(err(ConsoleError.Conflict(null))).contains("выведено")
        assertThat(err(ConsoleError.Network)).isEqualTo("Нет связи с сервером")
        assertThat(err(ConsoleError.NotFound)).isEqualTo("Устройство не найдено")
        assertThat(err(ConsoleError.Forbidden)).isEqualTo("Недостаточно прав")
        assertThat(err(ConsoleError.Server(500, null))).isEqualTo("Не удалось выполнить")
        assertThat(mapper.map(ShadowSession(error = ConsoleError.Network)).errorText).isEqualTo("Нет связи с сервером")
        assertThat(mapper.map(ShadowSession()).header).isEmpty()
    }

    private class Repo : ShadowRepository {
        var loadResult: Outcome<ShadowState, ConsoleError> = Outcome.Success(shadow())
        var putResult: Outcome<ShadowState, ConsoleError> = Outcome.Success(shadow(pending = true, published = true))
        var patch: ShadowDocument? = null
        override suspend fun load(deviceId: String) = loadResult
        override suspend fun put(deviceId: String, patch: ShadowDocument): Outcome<ShadowState, ConsoleError> {
            this.patch = patch
            return putResult
        }
    }

    private class Codec : ShadowCodec {
        override fun render(document: ShadowDocument) = "{rendered:${document.fields.keys.sorted().joinToString("|")}}"
        override fun parse(text: String): Outcome<ShadowDocument, ConsoleError> =
            if (text == "{") {
                Outcome.Failure(ConsoleError.InvalidInput("json", "eof"))
            } else {
                Outcome.Success(ShadowDocument(mapOf("telemetry" to obj("intervalSec" to num(20)))))
            }
    }

    private fun vm(repo: Repo, dispatcher: TestDispatcher = UnconfinedTestDispatcher()): ShadowViewModel {
        val codec = Codec()
        return ShadowViewModel(
            "d1", LoadShadow(repo), SaveShadowForm(repo, BuildDesiredPatch(ValidateShadowForm())), SaveShadowJson(repo, codec),
            RenderEditableJson(codec), AwaitShadowApplied(repo, intervalMs = 2_000, attempts = 5),
            CoroutineScope(SupervisorJob() + dispatcher),
        )
    }

    /** V-4: после «Сохранить» экран сам показывает, что агент применил desired, без повторного открытия карточки. */
    @Test
    fun `after save the screen follows the shadow until the agent applies it`() = runTest {
        val repo = Repo()
        val vm = vm(repo, UnconfinedTestDispatcher(testScheduler))
        vm.onIntent(ShadowIntent.User.StartEdit)
        vm.onIntent(ShadowIntent.User.FieldChanged(ShadowFields.INTERVAL, "60"))
        vm.onIntent(ShadowIntent.User.Save)
        assertThat(vm.uiState.value.noticeText).contains("Сохранено")
        val pendingBefore = mapper.map(ShadowSession(state = shadow(pending = true))).header

        repo.loadResult = Outcome.Success(shadow(pending = false, reportedVersion = 13L))
        advanceTimeBy(2_001)

        assertThat(vm.uiState.value.header).isNotEqualTo(pendingBefore)
        assertThat(vm.uiState.value.header).isEqualTo(mapper.map(ShadowSession(state = shadow(pending = false, reportedVersion = 13L))).header)
        assertThat(vm.uiState.value.noticeText).contains("Сохранено")
        vm.onDestroy()
    }

    @Test
    fun `loads on start and refreshes after a failure`() = runTest {
        val repo = Repo().apply { loadResult = Outcome.Failure(ConsoleError.Network) }
        val vm = vm(repo)
        assertThat(vm.uiState.value.errorText).isEqualTo("Нет связи с сервером")
        repo.loadResult = Outcome.Success(shadow())

        vm.onIntent(ShadowIntent.User.Refresh)

        assertThat(vm.uiState.value.errorText).isNull()
        assertThat(vm.uiState.value.rows).isNotEmpty()
        vm.onDestroy()
    }

    @Test
    fun `form edit validates before the request and sends only changes`() = runTest {
        val repo = Repo()
        val vm = vm(repo)
        vm.onIntent(ShadowIntent.User.StartEdit)
        vm.onIntent(ShadowIntent.User.FieldChanged(ShadowFields.INTERVAL, "5"))

        vm.onIntent(ShadowIntent.User.Save)
        assertThat(repo.patch).isNull()
        assertThat(vm.uiState.value.fields.first().error).isEqualTo("Вне допустимого диапазона")

        vm.onIntent(ShadowIntent.User.FieldChanged(ShadowFields.INTERVAL, "60"))
        vm.onIntent(ShadowIntent.User.Save)

        assertThat(repo.patch!!.fields.keys).containsExactly("telemetry")
        assertThat(vm.uiState.value.mode).isEqualTo(ShadowMode.View)
        assertThat(vm.uiState.value.noticeText).contains("Сохранено")
        vm.onDestroy()
    }

    @Test
    fun `saving without changes and server rejection`() = runTest {
        val repo = Repo()
        val vm = vm(repo)
        vm.onIntent(ShadowIntent.User.StartEdit)
        vm.onIntent(ShadowIntent.User.Save)
        assertThat(vm.uiState.value.noticeText).isEqualTo("Изменений нет")
        assertThat(repo.patch).isNull()

        repo.putResult = Outcome.Failure(ConsoleError.Validation("watchedApps", "bad"))
        vm.onIntent(ShadowIntent.User.AppAdded("new.app"))
        vm.onIntent(ShadowIntent.User.Save)

        assertThat(vm.uiState.value.saveErrorText).contains("bad")
        assertThat(vm.uiState.value.saving).isFalse()
        vm.onIntent(ShadowIntent.User.Cancel)
        assertThat(vm.uiState.value.mode).isEqualTo(ShadowMode.View)
        vm.onDestroy()
    }

    @Test
    fun `json mode renders editable desired, reports broken json and sends the patch`() = runTest {
        val repo = Repo()
        val vm = vm(repo)

        vm.onIntent(ShadowIntent.User.StartJson)
        assertThat(vm.uiState.value.jsonText).isEqualTo("{rendered:telemetry|thresholds|watchedApps}")

        vm.onIntent(ShadowIntent.User.JsonChanged("{"))
        vm.onIntent(ShadowIntent.User.Save)
        assertThat(vm.uiState.value.jsonError).isEqualTo("Некорректный JSON: eof")
        assertThat(repo.patch).isNull()

        vm.onIntent(ShadowIntent.User.JsonChanged("{\"telemetry\":{}}"))
        vm.onIntent(ShadowIntent.User.Save)
        assertThat(repo.patch).isNotNull()
        assertThat(vm.uiState.value.mode).isEqualTo(ShadowMode.View)
        vm.onDestroy()
    }

    @Test
    fun `editing is impossible before the shadow is loaded and save in view mode does nothing`() = runTest {
        val repo = Repo().apply { loadResult = Outcome.Failure(ConsoleError.Network) }
        val vm = vm(repo)
        vm.onIntent(ShadowIntent.User.StartEdit)
        vm.onIntent(ShadowIntent.User.StartJson)
        vm.onIntent(ShadowIntent.User.Save)
        assertThat(vm.uiState.value.mode).isEqualTo(ShadowMode.View)
        assertThat(repo.patch).isNull()

        repo.loadResult = Outcome.Success(shadow())
        vm.onIntent(ShadowIntent.User.Refresh)
        vm.onIntent(ShadowIntent.User.Save)
        assertThat(vm.uiState.value.noticeText).isEqualTo("Изменений нет")
        vm.onDestroy()
    }
}
