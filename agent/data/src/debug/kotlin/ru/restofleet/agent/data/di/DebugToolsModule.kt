package ru.restofleet.agent.data.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.restofleet.agent.data.mqtt.DebugForeignTopicPublisher
import ru.restofleet.agent.data.mqtt.MqttBrokerSession
import ru.restofleet.agent.domain.port.ForeignTopicPublisher

/** Только debug: адаптер проверки ACL (M2) поверх MQTT-сессии. В release этого файла в модуле нет. */
val debugToolsModule: Module = module {
    single<ForeignTopicPublisher> { DebugForeignTopicPublisher(get<MqttBrokerSession>()) }
}
