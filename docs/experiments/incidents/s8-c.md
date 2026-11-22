# Точность классификатора: truth-s8-c-1795386736246

Случаев: 2, верно: 2, точность: **100.0 %** (цель ≥ 90 %).

Допуск по времени — 3 мин; класс `degradation` исключён (R-10 — диплом).

| эталон \\ результат | backend_global | backend_local | restaurant_network | device | none |
|---|---|---|---|---|---|
| backend_global | 0 | 0 | 0 | 0 | 0 |
| backend_local | 0 | 2 | 0 | 0 | 0 |
| restaurant_network | 0 | 0 | 0 | 0 | 0 |
| device | 0 | 0 | 0 | 0 | 0 |
| none | 0 | 0 | 0 | 0 | 0 |

Источники:
- эталон: `deploy/out/sim/truth-s8-c-1795386736246.csv`
- инциденты: `GET /api/v1/incidents`
- тревоги: `GET /api/v1/alerts?rule=R-01&includeSuppressed=true`
