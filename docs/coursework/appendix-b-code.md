# Приложение Б — Фрагменты программного кода

В приложении приведены фрагменты исходного кода, реализующие механизмы, от которых зависят гарантии доставки и согласованность состояния устройств: приём сообщений серверной частью, досылка буфера исходящих сообщений агента, автомат состояний цели проверки, правила тени, регистрация устройства и восстановление подписок серверной части. Текст фрагментов взят из репозитория без изменения логики, имён и литералов; объявления пакетов, импорты, часть комментариев и запись в журнал опущены, длинные строки перенесены, а у членов класса, приведённых без объявления класса, уменьшен отступ. Места пропусков обозначены комментарием `// …`, пропуск внутри строки — `/* … */`.

## Б.1 Приём сообщений с подтверждением после фиксации транзакции

Конвейер приёма серверной части реализован классом `IngestPipeline` модуля `server:data` (файл `ingest/IngestPipeline.kt`); приведены очередь и методы `run` и `nextBatch`. Обработчик клиента HiveMQ передаёт сообщения в неограниченную очередь неблокирующим методом `offer`, а функция `nextBatch` собирает из неё пакет: первое сообщение ожидается без ограничения времени, следующие — пока в пакете меньше `batchSize` сообщений (по умолчанию 500) и не истекло окно `batchWindowMs` от первого (500 мс). Подтверждение `ack()` отправляется брокеру только после возврата из метода `handle`, то есть после фиксации транзакций обработки, поэтому пакет, не подтверждённый к остановке процесса, брокер доставит повторно. Метод `handle` того же файла (в листинг не включён) при временном сбое базы данных повторяет пакет без подтверждения, а при детерминированном разбирает его по одному сообщению.

```kotlin
private val queue = Channel<InboundMessage>(Channel.UNLIMITED)

/** Завершается отменой; неподтверждённая пачка остаётся у брокера. */
suspend fun run() {
    while (true) {
        val batch = nextBatch()
        handle(batch)
        batch.forEach { it.ack() }
        metrics.batches.incrementAndGet()
    }
}

// Ожидание через select с onTimeout: в отличие от withTimeoutOrNull
// таймаут не отменит приём уже вынутого из очереди сообщения
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun nextBatch(): List<InboundMessage> {
    val batch = mutableListOf(queue.receive())
    val deadline = timeSource.markNow() + config.batchWindowMs.milliseconds
    while (batch.size < config.batchSize) {
        val remaining = -deadline.elapsedNow()
        val next = queue.tryReceive().getOrNull()
            ?: if (remaining > Duration.ZERO) {
                select {
                    queue.onReceive { it }
                    onTimeout(remaining) { null }
                }
            } else {
                null
            }
        if (next == null) break
        batch += next
    }
    return batch
}
```

## Б.2 Досылка буфера исходящих сообщений агента

Досылку буфера исходящих сообщений агента выполняет сценарий использования `FlushOutbox` модуля `agent:domain` (файл `usecase/FlushOutbox.kt`); приведены поля замка и интервала и метод `invoke`. Записи выбираются пачками по `batch` (по умолчанию 50) в порядке `Outbox.peek` — по приоритету вида сообщения, внутри приоритета в порядке постановки (порт `Outbox`, файл `port/Outbox.kt`), — и публикуются по одной не чаще `ratePerSec` (по умолчанию 20) в секунду; запись удаляется сразу после своей публикации, поэтому при обрыве возможен дубль не более одной записи. Окончательный отказ брокера (`MessageRejected`, например PUBACK 0x87) удаляет запись без повтора и учитывается в `dropped`, а любой другой сбой останавливает досылку, и запись остаётся в буфере до следующего запуска. При `FlushScope.ALL` замок берётся на одну пачку, поэтому досылка одних итогов команд (`FlushScope.COMMAND_RESULTS`) не ждёт окончания досылки всего буфера.

```kotlin
private val mutex = Mutex()
private val intervalMs = (MS_IN_SECOND + ratePerSec - 1) / ratePerSec

suspend operator fun invoke(
    scope: FlushScope = FlushScope.ALL
): FlushResult {
    var sent = 0
    var dropped = 0

    // Под замком; true — пачка ушла целиком, можно взять следующую
    suspend fun sendBatch(): Boolean {
        val pending = when (scope) {
            FlushScope.ALL -> outbox.peek(batch)
            FlushScope.COMMAND_RESULTS ->
                outbox.peek(batch, MessageKind.COMMAND_RESULT)
        }
        for ((id, message) in pending) {
            if (sent + dropped > 0) delay(intervalMs)
            val failure = deliver(message)
            when {
                failure == null -> sent++
                failure is MessageRejected -> {
                    dropped++
                    logDropped(message, failure)
                }
                else -> return false
            }
            outbox.remove(listOf(id))
        }
        return pending.isNotEmpty()
    }

    when (scope) {
        FlushScope.ALL -> do {
            val more = mutex.withLock { sendBatch() }
        } while (more)
        FlushScope.COMMAND_RESULTS -> mutex.withLock {
            do {
                val more = sendBatch()
            } while (more)
        }
    }
    return FlushResult(sent, outbox.size(), dropped)
}

// … deliver(m): null — опубликовано, иначе причина сбоя; logDropped(…)
```

