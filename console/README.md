# Консоль RestoFleet (этап П)

Android-консоль службы поддержки и заглушка Desktop. Общий код — KMP (таргеты `android` и `jvm`, iOS нет, реш. 429).
Структура и правила — `docs/architecture.md` разд. 4–5, план — `docs/superpowers/plans/2026-11-22-practice.md` (ПМ6–ПМ8).

```
console/
  core/{common, mvi, decompose, network, storage, designsystem, ui, testing}
  feature/<f>/{<f>-domain, <f>-data, <f>-ui, <f>-component}
  root/          RootComponent (Вход | Главная), MainComponent (4 вкладки), Koin-модули, RootContent
  app-android/   applicationId ru.restofleet.console
  app-desktop/   окно 420×860, тот же RootContent
```

## Сборка и запуск

```bash
./gradlew :console:app-android:assembleDebug          # APK: console/app-android/build/outputs/apk/debug
./gradlew :console:app-desktop:run                    # окно Desktop (токены только в памяти)
./gradlew :console:app-desktop:run --args="--screenshot /tmp/console.png"   # PNG корня без окна
```

CA стенда:
- Android (только debug) — `deploy/certs/out/public/ca.crt` подхватывается автоматически, путь можно задать `-PstandCaPath=<pem>`;
  release CA стенда не доверяет.
- Desktop — `-Drf.standCa=<pem>`, например `./gradlew :console:app-desktop:run -Drf.standCa=…` (свойство передаётся JVM
  через `applicationDefaultJvmArgs` или `JAVA_OPTS`).

Адрес сервера вводится на экране входа, только `https://`.

## Правила

- Koin — только через конструктор; `get()` — в `console:root`, `console:app-*` и `<f>-component/…/di`.
- Фича не зависит от другой фичи: переходы — `Output`-ы компонентов, связывает `console:root` (`ScreenFactories`).
- Токены: refresh шифруется Keystore (Android) и лежит в DataStore, access — в памяти, в журнал и `toString` не попадают.
- Архитектурные правила проверяет `konsist-tests` (`ConsoleArchitectureTest`).
