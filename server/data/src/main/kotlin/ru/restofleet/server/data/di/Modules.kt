package ru.restofleet.server.data.di

import com.zaxxer.hikari.HikariDataSource
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.dsl.onClose
import ru.restofleet.server.data.config.DataConfig
import ru.restofleet.server.data.db.DataSources
import ru.restofleet.server.data.db.ExposedCommandRepository
import ru.restofleet.server.data.db.ExposedCredentialRepository
import ru.restofleet.server.data.db.ExposedDatabase
import ru.restofleet.server.data.db.ExposedDeviceRepository
import ru.restofleet.server.data.db.ExposedRefreshTokenRepository
import ru.restofleet.server.data.db.ExposedRestaurantRepository
import ru.restofleet.server.data.db.ExposedSessionRepository
import ru.restofleet.server.data.db.ExposedShadowRepository
import ru.restofleet.server.data.db.ExposedTelemetryRepository
import ru.restofleet.server.data.db.ExposedTokenRepository
import ru.restofleet.server.data.db.ExposedTransactionRunner
import ru.restofleet.server.data.db.ExposedUserRepository
import ru.restofleet.server.data.health.BrokerHealthProbe
import ru.restofleet.server.data.health.DatabaseHealthProbe
import ru.restofleet.server.data.ingest.DeviceMessageDecoder
import ru.restofleet.server.data.ingest.InboundProcessor
import ru.restofleet.server.data.ingest.IngestMetrics
import ru.restofleet.server.data.ingest.IngestPipeline
import ru.restofleet.server.data.ingest.IngestProcessor
import ru.restofleet.server.data.ingest.RestPayloadDecoder
import ru.restofleet.server.data.mqtt.DynSecBrokerAccounts
import ru.restofleet.server.data.mqtt.HiveMqClient
import ru.restofleet.server.data.mqtt.HiveMqPublisher
import ru.restofleet.server.data.mqtt.MqttBackend
import ru.restofleet.server.data.mqtt.MqttTls
import ru.restofleet.server.data.security.BcryptHasher
import ru.restofleet.server.data.security.InMemoryDeviceCredentialCache
import ru.restofleet.server.data.security.SecureRandomSecrets
import ru.restofleet.server.data.security.Sha256Digest
import ru.restofleet.server.domain.auth.RefreshTokenRepository
import ru.restofleet.server.domain.auth.SessionIssuer
import ru.restofleet.server.domain.auth.UserRepository
import ru.restofleet.server.domain.auth.usecase.Login
import ru.restofleet.server.domain.auth.usecase.RefreshSession
import ru.restofleet.server.domain.auth.usecase.UpsertUser
import ru.restofleet.server.domain.command.CommandRepository
import ru.restofleet.server.domain.command.usecase.ExpireCommands
import ru.restofleet.server.domain.command.usecase.GetCommand
import ru.restofleet.server.domain.command.usecase.HandleCommandResult
import ru.restofleet.server.domain.command.usecase.SendCommand
import ru.restofleet.server.domain.common.PasswordHasher
import ru.restofleet.server.domain.common.SecretDigest
import ru.restofleet.server.domain.common.SecretGenerator
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.device.BrokerAccounts
import ru.restofleet.server.domain.device.CredentialRepository
import ru.restofleet.server.domain.device.DeviceCredentialCache
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.device.MessagePublisher
import ru.restofleet.server.domain.device.usecase.DecommissionDevice
import ru.restofleet.server.domain.device.usecase.GetDevice
import ru.restofleet.server.domain.device.usecase.ListDevices
import ru.restofleet.server.domain.health.usecase.CheckHealth
import ru.restofleet.server.domain.ingest.TelemetryRepository
import ru.restofleet.server.domain.ingest.usecase.GetEventHistory
import ru.restofleet.server.domain.ingest.usecase.GetProbeHistory
import ru.restofleet.server.domain.ingest.usecase.GetTelemetryHistory
import ru.restofleet.server.domain.ingest.usecase.IngestBatch
import ru.restofleet.server.domain.polling.usecase.AuthenticateDevice
import ru.restofleet.server.domain.polling.usecase.MarkStalePollers
import ru.restofleet.server.domain.polling.usecase.PollCommands
import ru.restofleet.server.domain.polling.usecase.PollTelemetry
import ru.restofleet.server.domain.presence.SessionRepository
import ru.restofleet.server.domain.presence.usecase.HandleStatus
import ru.restofleet.server.domain.provisioning.TokenRepository
import ru.restofleet.server.domain.provisioning.usecase.IssueProvisioningTokens
import ru.restofleet.server.domain.provisioning.usecase.ProvisionDevice
import ru.restofleet.server.domain.restaurant.RestaurantRepository
import ru.restofleet.server.domain.restaurant.usecase.CreateRestaurant
import ru.restofleet.server.domain.restaurant.usecase.GetRestaurant
import ru.restofleet.server.domain.restaurant.usecase.ListRestaurants
import ru.restofleet.server.domain.restaurant.usecase.UpdateRestaurant
import ru.restofleet.server.domain.restaurant.usecase.UpsertRestaurantByCode
import ru.restofleet.server.domain.shadow.ShadowRepository
import ru.restofleet.server.domain.shadow.usecase.GetShadow
import ru.restofleet.server.domain.shadow.usecase.HandleReported
import ru.restofleet.server.domain.shadow.usecase.UpdateDesired
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.kernel.SystemClock
import ru.restofleet.shared.kernel.UuidIdGenerator
import javax.sql.DataSource