## Б.3 Автомат состояний цели проверки доступности

Класс `ProbeStateMachine` модуля `shared:probes` используется агентом и симулятором, по одному экземпляру на цель проверки. Начальное состояние — `UNKNOWN`; `failThreshold` неудач подряд (по умолчанию 3, значение меньше 1 заменяется на 1) переводят цель в `DOWN`, а первый успех сбрасывает счётчик и переводит её в `UP`. Функция `moveTo` возвращает переход только при смене состояния, в том числе `UNKNOWN → UP`; переход `Transition` содержит прежнее и новое состояние, число неудач подряд и класс последней ошибки и служит поводом для события `probe_state`.

```kotlin
class ProbeStateMachine(failThreshold: Int = DEFAULT_FAIL_THRESHOLD) {
    val failThreshold: Int = failThreshold.coerceAtLeast(1)

    var state: ProbeState = ProbeState.UNKNOWN
        private set

    var consecutiveFailures: Int = 0
        private set

    fun onSuccess(): Transition? {
        consecutiveFailures = 0
        return moveTo(ProbeState.UP, error = null)
    }

    fun onFailure(error: ProbeErrorClass): Transition? {
        consecutiveFailures++
        return if (consecutiveFailures >= failThreshold)
            moveTo(ProbeState.DOWN, error) else null
    }

    fun record(ok: Boolean, error: ProbeErrorClass?): Transition? =
        if (ok) onSuccess() else onFailure(error ?: ProbeErrorClass.OTHER)

    private fun moveTo(
        target: ProbeState,
        error: ProbeErrorClass?
    ): Transition? {
        if (state == target) return null
        val transition =
            Transition(state, target, consecutiveFailures, error)
        state = target
        return transition
    }

    companion object {
        const val DEFAULT_FAIL_THRESHOLD = 3
    }
}
```

## Б.4 Решение по версии и дельта тени устройства

Правила тени вынесены в модуль `shared:shadow`. Перечисление `VersionDecision` (файл `VersionDecision.kt`) определяет реакцию на пришедший `desired`: версия новее сохранённой или отсутствие сохранённой версии ведёт к применению и сохранению, равная — только к повторной публикации `reported`, более старая игнорируется. Это решение принимают агент и симулятор при получении `desired`.

```kotlin
enum class VersionDecision {
    IGNORE_OLDER,
    ECHO_EQUAL,
    APPLY_NEWER,
    ;

    companion object {
        fun of(stored: Long?, incoming: Long): VersionDecision = when {
            stored == null || incoming > stored -> APPLY_NEWER
            incoming == stored -> ECHO_EQUAL
            else -> IGNORE_OLDER
        }
    }
}
```

Функция `delta` (файл `ShadowDelta.kt`), которой серверная часть вычисляет расхождение тени, оставляет ключи `desired`, значения которых в `reported` отсутствуют или отличаются; служебные ключи корня (`ShadowKeys.METADATA`) исключаются. Вложенные объекты сравниваются рекурсивно, и в дельту попадают только расходящиеся листья, остальные значения, в том числе списки, — целиком. Перед сравнением элементы списков корня дополняются значениями по умолчанию из `ShadowDefaults.LIST_ITEMS` (у целей `probes` — `reference = false`); выполняющая это вспомогательная функция `withItemDefaults` опущена.

```kotlin
fun delta(
    desired: ShadowDocument,
    reported: ShadowDocument
): ShadowDocument = ShadowDocument(
    diff(
        desired.fields - ShadowKeys.METADATA,
        reported.fields,
        ShadowDefaults.LIST_ITEMS
    )
)

private fun diff(
    desired: Map<String, ShadowValue>,
    reported: Map<String, ShadowValue>,
    listItemDefaults: Map<String, Map<String, ShadowValue>> = emptyMap(),
): Map<String, ShadowValue> = buildMap {
    desired.forEach { (key, want) ->
        val have = reported[key]
        val defaults = listItemDefaults[key]
        when {
            want.withItemDefaults(defaults) ==
                have?.withItemDefaults(defaults) -> Unit
            want is ShadowValue.Obj && have is ShadowValue.Obj ->
                diff(want.fields, have.fields)
                    .takeIf { it.isNotEmpty() }
                    ?.let { put(key, ShadowValue.Obj(it)) }
            else -> put(key, want)
        }
    }
}

// … withItemDefaults(defaults): подстановка умолчаний в элементы списка
```

## Б.5 Регистрация устройства в одной транзакции

Сценарий использования `ProvisionDevice` модуля `server:domain` (файл `provisioning/usecase/ProvisionDevice.kt`) обменивает одноразовый код регистрации на учётную запись устройства; приведён метод `invoke`. В блоке `tx.transactional` выполняются погашение кода по его хэшу SHA-256, создание устройства, запись bcrypt-хэша пароля, привязка кода к устройству, создание тени с `desired` версии 1 и последним шагом — создание клиента брокера; результат `Outcome.Failure` откатывает транзакцию, поэтому при отказе брокера код остаётся непогашенным. Если клиент брокера создан, а фиксация не удалась, функция `compensate` удаляет его в контексте `NonCancellable`, и исключение пробрасывается. Сохраняемый `desired` публикуется только после фиксации, и результат публикации на исход регистрации не влияет.

