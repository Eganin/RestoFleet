# E2E Scenario: MVP-A, веха M2 — Device Owner и команды
Платформы: Android (эмуляторы `rf-api36` без DO и `rf-api36-do` с DO, API 36, headless `-no-window -no-audio`), Backend (docker-стенд: `apk-host` HTTPS 8443, `mosquitto` 8883).

Код: ветка `stage/0-mvp`, коммит `efbfffa`; шаг 18 — после исправлений, коммит `9984620`. Улики лежат в рабочей папке SDD (`.superpowers/sdd/2026-11-05-mvp-a/`, в git не входит). Скриншоты для отчёта копируются в `docs/reports/0-mvp/img/` в задаче 2.6.
Шаги, которые затронет раунд исправлений по ревью вехи M2, перепроверяются после него. Пометка: «перепроверено после M2-fix».

## Шаги
- [x] 1. Стенд: `docker compose up -d` в `deploy/` → `apk-host` и `mosquitto` healthy ✅ (task-2.5-fix1-report.md)
- [x] 2. `rf-api36`, обычный режим: установить агент (debug) и `waiter-4.13` → вкладки Lab и Device Owner, шапка DO «Обычный режим», D2–D6 и R10 — NA «только в режиме Device Owner» ✅ (`task-2.5-normal-lab-*.png`)
- [x] 3. C1 «Показать сообщение» → уведомление «Проверка RestoFleet» в канале `commands` ✅ (`task-2.5-normal-lab-commands.png`)
- [x] 4. C2 без DO и без overlay → стратегия `VIA_NOTIFICATION`; тап по уведомлению → `dummy-waiter` на переднем плане, `Background activity launch blocked` нет ✅ (`task-2.5-fix1-emulator-normal.log`)
- [x] 5. C3 с overlay (`appops … SYSTEM_ALERT_WINDOW allow`), «через 30 с» + Home → цель на переднем плане через ≈34 с (`DIRECT_OVERLAY`), блокировок нет ✅ (`task-2.5-fix1-normal-c3-overlay.png`)
- [x] 6. C4 в беззвучном режиме → `ringerMode silent`, `alarmVolume 6/7`, плеер `USAGE_ALARM` в состоянии `started`. Слышимость — в M4 ✅ (`task-2.5-fix1-normal-c4-silent.png`)
- [x] 7. Экспорт отчёта Lab → JSON содержит C1–C4, серийного номера нет, есть только маска ✅ (`task-2.5-fix1-emulator-normal-report.json`)
- [x] 8. `rf-api36-do`: `adb install -t`, `dpm set-device-owner ru.restofleet.agent/.AdminReceiver` → `Success`, шапка «Device Owner активен» ✅ (`task-2.5-do-active-initial.png`)
- [x] 9. R13 до D6 (без `READ_PHONE_STATE`) → `PermissionMissing: READ_PHONE_STATE`, не `RequiresDeviceOwner` ✅ (`task-2.5-fix1-do-r13-before-d6.png`)
- [x] 10. «Подтянуть хэш» с `https://10.0.2.2:8443/waiter-4.13.apk` → SHA-256 совпадает с `.sha256` (доверие к CA стенда только в debug) ✅ (`task-2.5-do-hash-fetched.png`)
- [x] 11. Release без CA: `aapt2 dump` release APK → 0 вхождений `networkSecurityConfig`, `stand_ca` ✅ (`task-2.5-release-trust.log`)
- [x] 12. «Запустить все проверки DO» → диалог перезагрузки **первым**, до любой проверки ✅ (`task-2.5-fix1-do-runall-dialog-first.png`)
- [x] 13. «Отмена» → выполнены D1, D6, R10, R13, D3, D5; D4 в прогоне нет; D2 «не запускалась»; серийный номер в маске ✅ (`task-2.5-fix1-do-after-decline-rows.png`)
- [x] 14. D3 тихая установка → `dumpsys package ru.restofleet.dummy.waiter` выдаёт `versionName=4.13`, без `pending_user_action` и без `REQUEST_INSTALL_PACKAGES` ✅ (`task-2.5-emulator-manual.log`)
- [x] 15. D5 «через 30 с» + Home, без overlay → цель на переднем плане через ≈34 с, блокировок нет — `DIRECT_DO` подтверждён на API 36 (решение 119) ✅ (`task-2.5-fix1-do-d5-30s.png`)
- [x] 16. D4 кнопкой → `mLockTaskModeState=LOCKED`, Home и Recents заблокированы; выход только через `am force-stop` цели ✅ с оговоркой: выхода изнутри агента нет — кандидат в ТЗ v0.3 (`task-2.5-do-d4-locked.png`)
- [x] 17. «Запустить все» → «Перезагрузить» → D2 последней, `uptime 0 min`; агент жив, DO сохранён, job сторожа WorkManager на месте ✅ (`task-2.5-fix1-do-after-reboot.png`)
- [x] 18. Перепроверка после M2-fix (`rf-api36-do`, API 36, коммит `9984620`, debug-сборка) ✅ перепроверено после M2-fix:
  - 18.1 debug: вкладки Lab и Device Owner на месте, в Lab секция «Команды» (`m2-fix-lab-debug-tabs.png`); в release вкладки и команд нет — `PrivilegedSurfaceReleaseTest` (решение 140)
  - 18.2 D3 с `ru.restofleet.dummy.waiter` → установка 4.13, результат `package … · versionCode 13 · versionName 4.13` (`m2-fix-do-d3-waiter-ok.png`; первый вызов после загрузки эмулятора дал `network:IOException`, повтор успешен — решение 147)
  - 18.3 D3 с `com.example.other` → `package_not_watched`, к apk-host запрос не ушёл (`m2-fix-do-d3-other-notwatched.png`); D3 с `com.android.chrome` и APK waiter → `install_failed:4`, сообщение системы в logcat (`m2-fix-do-d3-foreign-apk-rejected.png`)
  - 18.4 D4 → `mLockTaskModeState=LOCKED` (`m2-fix-do-d4-locked.png`); C1 → `kiosk_active` (`m2-fix-c1-under-kiosk.png`); C2 с `com.android.chrome` → `kiosk_active` (`m2-fix-c2-under-kiosk.png`); выход — `am force-stop` цели → `mLockTaskModeState=NONE`
  - 18.5 «Запустить все» → диалог (`m2-fix-do-reboot-dialog.png`) → «Перезагрузить» → `ShutdownThread` через ≈3,1–3,5 с (`m2-fix-reboot-timeline.txt`, `reason: deviceowner`); после загрузки агент жив (pid), DO на месте, job-ы агента в jobscheduler (`m2-fix-do-after-reboot.png`)
  - Протокол команд: `m2-fix-emulator.log`; эмулятор остановлен (`emu kill`)

## Не проверено (переносится)
- C4 на слух (`-no-audio`) → M4 (4.2).
- D4 с Chrome (в `rf-api36-do` Chrome есть, в `rf-api36` нет) → M4.
- D7 через QR → 4.1.
- Back в lock task на API 26–27 → M4 (нужны образы).
- D2 в JSON-отчёте Lab: журнал ручных проверок хранится в памяти, после перезагрузки результат D2 теряется → M3/M4.
