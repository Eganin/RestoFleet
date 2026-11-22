# Точность классификатора: truth-s8-a-1795382222118

Случаев: 12, верно: 12, точность: **100.0 %** (цель ≥ 90 %).

Допуск по времени — 3 мин; класс `degradation` исключён (R-10 — диплом).

| эталон \\ результат | backend_global | backend_local | restaurant_network | device | none |
|---|---|---|---|---|---|
| backend_global | 12 | 0 | 0 | 0 | 0 |
| backend_local | 0 | 0 | 0 | 0 | 0 |
| restaurant_network | 0 | 0 | 0 | 0 | 0 |
| device | 0 | 0 | 0 | 0 | 0 |
| none | 0 | 0 | 0 | 0 | 0 |

Источники:
- эталон: `deploy/out/sim/truth-s8-a-1795382222118.csv`
- инциденты: `GET /api/v1/incidents`
- тревоги: `GET /api/v1/alerts?rule=R-01&includeSuppressed=true`
