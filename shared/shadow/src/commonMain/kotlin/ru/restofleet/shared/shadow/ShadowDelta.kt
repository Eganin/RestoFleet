package ru.restofleet.shared.shadow

/**
 * Что из [desired] ещё не отражено в [reported]: ключи `desired`, значение которых в `reported` отсутствует или
 * отличается. Объекты сравниваются рекурсивно (в дельту попадают только расходящиеся листья), списки — целиком,
 * элементы списков корня — с умолчаниями [ShadowDefaults]; при расхождении в дельту идёт значение `desired` как есть,
 * при несовпадении типов — целиком. Ключи, которые есть только в `reported`, в дельту не попадают. Служебные ключи
 * [ShadowKeys.METADATA] игнорируются только в корне. Пустая дельта — тень применена.
 */
fun delta(desired: ShadowDocument, reported: ShadowDocument): ShadowDocument =
    ShadowDocument(diff(desired.fields - ShadowKeys.METADATA, reported.fields, ShadowDefaults.LIST_ITEMS))

private fun diff(
    desired: Map<String, ShadowValue>,
    reported: Map<String, ShadowValue>,
    listItemDefaults: Map<String, Map<String, ShadowValue>> = emptyMap(),
): Map<String, ShadowValue> = buildMap {
    desired.forEach { (key, want) ->
        val have = reported[key]
        val defaults = listItemDefaults[key]
        when {
            want.withItemDefaults(defaults) == have?.withItemDefaults(defaults) -> Unit
            want is ShadowValue.Obj && have is ShadowValue.Obj ->
                diff(want.fields, have.fields).takeIf { it.isNotEmpty() }?.let { put(key, ShadowValue.Obj(it)) }
            else -> put(key, want)
        }
    }
}

/** Элементы-объекты списка с недостающими полями из [defaults]; явные значения элемента сохраняются. */
private fun ShadowValue.withItemDefaults(defaults: Map<String, ShadowValue>?): ShadowValue =
    if (defaults == null || this !is ShadowValue.List) {
        this
    } else {
        ShadowValue.List(items.map { if (it is ShadowValue.Obj) ShadowValue.Obj(defaults + it.fields) else it })
    }
