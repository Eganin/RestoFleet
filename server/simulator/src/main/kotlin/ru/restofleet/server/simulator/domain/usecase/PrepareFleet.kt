package ru.restofleet.server.simulator.domain.usecase

import ru.restofleet.server.simulator.domain.model.FleetSpec
import ru.restofleet.server.simulator.domain.model.RegistrationReport
import ru.restofleet.server.simulator.domain.model.RegistrationRequest
import ru.restofleet.server.simulator.domain.model.RoleMix
import ru.restofleet.server.simulator.domain.port.ControlApi

/**
 * Подготовка парка точности (реш. 428): рестораны `<prefix>-01…<prefix>-NN` создаются через `POST /restaurants`, если
 * их нет, и в них регистрируются устройства (по [FleetSpec.perRestaurant], состав — [RoleMix]) в отдельный кэш учёток.
 * Повторный вызов ничего не дублирует: рестораны ищутся по коду, регистрация дописывает до нужного числа.
 */
class PrepareFleet(
    private val control: ControlApi,
    private val register: RegisterDevices,
) {
    suspend operator fun invoke(fleet: FleetSpec, mix: RoleMix, parallelism: Int): RegistrationReport {
        val ids = fleet.codes.map { control.ensureRestaurant(it, "Симулятор $it") }
        return register(RegistrationRequest(fleet.devices, mix, parallelism, restaurantIds = ids))
    }
}
