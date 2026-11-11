package ru.restofleet.shared.shadow

/**
 * Что из [desired] ещё не отражено в [reported]: ключи `desired`, значение которых в `reported` отсутствует или
 * отличается. Объекты сравниваются рекурсивно (в дельту попадают только расходящиеся листья), списки — целиком;
 * при несовпадении типов берётся значение `desired` целиком. Ключи, которые есть только в `reported`, в дельту не
 * попадают. Служебные ключи [ShadowKeys.METADATA] игнорируются только в корне. Пустая дельта — тень применена.
 */
fun delta(desired: ShadowDocument, reported: ShadowDocument): ShadowDocument =
    ShadowDocument(diff(desired.fields - ShadowKeys.METADATA, reported.fields))

private fun diff(desired: Map<String, ShadowValue>, reported: Map<String, ShadowValue>): Map<String, ShadowValue> =
    buildMap {
        desired.forEach { (key, want) ->
            val have = reported[key]
            when {
                want == have -> Unit
                want is ShadowValue.Obj && have is ShadowValue.Obj ->
                    diff(want.fields, have.fields).takeIf { it.isNotEmpty() }?.let { put(key, ShadowValue.Obj(it)) }
                else -> put(key, want)
            }
        }
    }
