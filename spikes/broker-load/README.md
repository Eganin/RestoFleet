# spikes:broker-load — одноразовые спайки S1–S3

Нагрузка на брокер Mosquitto 2.1.2 (dev-стенд, `deploy/`) и Dynamic Security. Код одноразовый, тестов нет, модуль вне Kover и Konsist.
Клиенты спайка — `spike-00001…`, роль `device`; клиенты `dev-*` и `operator` не затрагиваются.

## Запуск (из корня репозитория)
```bash
./gradlew :spikes:broker-load:installDist
B=spikes/broker-load/build/install/broker-load/bin/broker-load
ulimit -n 8192                       # лимит дескрипторов шелла наследуется процессом
umask 077
set -a; . deploy/.env; set +a        # пароль admin: из .env в окружение, не в аргументы (видны в ps)
export RF_ADMIN_PASSWORD="$DYNSEC_ADMIN_PASSWORD"; unset DYNSEC_ADMIN_PASSWORD

$B dynsec-create --count 1000 --batch 100          # S2; учётки -> deploy/out/spike-creds.csv (600, вне git)
docker compose -p restofleet -f deploy/docker-compose.yml restart mosquitto   # замер времени до healthy
$B load --clients 1000 --ramp-per-sec 200 --interval 30 --hold 180 --out spikes/broker-load/results/s1-1000.csv   # S1
$B disable-client --id spike-00001                 # S3
$B dynsec-delete --prefix spike-                   # уборка
rm deploy/out/spike-creds.csv
bash deploy/mosquitto/dynsec-dev.sh && bash deploy/mosquitto/test-dynsec-dev.sh   # 16/16
```
Память и CPU брокера снимались отдельным циклом `docker stats --no-stream` раз в 27 с.

## Результаты (2026-11-10, 10 ядер Apple, 16 ГБ, JDK Corretto 21.0.6, Docker 29.2.0)
Оговорка: **масштаб 1 000 клиентов вместо 10 000, S1 — 3 мин после разгона вместо 15** (сокращено по просьбе пользователя). 10 000 — «— (практика)».

| ID | Критерий | Факт (1 000 клиентов) | Итог |
|---|---|---|---|
| S1 | 10 000 подключений, heap ≤ 2 ГБ, стабильная телеметрия 30 с | 1 000/1 000 подключены весь hold (185 с), потерь 0, pub fail 0, 32,9 сообщ/с (ожидалось 33,3); heap used пик 114 МБ (без форс-GC); CPU процесса 8 % ядра; брокер 12 → 37 МиБ (≈25 КиБ/клиент), CPU ≈ 1 % | ⚠️ 10 000 не проверялся; экстраполяция: heap ≤ 1,1 ГБ, ≈ 80 % ядра, брокер ≈ 250 МиБ; эфемерных портов macOS ≈ 16 000 — ограничение стенда |
| S2 | создание ≤ 10 мин, старт брокера ≤ 30 с | создание 1 000 клиентов 0,56 с (10 пакетов по 200 команд), ошибок 0; restart: TLS через 0,4 с, `healthy` через 5,5 с | ✅ (на 1 000; 10 000 — экстраполяция) |
| S3 | разрыв ≤ 5 с | `disableClient` → DISCONNECT от сервера через 16 мс; повторный вход отклонён; после `enableClient` вход успешен | ✅ (на `spike-00001`, не на `dev-emu-36`) |

Метрики: `results/s1-1000.csv` (раз в 10 с), `results/s1-1000-broker-docker-stats.csv`, логи `s1-1000.log`, `s2-create.log`, `s2-delete.log`, `s3-disable.log`.
