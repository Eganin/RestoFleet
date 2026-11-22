package ru.restofleet.console.feature.shadow.domain

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue

sealed interface SaveResult {
    data class Saved(val state: ShadowState) : SaveResult

    data object NoChanges : SaveResult

    data class Invalid(val errors: Map<String, String>) : SaveResult

    data class Failed(val error: ConsoleError) : SaveResult
}

class SaveShadowForm(private val repository: ShadowRepository, private val build: BuildDesiredPatch) {
    suspend operator fun invoke(deviceId: String, original: ShadowDocument, form: ShadowForm): SaveResult =
        when (val patch = build(original, form)) {
            PatchResult.NoChanges -> SaveResult.NoChanges
            is PatchResult.Invalid -> SaveResult.Invalid(patch.errors)
            is PatchResult.Ready -> when (val saved = repository.put(deviceId, patch.patch)) {
                is Outcome.Success -> SaveResult.Saved(saved.value)
                is Outcome.Failure -> SaveResult.Failed(saved.error)
            }
        }
}

/** Режим JSON: пользователь правит merge-patch как текст. Пустой объект — нечего отправлять. */
class SaveShadowJson(private val repository: ShadowRepository, private val codec: ShadowCodec) {
    suspend operator fun invoke(deviceId: String, text: String): SaveResult = when (val parsed = codec.parse(text)) {
        is Outcome.Failure -> SaveResult.Failed(parsed.error)
        is Outcome.Success -> if (parsed.value.isEmpty) {
            SaveResult.NoChanges
        } else {
            when (val saved = repository.put(deviceId, parsed.value)) {
                is Outcome.Success -> SaveResult.Saved(saved.value)
                is Outcome.Failure -> SaveResult.Failed(saved.error)
            }
        }
    }
}

class RenderEditableJson(private val codec: ShadowCodec) {
    operator fun invoke(desired: ShadowDocument): String = codec.render(EditableDesired(desired))
}

/** Строка тени: путь, желаемое значение, сообщённое и признак расхождения («подсветка на месте»). */
data class ShadowRow(val path: String, val desired: String, val reported: String?, val changed: Boolean)

/** Плоский вид desired с отметкой, какие значения ещё не применены (по delta). */
class FlattenShadow {
    operator fun invoke(state: ShadowState): List<ShadowRow> {
        val changedPaths = leaves(state.delta).map { it.first }.toSet()
        val reported = state.reported?.let { leaves(it).toMap() }.orEmpty()
        return leaves(EditableDesired(state.desired)).map { (path, value) ->
            val isChanged = path in changedPaths
            ShadowRow(path, value, reported[path].takeIf { isChanged || it != null }, isChanged)
        }
    }

    private fun leaves(doc: ShadowDocument): List<Pair<String, String>> =
        doc.fields.entries.flatMap { (k, v) -> leaves(k, v) }

    private fun leaves(path: String, value: ShadowValue): List<Pair<String, String>> = when (value) {
        is ShadowValue.Obj -> value.fields.entries.flatMap { (k, v) -> leaves("$path.$k", v) }
        else -> listOf(path to text(value))
    }

    private fun text(value: ShadowValue): String = when (value) {
        is ShadowValue.Num -> if (value.value % 1.0 == 0.0) value.value.toLong().toString() else value.value.toString()
        is ShadowValue.Str -> value.value
        is ShadowValue.Bool -> value.value.toString()
        is ShadowValue.List -> value.items.joinToString(", ") { item ->
            (item as? ShadowValue.Obj)?.fields?.let { f -> (f["id"] as? ShadowValue.Str)?.value ?: text(item) } ?: text(item)
        }
        is ShadowValue.Obj -> value.fields.entries.joinToString(", ") { (k, v) -> "$k=${text(v)}" }
    }
}
