# Точность классификатора: truth-s8-b-1795386263716

Случаев: 1, верно: 1, точность: **100.0 %** (цель ≥ 90 %).

Допуск по времени — 3 мин; класс `degradation` исключён (R-10 — диплом).

| эталон \\ результат | backend_global | backend_local | restaurant_network | device | none |
|---|---|---|---|---|---|
| backend_global | 0 | 0 | 0 | 0 | 0 |
| backend_local | 0 | 0 | 0 | 0 | 0 |
| restaurant_network | 0 | 0 | 1 | 0 | 0 |
| device | 0 | 0 | 0 | 0 | 0 |
| none | 0 | 0 | 0 | 0 | 0 |

Источники:
- эталон: `deploy/out/sim/truth-s8-b-1795386263716.csv`
- инциденты: `GET /api/v1/incidents`
- тревоги: `GET /api/v1/alerts?rule=R-01&includeSuppressed=true`
