package ru.restofleet.agent.di

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Release (решение 140): привилегированной поверхности нет. Ни Capability Lab (реш. 275), ни вкладки Device Owner с её
 * ViewModel, ни ручного запуска команд Lab, ни самовыдачи разрешений, ни подтягивания хэша в графе не регистрируется;
 * защищает это `PrivilegedSurfaceReleaseTest`. Одноимённый модуль debug лежит в `src/debug`.
 */
val privilegedModule: Module = module { }
