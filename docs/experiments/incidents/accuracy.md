# Точность классификатора: truth-accuracy-1795394254446

Случаев: 96, верно: 96, точность: **100.0 %** (цель ≥ 90 %).

Допуск по времени — 3 мин после конца эпизода (только вперёд, реш. 537); класс `degradation` исключён (R-10 — диплом).

| эталон \\ результат | backend_global | backend_local | restaurant_network | device | none |
|---|---|---|---|---|---|
| backend_global | 60 | 0 | 0 | 0 | 0 |
| backend_local | 0 | 6 | 0 | 0 | 0 |
| restaurant_network | 0 | 0 | 3 | 0 | 0 |
| device | 0 | 0 | 0 | 3 | 0 |
| none | 0 | 0 | 0 | 0 | 24 |

Источники:
- эталон: `deploy/out/sim/truth-accuracy-1795394254446.csv`
- инциденты: `GET /api/v1/incidents`
- тревоги: `GET /api/v1/alerts?rule=R-01&includeSuppressed=true`
