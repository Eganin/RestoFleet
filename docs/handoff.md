# Handoff — точка входа для любой сессии

> Новая сессия или агент: прочитай этот файл, затем `CLAUDE.md`, план текущего этапа (ссылка ниже) и только свой раздел в нём. Блок «Текущее состояние» перезаписывается после каждой задачи, «Журнал» — дополняется.

## Текущее состояние

- **Этап:** MVP-A (эмуляторы) · **ветка:** `stage/0-mvp`
- **План:** `docs/superpowers/plans/2026-11-05-mvp-a.md`
- **Последняя завершённая задача:** 0.7 стенд — CA (make-ca.sh) и Mosquitto 2.1.2 (TLS 8883 + DynSec + persistence)
- **Следующая задача:** 0.8 закрытие M0 — отчёт и handoff
- **Блокеры:** GitHub-remote не настроен (CI заработает после пуша)
- **Как поднять стенд:** `deploy/README.md` (4 команды + `./test-stand.sh`); нужен `local.properties` с `sdk.dir` для сборки.
- **Исполнение:** M0 — в основной сессии; M1–M4 — субагенты (исполнитель + ревьюер на задачу).

## Журнал

| Дата | Задача | Коммит | Итог |
|---|---|---|---|
| 2026-11-06 | План MVP-A согласован | — | решения — `docs/reports/0-mvp/decisions.md` |
| 2026-11-06 | 0.1 git, wrapper 9.8.2, version catalog, root build | см. `git log` | `./gradlew help` зелёный, все плагины резолвятся |
| 2026-11-06 | 0.2 shared:kernel (Outcome, DomainError, Clock, IdGenerator) | см. `git log` | 12 тестов, покрытие 100 %; нужен `local.properties` с `sdk.dir` (AGP сам ставит платформу 37) |
| 2026-11-06 | 0.3 shared:protocol — DeviceId, Topics, RfJson | см. `git log` | 6 тестов; небезопасные deviceId (/ + # пробел, >64, не ASCII) отклоняются |
| 2026-11-06 | 0.4 shared:protocol — черновик DTO, 15 golden-фикстур, round-trip | см. `git log` | 23 теста протокола; покрытие ≥ 90 %; добавлены фикстуры agent_lifecycle и package_changed |
| 2026-11-06 | 0.5 detekt 2 и Konsist (:konsist-tests) | см. `git log` | detekt чистый (порог длины строки не поднимался); 4 правила Konsist ловят нарушения (проверено мутацией) |
| 2026-11-06 | 0.6 Kover-агрегатор (вариант ci) и GitHub Actions | см. `git log` | `./gradlew build detekt koverVerifyCi` зелёный (1 мин); строки 199/199; гейт проверен нарушением |
| 2026-11-06 | 0.7 стенд — CA (make-ca.sh) и Mosquitto 2.1.2 (TLS 8883 + DynSec + persistence) | см. `git log` | `deploy/test-stand.sh` и `deploy/certs/test-make-ca.sh` зелёные; мутации (anon разрешён) ловятся |
