package ru.restofleet.console.core.decompose

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate

/** ViewModel живёт в `InstanceKeeper` компонента: переживает смену конфигурации, уничтожается вместе со стеком. */
inline fun <reified T : InstanceKeeper.Instance> ComponentContext.retainedViewModel(
    key: Any = T::class,
    noinline factory: () -> T,
): T = instanceKeeper.getOrCreate(key, factory)
