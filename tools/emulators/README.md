# Эмуляторы MVP-A

Матрица — `docs/stages/0-mvp-feasibility.md`. Скрипты рассчитаны на macOS arm64 и образы `arm64-v8a`.

## Создание AVD — `create-avds.sh`

```bash
tools/emulators/create-avds.sh list                 # таблица AVD
tools/emulators/create-avds.sh create rf-api26      # скачать образ и создать AVD (pixel_6, 4 ГБ RAM, без Play Store)
tools/emulators/create-avds.sh delete rf-api26
```

Создаётся только то, что названо: AVD весит несколько ГБ, поэтому они создаются, прогоняются и удаляются по одному (скрипт останавливается, если свободно меньше 15 ГБ). В таблице: `rf-api26/29/31/33/35/36/37`, DO-AVD `rf-api{26,35,36,37}-do` (`pixel_tablet`, без аккаунтов) и `rf-api36-qr` на `google_apis_playstore` (D7: на экране приветствия 6 касаний открывают сканер QR; QR с JSON провижининга подаётся в виртуальную камеру).

**Ruling:** образа 37.2 в репозитории Google нет, `rf-api37` — на `android-37.0`. **Известная проблема (2026-11-10):** на эмуляторе 37.2.13.0 (macOS arm64) этот образ не грузится — «hanging thread QEMU2», затем segfault (см. `docs/mvp-results/api37-summary.txt`).

## Прогон — `run-matrix.sh <avd> <api> [--window] [--do] [--no-reboot] [--no-start]`

```bash
./gradlew :agent:app:assembleDebug
tools/emulators/run-matrix.sh rf-api36 36                    # обычный режим, без окна
tools/emulators/run-matrix.sh rf-api36 36 --window           # с окном и звуком эмулятора
tools/emulators/run-matrix.sh rf-api36-do 36 --do --window   # Device Owner
```

- **`--window`**: эмулятор открывается с окном и звуком, иначе запускается с `-no-window -no-audio`.
- **`--do`**: прогон на AVD с Device Owner (`rf-api36-do`) с учёткой `dev-do-<api>`. Сверх обычного прогона:
  - проверяет, что агент — Device Owner, а если нет, назначает его (`dpm set-device-owner`; на AVD не должно быть аккаунтов);
  - проверяет `mode=device_owner` в `status`;
  - отправляет команды по MQTT:
    - **D3** — `install_update` для `waiter-4.13`: после удаления приложения это тихая установка с нуля;
    - **D3** — `install_update` для `waiter-4.14`: обновление с проверкой SHA-256. Перед прогоном нужны `tools/dev/build-dummy-apk.sh 13` и `14`, а также запущенный `apk-host`;
    - **D5** — `launch_app` при агенте в фоне: проверяется, что dummy-waiter оказался на переднем плане;
    - **D2** — `reboot` вместо `adb reboot`: итог команды пишется в outbox и уходит до перезагрузки или досылается после неё. Этот же шаг проверяет автозапуск L2.

  Ответы (`cmd/res`) берутся из MQTT-лога прогона. Итоги — в `docs/mvp-results/api<N>-do-summary.txt`, отчёт Lab — в `api<N>-device_owner.json`. Ручные шаги (D4 киоск, D6 выдача разрешений, кнопки DO-экрана) не автоматизируются.
- **Лог MQTT.** На всё время прогона включается подписка оператора на `rf/v1/dev/dev-emu-<api>/#`. Все сообщения устройства пишутся в `logs/run-matrix/api<N>-<дата-время>-mqtt.log`, по одному на строку: `время-по-часам-хоста топик payload`. В логе есть `status`, `telemetry`, `probe`, `events`, `shadow/desired`, `shadow/reported` и `cmd/res`. Первыми приходят retained-сообщения прошлой сессии. В конце прогона в итог пишется число сообщений по типам. Рядом лежит лог самого эмулятора. Каталог `logs/` в git не попадает.
- **Если этот AVD уже запущен** (например, из Android Studio) или порт 5600 занят, скрипт сразу останавливается и чужой эмулятор не трогает. Закройте эмулятор и повторите.

Стенд поднят (`deploy/`), агент собран (`./gradlew :agent:app:assembleDebug`). Скрипт запускает ровно один эмулятор (`-no-boot-anim -no-snapshot -port 5600`), ставит debug-агента, передаёт учётку `dev-emu-<api>` через stdin (пароль не попадает в argv и вывод), ждёт retained `online` и телеметрию по подписке оператора, проверяет L2 (`adb reboot` без UI, новый `connId` ≤ 180 с), нажимает «Экспорт» в Capability Lab (`uiautomator`) и забирает `docs/mvp-results/api<N>-normal.json`. Итоги — в `api<N>-summary.txt`. Ручные шаги (L3, L4, C1–C4, D2–D5) не автоматизируются.

Ресивера `DEV_EXPORT` в агенте нет: экспорт делает нажатие кнопки.
