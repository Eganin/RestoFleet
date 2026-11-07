package ru.restofleet.agent.data.di

import org.koin.core.qualifier.named

/** Диспетчер фоновой работы агента (ввод-вывод, цикл телеметрии, подготовка звука); значение задаёт `agent:app`. */
val AGENT_DISPATCHER = named("agentDispatcher")
