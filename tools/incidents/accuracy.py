#!/usr/bin/env python3
"""Точность классификатора инцидентов (ПМ4, реш. 428): эталон симулятора против GET /incidents и GET /alerts.

Запуск (python3 -I, только stdlib):
  python3 -I tools/incidents/accuracy.py --truth deploy/out/sim/truth-accuracy-<ts>.csv \\
      --incidents incidents.json --alerts alerts.json --out docs/experiments/incidents/accuracy.md

Сопоставление строки эталона (ресторан, ожидаемый класс, интервал):
  * инцидент считается, если он покрывает ресторан и его интервал [startedAt, resolvedAt или конец данных)
    пересекается с интервалом эталона, расширенным на допуск (3 мин) **только вперёд**: допуск покрывает задержку
    обнаружения и закрытия, а увидеть эпизод раньше его начала сервер не может. Расширение назад сопоставляло строку
    с инцидентом предыдущего раунда (V-5, реш. 537);
  * класс совпал — среди таких инцидентов есть инцидент ожидаемого класса; иначе предсказание — класс первого
    по времени из них; нет ни одного — «none»;
  * «device» — R-01 этого устройства без подавления инцидентом, пересекающаяся с интервалом (инцидента при этом нет);
  * класс «degradation» исключается: R-10 — диплом;
  * --since/--until (epoch ms) — окно прогона: инциденты и тревоги, начавшиеся вне него, не учитываются. После
    штатной остановки симулятора все устройства офлайн (R-07 в каждом ресторане) — это не эпизод эталона, а
    следующий прогон застаёт эти инциденты открытыми до переподключения устройств (реш. 462).
"""
import argparse
import csv
import json
import sys
from collections import Counter, OrderedDict

CLASSES = ["backend_global", "backend_local", "restaurant_network", "device", "none"]
EXCLUDED = {"degradation"}
TOLERANCE_MS = 3 * 60 * 1000


def load_truth(path):
    with open(path, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    for r in rows:
        r["from"] = int(r["from"])
        r["to"] = int(r["to"])
        r["device"] = r.get("device") or None
    return rows


def load_items(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    return data["items"] if isinstance(data, dict) else data


def overlaps(start, end, lo, hi):
    return start < hi and end > lo


def window(row):
    """Интервал строки эталона с допуском только вперёд (V-5)."""
    return row["from"], row["to"] + TOLERANCE_MS


def incident_classes(row, incidents, horizon):
    lo, hi = window(row)
    found = []
    for inc in incidents:
        codes = {r["code"] for r in inc.get("restaurants", [])}
        end = inc.get("resolvedAt") or horizon
        if row["restaurant"] in codes and overlaps(inc["startedAt"], end, lo, hi):
            found.append((inc["startedAt"], inc["kind"]))
    return [kind for _, kind in sorted(found)]


def device_alarmed(row, alerts, horizon):
    lo, hi = window(row)
    for a in alerts:
        if a.get("ruleId") != "R-01" or a.get("deviceId") != row["device"] or a.get("parentIncidentId"):
            continue
        if overlaps(a["openedAt"], a.get("resolvedAt") or horizon, lo, hi):
            return True
    return False


def predict(row, incidents, alerts, horizon):
    kinds = incident_classes(row, incidents, horizon)
    if kinds:
        return row["expectedClass"] if row["expectedClass"] in kinds else kinds[0]
    if row["expectedClass"] == "device" and row["device"] and device_alarmed(row, alerts, horizon):
        return "device"
    return "none"


def evaluate(truth, incidents, alerts, until=None, since=None):
    """Матрица ошибок и точность; строки эталона — без исключённых классов."""
    def inside(at):
        return (since is None or at >= since) and (until is None or at <= until)

    incidents = [i for i in incidents if inside(i["startedAt"])]
    alerts = [a for a in alerts if inside(a["openedAt"])]
    rows = []
    for t in truth:
        if t["expected_class"] in EXCLUDED:
            continue
        rows.append({"restaurant": t["restaurant"], "device": t["device"], "expectedClass": t["expected_class"],
                     "from": t["from"], "to": t["to"]})
    horizon = max([r["to"] for r in rows] + [0]) + TOLERANCE_MS
    matrix = Counter()
    for r in rows:
        matrix[(r["expectedClass"], predict(r, incidents, alerts, horizon))] += 1
    total = sum(matrix.values())
    correct = sum(n for (e, p), n in matrix.items() if e == p)
    return {"total": total, "correct": correct, "accuracy": (correct / total) if total else None, "matrix": matrix}


def render(result, title, sources):
    lines = [f"# {title}", ""]
    acc = result["accuracy"]
    acc_text = "—" if acc is None else f"{acc * 100:.1f} %"
    lines += [f"Случаев: {result['total']}, верно: {result['correct']}, точность: **{acc_text}** (цель ≥ 90 %).", ""]
    lines += ["Допуск по времени — 3 мин после конца эпизода (только вперёд, реш. 537); класс `degradation` исключён "
              "(R-10 — диплом).", ""]
    lines += ["| эталон \\\\ результат | " + " | ".join(CLASSES) + " |", "|---|" + "---|" * len(CLASSES)]
    for e in CLASSES:
        cells = [str(result["matrix"].get((e, p), 0)) for p in CLASSES]
        lines.append(f"| {e} | " + " | ".join(cells) + " |")
    lines += ["", "Источники:"] + [f"- {s}" for s in sources] + [""]
    return "\n".join(lines)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--truth", required=True)
    p.add_argument("--incidents", required=True)
    p.add_argument("--alerts", required=True)
    p.add_argument("--out")
    p.add_argument("--title", default="Точность классификатора инцидентов")
    p.add_argument("--since", type=int, help="начало прогона, epoch ms")
    p.add_argument("--until", type=int, help="конец прогона, epoch ms")
    args = p.parse_args(argv)
    result = evaluate(
        load_truth(args.truth), load_items(args.incidents), load_items(args.alerts), args.until, args.since,
    )
    text = render(result, args.title, [f"эталон: `{args.truth}`", "инциденты: `GET /api/v1/incidents`",
                                       "тревоги: `GET /api/v1/alerts?rule=R-01&includeSuppressed=true`"])
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
    acc = result["accuracy"]
    print(f"accuracy: {result['correct']}/{result['total']}" + ("" if acc is None else f" = {acc * 100:.1f} %"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