/**
 * Use cases `server:domain` (один экземпляр на вызов). Domain не знает Koin (правило Konsist), поэтому модуль живёт
 * здесь, в пакете `di` (composition root). Требует бины [dataModule] и из `server:app`: `ProvisioningSettings`,
 * `PollingSettings`, `AuthSettings`, `AccessTokenIssuer`.
 */
val domainModule = module {
    factoryOf(::CreateRestaurant)
    factoryOf(::UpdateRestaurant)
    factoryOf(::GetRestaurant)
    factoryOf(::ListRestaurants)
    factoryOf(::UpsertRestaurantByCode)
    factoryOf(::ListDevices)
    factoryOf(::GetDevice)
    factoryOf(::DecommissionDevice)
    factoryOf(::IssueProvisioningTokens)
    factoryOf(::ProvisionDevice)
    factoryOf(::IngestBatch)
    factoryOf(::GetTelemetryHistory)
    factoryOf(::GetProbeHistory)
    factoryOf(::GetEventHistory)
    factoryOf(::HandleStatus)
    factoryOf(::UpdateDesired)
    factoryOf(::HandleReported)
    factoryOf(::GetShadow)
    factoryOf(::SendCommand)
    factoryOf(::HandleCommandResult)
    factoryOf(::ExpireCommands)
    factoryOf(::GetCommand)
    factoryOf(::AuthenticateDevice)
    factoryOf(::PollTelemetry)
    factoryOf(::PollCommands)
    factoryOf(::MarkStalePollers)
    factoryOf(::Login)
    factoryOf(::RefreshSession)
    factoryOf(::UpsertUser)
    singleOf(::SessionIssuer)
}

/**
 * Реализации портов на PostgreSQL (Exposed, Hikari) и Mosquitto (HiveMQ), конвейер ingest. Требует бин [DataConfig]
 * из `server:app`. Пул закрывается при остановке Koin; MQTT запускает и останавливает `server:app` через
 * [MqttBackend].
 */
val dataModule = module {
    single<Clock> { SystemClock }
    single<IdGenerator> { UuidIdGenerator() }

    single { DataSources.hikari(get<DataConfig>().database) } onClose { it?.close() }
    single<DataSource> { get<HikariDataSource>() }
    single { ExposedDatabase.connect(get<DataSource>()) }
    single<TransactionRunner> { ExposedTransactionRunner(get()) }

    singleOf(::ExposedRestaurantRepository) { bind<RestaurantRepository>() }
    singleOf(::ExposedDeviceRepository) { bind<DeviceRepository>() }
    singleOf(::ExposedCredentialRepository) { bind<CredentialRepository>() }
    singleOf(::ExposedTokenRepository) { bind<TokenRepository>() }
    singleOf(::ExposedSessionRepository) { bind<SessionRepository>() }
    singleOf(::ExposedShadowRepository) { bind<ShadowRepository>() }
    singleOf(::ExposedTelemetryRepository) { bind<TelemetryRepository>() }
    singleOf(::ExposedCommandRepository) { bind<CommandRepository>() }
    singleOf(::ExposedUserRepository) { bind<UserRepository>() }
    singleOf(::ExposedRefreshTokenRepository) { bind<RefreshTokenRepository>() }

    single<PasswordHasher> { BcryptHasher() }
    single<SecretGenerator> { SecureRandomSecrets() }
    single<SecretDigest> { Sha256Digest }
    single<DeviceCredentialCache> { InMemoryDeviceCredentialCache(get(), get<DataConfig>().deviceAuthCacheTtlMs) }

    single { get<DataConfig>().mqtt.let { HiveMqClient(it, it.caFile?.let(MqttTls::trustOnly)) } }
    single<MessagePublisher> { HiveMqPublisher(get(), get()) }
    single<BrokerAccounts> { DynSecBrokerAccounts(get(), get<DataConfig>().mqtt.requestTimeoutMs) }
    singleOf(::IngestMetrics)
    single { DeviceMessageDecoder(get<DataConfig>().ingest.maxPayloadBytes) }
    singleOf(::RestPayloadDecoder)
    single<InboundProcessor> { IngestProcessor(get(), get(), get(), get(), get(), get()) }
    single { IngestPipeline(get(), get<DataConfig>().ingest, get()) }
    single { MqttBackend(get(), get()) }

    single { DatabaseHealthProbe(get()) }
    single { BrokerHealthProbe(get()) }
    factory { CheckHealth(listOf(get<DatabaseHealthProbe>(), get<BrokerHealthProbe>())) }
}
