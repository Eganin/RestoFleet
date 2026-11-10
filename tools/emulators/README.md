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

## Прогон — `run-matrix.sh <avd> <api>`

Стенд поднят (`deploy/`), агент собран (`./gradlew :agent:app:assembleDebug`). Скрипт запускает ровно один эмулятор (`-no-window -no-audio -no-boot-anim -port 5600`, окно не открывается), ставит debug-агента, передаёт учётку `dev-emu-<api>` через stdin (пароль не попадает в argv и вывод), ждёт retained `online` и телеметрию по подписке оператора, проверяет L2 (`adb reboot` без UI, новый `connId` ≤ 180 с), нажимает «Экспорт» в Capability Lab (`uiautomator`) и забирает `docs/mvp-results/api<N>-normal.json`. Итоги — в `api<N>-summary.txt`. Ручные шаги (L3, L4, C1–C4, D2–D5) не автоматизируются.

Ресивера `DEV_EXPORT` в агенте нет: экспорт делает нажатие кнопки.
