# Точность классификатора: truth-accuracy-1795388188560

Случаев: 96, верно: 83, точность: **86.5 %** (цель ≥ 90 %).

Допуск по времени — 3 мин; класс `degradation` исключён (R-10 — диплом).

| эталон \\ результат | backend_global | backend_local | restaurant_network | device | none |
|---|---|---|---|---|---|
| backend_global | 60 | 0 | 0 | 0 | 0 |
| backend_local | 0 | 6 | 0 | 0 | 0 |
| restaurant_network | 0 | 0 | 3 | 0 | 0 |
| device | 1 | 1 | 0 | 1 | 0 |
| none | 8 | 2 | 1 | 0 | 13 |

Источники:
- эталон: `deploy/out/sim/truth-accuracy-1795388188560.csv`
- инциденты: `GET /api/v1/incidents`
- тревоги: `GET /api/v1/alerts?rule=R-01&includeSuppressed=true`