```kotlin
suspend operator fun invoke(
    request: ProvisionRequest
): Outcome<ProvisionedDevice, ServerError> {
    val code = ProvisioningCode.normalize(request.code)
    if (code.isEmpty() || code.length > MAX_CODE)
        return Outcome.Failure(ServerError.TokenInvalid)
    val tokenHash = digest.sha256(code)
    var brokerClient: DeviceId? = null
    val outcome = try {
        tx.transactional<Pair<ProvisionedDevice, Shadow>, ServerError> {
            val now = clock.nowMillis()
            val token = tokens.redeem(tokenHash, now)
                ?: return@transactional Outcome.Failure(
                    rejection(tokens.findByHash(tokenHash), now)
                )
            val password = secrets.devicePassword()
            val device = newDevice(token, request, now)
            devices.insert(device)
            credentials.insert(device.id, hasher.hash(password), now)
            tokens.attachDevice(token.id, device.id)
            val shadow =
                Shadow.initial(device.id, DefaultDesired.document, now)
            shadows.insert(shadow)
            when (val created = broker.createDevice(device.id, password)) {
                is Outcome.Failure -> created
                is Outcome.Success -> {
                    brokerClient = device.id
                    Outcome.Success(
                        ProvisionedDevice(
                            device.id, password, token.restaurantId,
                            token.role, settings.broker
                        ) to shadow,
                    )
                }
            }
        }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Включая отмену корутины: компенсация — в NonCancellable
        withContext(NonCancellable) { compensate(brokerClient) }
        throw e
    }
    return when (outcome) {
        is Outcome.Failure -> outcome
        is Outcome.Success -> {
            val (provisioned, shadow) = outcome.value
            publisher.publishDesired(
                provisioned.deviceId, shadow.desiredState()
            )
            Outcome.Success(provisioned)
        }
    }
}

// … newDevice(…), rejection(…), compensate(…): broker.deleteDevice
```

## Б.6 Повторная подписка серверной части при каждом подключении

Класс `HiveMqClient` модуля `server:data` (файл `mqtt/HiveMqClient.kt`) держит единственное MQTT 5-соединение серверной части с постоянной сессией (`Clean Start = false`); приведены члены класса, связанные с подписками. Обработчик `onConnected` при каждом подключении повторно оформляет все запомненные в `subscriptions` фильтры независимо от признака `session present` в CONNACK: Mosquitto 2.1.2 при восстановлении сессии из постоянного хранилища не восстанавливает разделяемые подписки `$share/…`, а повторная подписка на тот же фильтр в той же сессии заменяет прежнюю. Признак `isSubscribed`, по которому `/health` сообщает о готовности, истинен только после SUBACK без отказов на текущем соединении: обработчик разрыва сбрасывает `subscriptionsActive`. SUBACK в `onConnected` ожидается асинхронно (`whenComplete`), так как обработчик выполняется в потоке клиента HiveMQ.

```kotlin
@Volatile private var subscriptions: List<String> = emptyList()

// SUBACK на все subscriptions получен на текущем соединении
@Volatile private var subscriptionsActive = false

private val client: Mqtt5AsyncClient = Mqtt5Client.builder()
    // … clientId, адрес, TLS, автопереподключение 1…30 с
    .addConnectedListener(::onConnected)
    .addDisconnectedListener { ctx ->
        subscriptionsActive = false
        // … журнал; переподключение тем же CONNECT (Clean Start = false)
    }
    .buildAsync()

val isSubscribed: Boolean get() = isConnected && subscriptionsActive

suspend fun subscribe(filters: List<String>) {
    subscriptions = (subscriptions + filters).distinct()
    val ack = client.subscribe(subscribeRequest(filters)).await()
    val rejected = filters.zip(ack.reasonCodes)
        .filter { (_, code) -> code.isError }
    check(rejected.isEmpty()) { /* … отклонённые фильтры и коды */ }
    subscriptionsActive = true
}

private fun onConnected(
    context: com.hivemq.client.mqtt.lifecycle.MqttClientConnectedContext
) {
    val sessionPresent = (context as? Mqtt5ClientConnectedContext)
        ?.connAck?.isSessionPresent == true
    log.info { "MQTT connected, session present: $sessionPresent" }
    val filters = subscriptions
    if (filters.isEmpty()) return
    // Независимо от session present: брокер мог восстановить сессию
    // без shared-подписок. До SUBACK isSubscribed = false
    client.subscribe(subscribeRequest(filters)).whenComplete { ack, error ->
        val rejected = ack?.reasonCodes?.count { it.isError } ?: 0
        when {
            error != null -> log.error { /* … */ }
            rejected > 0 -> log.error { /* … */ }
            else -> {
                subscriptionsActive = true
                log.info { "MQTT resubscribed ${filters.size} filters" }
            }
        }
    }
}
```
