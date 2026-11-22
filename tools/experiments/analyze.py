"""Обработка эксперимента «MQTT vs REST-polling» (КМ4, план 4.4).

Читает сырые результаты прогонов (logs/experiments/<прогон>/: summary.csv, commands.csv, detection.csv, timeline.csv,
docker-stats.csv, conditions.txt — их пишут симулятор и run-experiment.sh) и выпускает:
  docs/experiments/data/*.csv  — сводные таблицы и сырьё прогонов (без секретов: только deviceId, cmdId и числа);
                                 архивы отменённых серий data/runs-series*.csv скрипт не трогает, а сравнивает с ними;
  docs/experiments/img/*.png   — графики (если установлен matplotlib);
  docs/experiments/results.md  — таблицы, выводы, условия и оговорки. Все числа — только из CSV.

Запуск: python3 -I tools/experiments/analyze.py [--raw logs/experiments] [--out docs/experiments]
matplotlib — из venv вне репозитория: /tmp/rf-venv/bin/python -I tools/experiments/analyze.py
"""
import argparse
import csv
import math
import shutil
import statistics
from pathlib import Path

MODES = [  # фиксированный порядок серий и цветов (категориальная палитра, проверена валидатором dataviz)
    ("mqtt", None, "MQTT", "#2a78d6"),
    ("rest", 5, "REST, опрос 5 с", "#eb6834"),
    ("rest", 15, "REST, опрос 15 с", "#1baf7a"),
    ("rest", 30, "REST, опрос 30 с", "#eda100"),
]
SIZES = [100, 1000, 5000]
SERVER = ["backend", "mosquitto", "postgres"]
CONDITIONS = [("baseline", "без деградации"), ("latency300", "+300 мс (Toxiproxy)"), ("loss5", "потери 5 % (netem)")]
DAY_SEC = 86_400
BASIC_CACHE_TTL_SEC = 300  # TTL кэша Basic бэкенда (реш. 213)
MIB = 1024 * 1024
RAW_FILES = ["summary.csv", "commands.csv", "detection.csv", "timeline.csv", "docker-stats.csv", "conditions.txt"]
KEEPALIVE_LOG = "mosquitto-timeouts.log"  # строки брокера «exceeded timeout» за прогон (пишет драйвер серии)
SERIES1 = ("runs-series1-240s.csv", "серия 1, окно 240 с")  # отменена ревью КМ5 (R-4, R-5)


# ---------- чтение и расчёт ----------

def read_csv(path: Path) -> list:
    if not path.exists():
        return []
    with path.open(newline="") as handle:
        return list(csv.DictReader(handle))


def num(value, default=None):
    try:
        return float(value) if value not in (None, "") else default
    except ValueError:
        return default


def nearest_rank(values: list, percent: float):
    """Тот же метод, что в симуляторе (Percentiles.kt): значение — элемент выборки."""
    if not values:
        return None
    ordered = sorted(values)
    rank = min(max(math.ceil(percent / 100 * len(ordered)), 1), len(ordered))
    return ordered[rank - 1]


def container_role(name: str) -> str:
    for role in SERVER + ["apk-host", "toxiproxy"]:
        if name.startswith(f"restofleet-{role}-"):
            return role
    return "simulator" if "simulator" in name else name


def window_stats(stats: list, start: int, end: int) -> dict:
    """Средние и пиковые CPU/ОЗУ контейнеров по сэмплам внутри окна; NET I/O симулятора — приращение за окно."""
    by_role = {}
    for row in stats:
        ts = int(row["ts_ms"])
        by_role.setdefault(container_role(row["name"]), []).append((ts, row))
    out = {}
    for role, rows in by_role.items():
        inside = [r for ts, r in rows if start <= ts <= end]
        if not inside:
            continue
        cpu = [num(r["cpu_pct"], 0.0) for r in inside]
        mem = [num(r["mem_used_bytes"], 0.0) / MIB for r in inside]
        out[role] = {
            "samples": len(inside), "cpu_mean": statistics.mean(cpu), "cpu_max": max(cpu),
            "mem_mean_mib": statistics.mean(mem), "mem_max_mib": max(mem),
        }
        timed = sorted((ts, r) for ts, r in rows if start <= ts <= end)
        if len(timed) >= 2:
            (t0, first), (t1, last) = timed[0], timed[-1]
            moved = (num(last["net_rx_bytes"], 0) + num(last["net_tx_bytes"], 0)
                     - num(first["net_rx_bytes"], 0) - num(first["net_tx_bytes"], 0))
            out[role]["net_bytes_per_sec"] = moved / ((t1 - t0) / 1000) if t1 > t0 else None
    return out


def keepalive_timeouts(directory: Path, lost_ids: set) -> list:
    """(deviceId, секунда брокера) отключений «exceeded timeout» пропавших устройств; журнал Mosquitto — точность 1 с."""
    path = directory / KEEPALIVE_LOG
    if not path.exists():
        # обработка из копии docs/experiments/data/raw/<прогон>/ — уже разобранный CSV
        return [(r["device_id"], int(r["broker_ts_sec"])) for r in read_csv(directory / "keepalive-timeouts.csv")
                if r["device_id"] in lost_ids]
    out = []
    for line in path.read_text().splitlines():
        head, _, rest = line.partition(": Client ")
        device = rest.split(" ", 1)[0]
        if head.isdigit() and device in lost_ids and "exceeded timeout" in rest:
            out.append((device, int(head)))
    return out


def load_run(directory: Path) -> dict:
    rows = read_csv(directory / "summary.csv")
    if not rows:
        return None
    s = rows[0]
    commands = read_csv(directory / "commands.csv")
    detection = read_csv(directory / "detection.csv")
    stats = read_csv(directory / "docker-stats.csv")
    start, end = int(s["window_start_ms"]), int(s["window_end_ms"])
    window = (end - start) / 1000
    devices = int(s["devices"])
    connected = int(s["connected_at_window_start"]) or devices
    rx, tx = num(s["rx_bytes"]), num(s["tx_bytes"])
    latencies = [num(c["latency_ms"]) for c in commands if c["latency_ms"]]
    detected = [num(d["offline_after_ms"]) for d in detection if d["offline_after_ms"]]
    poll = int(s["poll_interval_sec"]) if s["poll_interval_sec"] else None
    server = window_stats(stats, start, end)
    per_sample = {}
    for row in stats:
        ts = int(row["ts_ms"])
        if start <= ts <= end and container_role(row["name"]) in SERVER:
            per_sample[ts] = per_sample.get(ts, 0.0) + num(row["cpu_pct"], 0.0)
    # команды REST дольше 1,1 интервала опроса и были ли в это время всплески CPU сервера (сэмпл > 3 медиан окна)
    median_cpu = statistics.median(per_sample.values()) if per_sample else None
    burst_ts = [ts for ts, v in per_sample.items() if median_cpu and v > 3 * median_cpu]
    late = [c for c in commands if poll and c["latency_ms"] and num(c["latency_ms"]) > 1.1 * poll * 1000]
    late_in_burst = [c for c in late
                     if any(int(c["sent_at_ms"]) - 5000 <= ts <= int(c["sent_at_ms"]) + num(c["latency_ms"])
                            for ts in burst_ts)]
    timeouts = keepalive_timeouts(directory, {d["device_id"] for d in detection})
    per_second = {}
    for _, second in timeouts:
        per_second[second] = per_second.get(second, 0) + 1
    run = {
        "name": s["name"], "mode": s["mode"], "poll": poll, "network": s["network"], "devices": devices,
        "connected": connected, "connected_end": int(s["connected_at_window_end"]), "window_sec": window,
        "rx_bytes": rx, "tx_bytes": tx,
        "bytes_per_device_day": (rx + tx) / connected / window * DAY_SEC if rx is not None else None,
        "rx_per_device_day": rx / connected / window * DAY_SEC if rx is not None else None,
        "tx_per_device_day": tx / connected / window * DAY_SEC if tx is not None else None,
        "payload_per_device_day": (num(s["payload_bytes_out"]) + num(s["payload_bytes_in"])) / connected / window * DAY_SEC,
        "msgs_per_sec": (num(s["msgs_out"]) + num(s["msgs_in"])) / window,
        "requests_per_sec": num(s["requests"]) / window,
        "request_errors": int(s["request_errors"]), "publish_errors": int(s["publish_errors"]),
        "connect_failures": int(s["connect_failures"]), "connection_losses": int(s["connection_losses"]),
        "commands_sent": len(commands), "send_failures": int(s["command_send_failures"]),
        "commands_received": len(latencies),
        "commands_succeeded": sum(1 for c in commands if c["final_status"] == "succeeded"),
        "latency_p50": nearest_rank(latencies, 50), "latency_p95": nearest_rank(latencies, 95),
        "latency_max": max(latencies) if latencies else None,
        "loss_devices": len(detection), "loss_detected": len(detected),
        "detection_median": statistics.median(detected) if detected else None,
        "detection_min": min(detected) if detected else None, "detection_max": max(detected) if detected else None,
        "server": server,
        "server_cpu_mean": sum(server[r]["cpu_mean"] for r in SERVER if r in server),
        "server_mem_mean_mib": sum(server[r]["mem_mean_mib"] for r in SERVER if r in server),
        "server_cpu_median": median_cpu,
        "late_commands": len(late) if poll else None, "late_in_cpu_burst": len(late_in_burst) if poll else None,
        "server_cpu_max": max(per_sample.values()) if per_sample else None,
        "cpu_samples": len(per_sample),
        "keepalive_timeouts": len(timeouts) if timeouts else None,
        "keepalive_same_second_max": max(per_second.values()) if per_second else None,
        "keepalive_timeouts_list": timeouts,
        "sim_cpu_mean": server.get("simulator", {}).get("cpu_mean"),
        "sim_net_per_device_day": (server.get("simulator", {}).get("net_bytes_per_sec") or 0) / connected * DAY_SEC
        if server.get("simulator", {}).get("net_bytes_per_sec") else None,
        "commands": commands, "detection": detection, "dir": directory,
    }
    return run


def find(runs: dict, mode: str, poll, devices: int, network: str = "baseline"):
    for run in runs.values():
        if run["mode"] == mode and run["poll"] == poll and run["devices"] == devices and run["network"] == network:
            return run
    return None


# ---------- форматирование ----------

def fmt(value, digits=1, unit=""):
    if value is None:
        return "—"
    text = f"{value:,.{digits}f}".replace(",", " ").replace(".", ",")
    return f"{text}{unit}"


def fmt_mb(value):
    return fmt(value / 1e6 if value is not None else None, 2)


def fmt_ms(value):
    """Задержка с единицей: до секунды — «11,2 мс», дальше — «2,29 с»."""
    if value is None:
        return "—"
    return fmt(value, 1, " мс") if value < 1000 else fmt(value / 1000, 2, " с")


def table(header: list, rows: list) -> str:
    lines = ["| " + " | ".join(header) + " |", "|" + "|".join("---" for _ in header) + "|"]
    lines += ["| " + " | ".join(str(c) for c in row) + " |" for row in rows]
    return "\n".join(lines)


# ---------- выходные данные ----------

RUN_FIELDS = [
    "name", "mode", "poll", "network", "devices", "connected", "connected_end", "window_sec", "rx_bytes", "tx_bytes",
    "bytes_per_device_day", "rx_per_device_day", "tx_per_device_day", "payload_per_device_day",
    "sim_net_per_device_day", "msgs_per_sec", "requests_per_sec", "request_errors", "publish_errors",
    "connect_failures", "connection_losses", "commands_sent", "send_failures", "commands_received",
    "commands_succeeded", "latency_p50", "latency_p95", "latency_max", "loss_devices", "loss_detected",
    "detection_median", "detection_min", "detection_max", "server_cpu_mean", "server_cpu_median", "server_cpu_max",
    "cpu_samples", "server_mem_mean_mib", "sim_cpu_mean", "keepalive_timeouts", "keepalive_same_second_max",
    "late_commands", "late_in_cpu_burst",
]


def write_data(runs: dict, out: Path) -> None:
    data = out / "data"
    data.mkdir(parents=True, exist_ok=True)
    # только свои выходы: архивы серий (runs-series*.csv) остаются
    for name in ("runs.csv", "server-load.csv"):
        (data / name).unlink(missing_ok=True)
    if (data / "raw").exists():
        shutil.rmtree(data / "raw")
    (data / "raw").mkdir()
    ordered = sorted(runs.values(), key=lambda r: (r["network"] != "baseline", r["devices"], r["mode"], r["poll"] or 0))
    with (data / "runs.csv").open("w", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(RUN_FIELDS)
        for run in ordered:
            writer.writerow([round(run[f], 3) if isinstance(run[f], float) else run[f] for f in RUN_FIELDS])
    with (data / "server-load.csv").open("w", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(["run", "container", "samples", "cpu_mean_pct", "cpu_max_pct", "mem_mean_mib", "mem_max_mib"])
        for run in ordered:
            for role, st in sorted(run["server"].items()):
                writer.writerow([run["name"], role, st["samples"], round(st["cpu_mean"], 2), round(st["cpu_max"], 2),
                                 round(st["mem_mean_mib"], 1), round(st["mem_max_mib"], 1)])
    for run in ordered:
        target = data / "raw" / run["name"]
        target.mkdir()
        for name in RAW_FILES:
            if (run["dir"] / name).exists():
                shutil.copy(run["dir"] / name, target / name)
        if run["keepalive_timeouts_list"]:
            with (target / "keepalive-timeouts.csv").open("w", newline="") as handle:
                writer = csv.writer(handle, lineterminator="\n")
                writer.writerow(["device_id", "broker_ts_sec"])
                writer.writerows(sorted(run["keepalive_timeouts_list"], key=lambda t: (t[1], t[0])))


# ---------- графики ----------

def charts(runs: dict, out: Path) -> list:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.patches  # noqa: F401 — Patch для легенды
        import matplotlib.pyplot as plt
        import matplotlib.ticker  # noqa: F401
    except ImportError:
        return []
    img = out / "img"
    if img.exists():
        shutil.rmtree(img)
    if not any(find(runs, m, p, n) for m, p, _, _ in MODES for n in SIZES):
        return []
    img.mkdir(parents=True)
    plt.rcParams.update({
        "font.family": "DejaVu Sans", "font.size": 9, "axes.edgecolor": "#8a8984", "axes.labelcolor": "#52514e",
        "xtick.color": "#52514e", "ytick.color": "#52514e", "axes.spines.top": False, "axes.spines.right": False,
        "axes.grid": True, "axes.grid.axis": "y", "grid.color": "#e4e3df", "grid.linewidth": 0.6,
        "axes.axisbelow": True, "figure.facecolor": "#fcfcfb", "axes.facecolor": "#fcfcfb", "savefig.dpi": 200,
        "legend.frameon": False,
    })
    made = []

    def grouped(ax, metric, label_fn, log=False):
        width = 0.2
        for i, (mode, poll, label, color) in enumerate(MODES):
            xs, ys = [], []
            for j, n in enumerate(SIZES):
                run = find(runs, mode, poll, n)
                if run and run.get(metric) is not None:
                    xs.append(j + (i - 1.5) * width)
                    ys.append(run[metric])
            bars = ax.bar(xs, ys, width * 0.92, color=color, label=label, edgecolor="#fcfcfb", linewidth=1)
            for bar, y in zip(bars, ys):
                ax.annotate(label_fn(y), (bar.get_x() + bar.get_width() / 2, y), xytext=(0, 2),
                            textcoords="offset points", ha="center", va="bottom", fontsize=6.5, color="#52514e")
        ax.set_xticks(range(len(SIZES)), [f"N = {n:,}".replace(",", " ") for n in SIZES])
        ax.set_xlim(-0.5, len(SIZES) - 0.5)
        if log:
            ax.set_yscale("log")
            ax.yaxis.set_minor_formatter(matplotlib.ticker.NullFormatter())
            ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: fmt(v, 0)))

    def legend(fig):
        handles = [matplotlib.patches.Patch(color=color, label=label) for _, _, label, color in MODES]
        fig.legend(handles=handles, ncol=4, loc="lower center", fontsize=8)
        fig.bottom_legend = True

    def save(fig, name):
        fig.tight_layout(rect=(0, 0.07, 1, 1) if getattr(fig, "bottom_legend", False) else None)
        fig.savefig(img / name)
        plt.close(fig)
        made.append(name)

    fig, ax = plt.subplots(figsize=(7.5, 4))
    grouped(ax, "bytes_per_device_day", lambda v: fmt(v / 1e6, 2))
    ax.set_ylabel("МБ в сутки на устройство")
    ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: fmt(v / 1e6, 0)))
    ax.set_title("Трафик устройства (IP-байты интерфейса, экстраполяция окна на сутки)", loc="left", fontsize=10)
    legend(fig)
    save(fig, "traffic.png")

    # Задержки от 10 мс до 30 с — лог. шкала; на ней столбцы искажают пропорции, поэтому точки с подписями
    fig, axes = plt.subplots(1, 2, figsize=(7.5, 3.8), sharey=True)
    for ax, metric, title in [(axes[0], "latency_p50", "p50"), (axes[1], "latency_p95", "p95")]:
        for i, (mode, poll, label, color) in enumerate(MODES):
            for j, n in enumerate(SIZES):
                run = find(runs, mode, poll, n)
                if run and run.get(metric) is not None:
                    x, y = j + (i - 1.5) * 0.2, run[metric]
                    ax.plot([x], [y], "o", color=color, markersize=6, markeredgecolor="#fcfcfb", markeredgewidth=1)
                    ax.annotate(fmt(y, 0) if y < 1000 else fmt(y / 1000, 1, " с"), (x, y), xytext=(0, 5),
                                textcoords="offset points", ha="center", fontsize=6, color="#52514e")
        ax.set_xticks(range(len(SIZES)), [f"N = {n:,}".replace(",", " ") for n in SIZES])
        ax.set_xlim(-0.5, len(SIZES) - 0.5)
        ax.set_yscale("log")
        ax.yaxis.set_minor_formatter(matplotlib.ticker.NullFormatter())
        ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: fmt(v, 0)))
        ax.set_title(f"Задержка команды, {title}", loc="left", fontsize=10)
    axes[0].set_ylabel("мс (лог. шкала)")
    legend(fig)
    save(fig, "latency.png")

    fig, ax = plt.subplots(figsize=(7.5, 4))
    grouped(ax, "server_cpu_mean", lambda v: fmt(v, 0))
    ax.set_ylabel("% одного ядра (среднее за окно)")
    ax.set_title("CPU сервера: backend + mosquitto + postgres", loc="left", fontsize=10)
    legend(fig)
    save(fig, "server-cpu.png")

    fig, ax = plt.subplots(figsize=(7.5, 4))
    grouped(ax, "server_mem_mean_mib", lambda v: fmt(v, 0))
    ax.set_ylabel("МиБ (среднее за окно)")
    ax.set_title("ОЗУ сервера: backend + mosquitto + postgres", loc="left", fontsize=10)
    legend(fig)
    save(fig, "server-mem.png")

    fig, ax = plt.subplots(figsize=(7.5, 4))
    grouped(ax, "detection_median", lambda v: fmt(v / 1000, 0))
    for i, (mode, poll, _, color) in enumerate(MODES):
        for j, n in enumerate(SIZES):
            run = find(runs, mode, poll, n)
            if run and run["detection_min"] is not None:
                x = j + (i - 1.5) * 0.2
                ax.plot([x, x], [run["detection_min"], run["detection_max"]], color="#52514e", linewidth=1)
    ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: fmt(v / 1000, 0)))
    ax.set_ylabel("секунды (медиана; черта — мин…макс)")
    ax.set_title("Время обнаружения пропажи устройства", loc="left", fontsize=10)
    legend(fig)
    save(fig, "detection.png")

    pairs = [("mqtt", None, "MQTT", MODES[0][3]), ("rest", 15, "REST, опрос 15 с", MODES[2][3])]
    if any(find(runs, m, p, 1000, net) for m, p, _, _ in pairs for net, _ in CONDITIONS[1:]):
        fig, axes = plt.subplots(1, 2, figsize=(7.5, 3.6))
        for ax, (mode, poll, label, color) in zip(axes, pairs):
            names, p50, p95 = [], [], []
            for net, title in CONDITIONS:
                run = find(runs, mode, poll, 1000, net)
                if run and run["latency_p50"] is not None:
                    names.append(title)
                    p50.append(run["latency_p50"])
                    p95.append(run["latency_p95"])
            xs = range(len(names))
            ax.bar([x - 0.18 for x in xs], p50, 0.34, color=color, edgecolor="#fcfcfb")
            ax.bar([x + 0.18 for x in xs], p95, 0.34, color=color, alpha=0.45, edgecolor="#fcfcfb", hatch="////")
            for x, a, b in zip(xs, p50, p95):
                ax.annotate(fmt_ms(a), (x - 0.18, a), xytext=(0, 2), textcoords="offset points", ha="center",
                            fontsize=6.5, color="#52514e")
                ax.annotate(fmt_ms(b), (x + 0.18, b), xytext=(0, 2), textcoords="offset points", ha="center",
                            fontsize=6.5, color="#52514e")
            ax.set_xticks(list(xs), names, fontsize=7)
            ax.set_title(f"{label}, N = 1 000", loc="left", fontsize=10)
            if mode == "rest":
                ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: fmt(v / 1000, 0)))
                ax.set_ylabel("задержка команды, с")
            else:
                ax.set_ylabel("задержка команды, мс")
        fig.legend(handles=[matplotlib.patches.Patch(color="#8a8984", label="p50"),
                            matplotlib.patches.Patch(facecolor="#8a8984", alpha=0.45, hatch="////", label="p95")],
                   ncol=2, loc="lower center", fontsize=8)
        fig.bottom_legend = True
        save(fig, "degradation.png")

    timeline_runs = [r for r in (find(runs, "mqtt", None, 5000), find(runs, "rest", 5, 5000)) if r]
    if timeline_runs:
        fig, axes = plt.subplots(1, len(timeline_runs), figsize=(7.5, 3.4), sharey=True, squeeze=False)
        for ax, run in zip(axes[0], timeline_runs):
            stats = read_csv(run["dir"] / "docker-stats.csv")
            t0 = min(int(r["ts_ms"]) for r in stats)
            for role, color in [("backend", "#2a78d6"), ("mosquitto", "#eb6834"), ("postgres", "#1baf7a")]:
                pts = [((int(r["ts_ms"]) - t0) / 1000, num(r["cpu_pct"], 0)) for r in stats
                       if container_role(r["name"]) == role]
                ax.plot([p[0] for p in pts], [p[1] for p in pts], color=color, linewidth=1.6, label=role)
            summary = read_csv(run["dir"] / "summary.csv")[0]
            ws, we = (int(summary["window_start_ms"]) - t0) / 1000, (int(summary["window_end_ms"]) - t0) / 1000
            ax.axvspan(ws, we, color="#e4e3df", alpha=0.5, linewidth=0)
            ax.set_title(f"{'MQTT' if run['mode'] == 'mqtt' else 'REST, опрос 5 с'}, N = 5 000", loc="left", fontsize=10)
            ax.set_xlabel("секунды от старта (серым — окно замера)")
        axes[0][0].set_ylabel("CPU, % одного ядра")
        axes[0][0].legend(fontsize=8)
        save(fig, "timeline-5000.png")
    return made


# ---------- отчёт ----------

def ratio(a, b):
    return a / b if a and b else None


def results_md(runs: dict, images: list, archive: dict) -> str:
    any_run = next(iter(runs.values()))
    # Окно замера — из данных (КМ4: 240 с; с ревью КМ5, R-5 — не меньше двух TTL кэша Basic, 600 с)
    window_sec = round(sorted(r["window_sec"] for r in runs.values())[len(runs) // 2])
    window = fmt(window_sec, 0)
    short_window = window_sec < 2 * BASIC_CACHE_TTL_SEC
    ttl_multiple = window_sec / BASIC_CACHE_TTL_SEC
    commands_per_run = sorted(r["commands_sent"] for r in runs.values())[len(runs) // 2]
    conditions = (any_run["dir"] / "conditions.txt").read_text().splitlines() if (any_run["dir"] / "conditions.txt").exists() else []
    host = next((c.split(": ", 1)[1] for c in conditions if c.startswith("host:")), "—")
    docker = next((c.split(": ", 1)[1] for c in conditions if c.startswith("docker:")), "—")
    sim = next((c.split(": ", 1)[1] for c in conditions if c.startswith("simulator:")), "—")
    dates = sorted((r["dir"] / "conditions.txt").read_text().splitlines()[0].split(": ", 1)[1]
                   for r in runs.values() if (r["dir"] / "conditions.txt").exists())
    out = ["# Эксперимент «MQTT vs REST-polling» — результаты",
           "",
           "> Сгенерировано `tools/experiments/analyze.py` из CSV прогонов (`docs/experiments/data/`). Числа в этом файле "
           "не правятся руками: изменились CSV — перезапустить скрипт. Методика и решения — `docs/reports/1-coursework/K4.md`, "
           "`decisions.md` (реш. 279–291; ревью КМ5 — 309, 310; серия 2 — 314–317).",
           "",
           "## Условия",
           "",
           f"- **Машина:** {host}; Docker Desktop {docker}. Сервер (backend, mosquitto, postgres) и симулятор — на одной "
           "машине, в одной виртуальной машине Docker.",
           f"- **Симулятор:** контейнер `simulator` (compose, profile `experiment`), ограничения {sim}; один процесс JVM, "
           "клиенты HiveMQ на общем event loop (MQTT) или OkHttp с отдельным keep-alive соединением на устройство (REST).",
           f"- **Прогоны:** {len(runs)}, с {dates[0] if dates else '—'} по {dates[-1] if dates else '—'} (UTC). Каждый прогон: "
           f"разгон 100 подключений/с, прогрев 30 с, **окно замера {window} с**, затем добор команд и замер пропажи. "
           "Телеметрия раз в 30 с (MQTT — из тени, REST — из сценария), команды `ping` 60 в минуту случайным устройствам "
           f"({commands_per_run} за окно)." + ("" if short_window else " Вход измерителя (admin) — в фазе прогрева, до "
                                                 "окна (реш. 309)."),
           "- **Трафик** — IP-байты интерфейса устройств в контейнере симулятора (`/sys/class/net/*/statistics`), то есть "
           "вместе с заголовками TCP/IP, TLS (MQTT), keep-alive и ACK; измеритель ходит к бэкенду через отдельную сеть и в "
           f"счётчик не попадает. «В сутки» — линейная экстраполяция окна {window} с (реш. 209).",
           "- **Задержка команды** — от момента перед `POST /devices/{id}/commands` до получения команды устройством "
           "(MQTT — колбэк HiveMQ, REST — ответ опроса), обе отметки — монотонные часы одного процесса; перцентили — "
           "ближайший ранг.",
           "- **CPU/ОЗУ сервера** — `docker stats --no-stream` раз в 5 с (`docker-stats.csv`); CPU — проценты одного ядра, "
           "сумма трёх контейнеров по сэмплу: среднее, медиана и пик (максимум) по сэмплам окна.",
           "- **Пропажа** — 10 последних устройств прогона замолкают без DISCONNECT и без закрытия TCP; `GET /devices/{id}` "
           "раз в секунду до `offline`. MQTT — LWT брокера по keep-alive (30 с × 1,5), REST — планировщик бэкенда "
           "(нет опроса дольше 3 × интервал опроса, проверка раз в 5 с, реш. 214).",
           ""]

    out += ["## 1. Трафик на устройство", "",
            f"МБ в сутки на устройство (приём + передача), экстраполяция окна {window} с; в скобках — доля передачи "
            "устройства.", ""]
    rows = []
    for mode, poll, label, _ in MODES:
        row = [label]
        for n in SIZES:
            run = find(runs, mode, poll, n)
            if run and run["bytes_per_device_day"]:
                share = run["tx_per_device_day"] / run["bytes_per_device_day"] * 100
                row.append(f"{fmt_mb(run['bytes_per_device_day'])} ({fmt(share, 0)} %)")
            else:
                row.append("—")
        rows.append(row)
    out += [table(["Режим"] + [f"N = {fmt(n, 0)}" for n in SIZES], rows), ""]
    rows = []
    for mode, poll, label, _ in MODES:
        run = find(runs, mode, poll, 1000)
        if run:
            rows.append([label, fmt_mb(run["payload_per_device_day"]), fmt_mb(run["bytes_per_device_day"]),
                         fmt_mb(run["sim_net_per_device_day"])])
    out += ["N = 1 000: полезная нагрузка приложения (JSON сообщений и тел HTTP) против IP-байтов интерфейса и NET I/O "
            "контейнера по `docker stats` (сверка; включает и трафик измерителя), МБ/сутки на устройство:", "",
            table(["Режим", "полезная нагрузка", "IP-байты интерфейса", "docker stats NET I/O"], rows), ""]
    if "traffic.png" in images:
        out += ["![Трафик на устройство](img/traffic.png)", ""]

    out += ["## 2. Задержка доставки команды", "", "p50 / p95 / максимум; «доставлено» — получено устройством из отправленных.", ""]
    rows = []
    for mode, poll, label, _ in MODES:
        for n in SIZES:
            run = find(runs, mode, poll, n)
            if run:
                rows.append([label, fmt(n, 0), fmt_ms(run["latency_p50"]), fmt_ms(run["latency_p95"]),
                             fmt_ms(run["latency_max"]), f"{run['commands_received']}/{run['commands_sent']}",
                             f"{run['commands_succeeded']}/{run['commands_sent']}"])
    out += [table(["Режим", "N", "p50", "p95", "макс.", "доставлено", "`succeeded`"], rows), ""]
    if "latency.png" in images:
        out += ["![Задержка команды](img/latency.png)", ""]

    out += ["## 3. Нагрузка на сервер", "",
            "Среднее за окно: CPU — % одного ядра, ОЗУ — МиБ; поток — сообщений/с (MQTT, в обе стороны) или успешных "
            "запросов/с (REST). Для суммы CPU трёх контейнеров — среднее, медиана и пик по сэмплам окна (раз в 5 с).", ""]
    if not short_window:
        out += [f"Окно {window} с — {fmt(ttl_multiple, 0)} TTL кэша Basic (300 с, реш. 213, 310). REST-устройство проходит "
                "полную проверку bcrypt при первом запросе и затем раз в TTL плюс ожидание следующего запроса, поэтому в "
                f"окно, кратное TTL, попадает одинаковое число повторных проверок (≈ {fmt(ttl_multiple, 0)} на устройство) "
                "при любой фазе окна. **Среднее за окно — стационарная оценка нагрузки**: она включает периодическую "
                "проверку bcrypt в той доле, которая была бы и в сутках работы. Медиана — «фон» между проверками, пик — "
                "высота всплеска: после массового подключения проверки синхронны, и у REST при больших N всплески видны "
                "на графике CPU во времени. У MQTT пароль проверяется только при подключении (до окна).", ""]
    rows = []
    for mode, poll, label, _ in MODES:
        for n in SIZES:
            run = find(runs, mode, poll, n)
            if not run:
                continue
            srv = run["server"]
            cells = [label, fmt(n, 0)]
            for role in SERVER:
                st = srv.get(role)
                cells.append(f"{fmt(st['cpu_mean'], 1)} / {fmt(st['mem_mean_mib'], 0)}" if st else "—")
            cells.append(f'{fmt(run["server_cpu_mean"], 1)} / {fmt(run["server_cpu_median"], 1)} / '
                         f'{fmt(run.get("server_cpu_max"), 1)}')
            flow = run["msgs_per_sec"] if mode == "mqtt" else run["requests_per_sec"]
            cells.append(fmt(flow, 1))
            cells.append(fmt(run["sim_cpu_mean"], 0))
            rows.append(cells)
    out += [table(["Режим", "N", "backend CPU / ОЗУ", "mosquitto CPU / ОЗУ", "postgres CPU / ОЗУ",
                   "CPU сумма: среднее / медиана / пик",
                   "поток, 1/с", "CPU симулятора"], rows), ""]
    for name in ("server-cpu.png", "server-mem.png", "timeline-5000.png"):
        if name in images:
            out += [f"![{name}](img/{name})", ""]

    out += ["## 4. Время обнаружения пропажи", "", "Медиана (мин…макс), секунды; обнаружено — из 10.", ""]
    rows = []
    for mode, poll, label, _ in MODES:
        row = [label]
        for n in SIZES:
            run = find(runs, mode, poll, n)
            if run and run["detection_median"] is not None:
                row.append(f"{fmt(run['detection_median'] / 1000, 1)} ({fmt(run['detection_min'] / 1000, 1)}…"
                           f"{fmt(run['detection_max'] / 1000, 1)}), {run['loss_detected']}/{run['loss_devices']}")
            elif run:
                row.append(f"не обнаружено, 0/{run['loss_devices']}")
            else:
                row.append("—")
        rows.append(row)
    out += [table(["Режим"] + [f"N = {fmt(n, 0)}" for n in SIZES], rows), ""]
    if "detection.png" in images:
        out += ["![Время обнаружения](img/detection.png)", ""]

    out += ["## 5. Деградация сети, N = 1 000", "",
            "Toxiproxy: +300 мс на каждый ответ сервера (latency, downstream) перед брокером (`mqtt`) и бэкендом (`rest`); "
            "потери: `tc netem loss 5%` на исходящем интерфейсе устройств в контейнере симулятора.", ""]
    rows = []
    for mode, poll, label in [("mqtt", None, "MQTT"), ("rest", 15, "REST, опрос 15 с")]:
        for net, title in CONDITIONS:
            run = find(runs, mode, poll, 1000, net)
            if run:
                rows.append([label, title, fmt_ms(run["latency_p50"]), fmt_ms(run["latency_p95"]),
                             f"{run['commands_received']}/{run['commands_sent']}", fmt_mb(run["bytes_per_device_day"]),
                             run["connection_losses"], run["publish_errors"] + run["request_errors"],
                             fmt(run["server_cpu_mean"], 1)])
    out += [table(["Режим", "Сеть", "p50", "p95", "доставлено", "МБ/сутки", "обрывов", "ошибок отправки",
                   "CPU сервера"], rows), ""]
    if "degradation.png" in images:
        out += ["![Деградация](img/degradation.png)", ""]

    keepalive = [(n, r) for n in SIZES for r in [find(runs, "mqtt", None, n)] if r and r["detection_min"] is not None]
    spread = "; ".join(f"N = {fmt(n, 0)} — {fmt(r['detection_min'] / 1000, 1)}…{fmt(r['detection_max'] / 1000, 1)} с"
                       for n, r in keepalive)
    batched = [(n, r) for n, r in keepalive if r.get("keepalive_same_second_max")]
    if batched:
        keepalive_text = (
            "Mosquitto 2.1 проверяет keep-alive пачками: по журналу брокера (`raw/<прогон>/keepalive-timeouts.csv`, "
            "точность 1 с) в одну секунду отключены «exceeded timeout» " + "; ".join(
                f"{r['keepalive_same_second_max']} из {r['keepalive_timeouts']} пропавших при N = {fmt(n, 0)}"
                for n, r in batched)
            + ", хотя последний пакет у них был в разное время (фаза телеметрии случайна), — отсюда разный разброс по "
            "прогонам: " + spread + ".")
    else:
        keepalive_text = "Разброс по прогонам: " + spread + "."
    headers_note = rate_limit_headers(runs, archive)
    out += ["## Выводы", ""] + conclusions(runs) + [""]
    out += ["## Оговорки", "",
            f"- **Сокращённые прогоны** (реш. 209): окно {window} с вместо суток; «в сутки» — линейная экстраполяция. Суточные "
            "эффекты (переподключения, ротация сессий, рост БД) не измерены.",
            "- **Одна машина:** сервер и симулятор делят CPU и сеть одной виртуальной машины Docker; сеть — мост Docker без "
            "реальной задержки. Абсолютные числа CPU — для этой машины; сравнимы между режимами, а не с продом.",
            "- **REST без TLS:** устройства в REST-режиме ходят к бэкенду по HTTP (`backend:8080`, как задумано контрактом "
            "K2), MQTT — по TLS 8883. Сравнение консервативно в пользу REST: с HTTPS трафик и CPU REST были бы выше "
            "(реш. 282).",
            "- **Задержка MQTT** включает вызов REST API бэкенда (запись команды в БД + публикация), а не только доставку "
            "брокером; задержка REST — в основном ожидание следующего опроса (≈ половина интервала в среднем).",
            "- **Пропажа MQTT** считается от момента замолкания: брокер разрывает соединение через 1,5 × keep-alive "
            "(45 с) от последнего пакета устройства (плюс шаг опроса статуса 1 с), поэтому от момента пропажи время "
            "бывает и меньше 45 с. " + keepalive_text,
            "- **Потери 5 %** — только на исходящем трафике устройств (`netem` на egress, реш. 291: `ifb` для входящего "
            "направления в ядре Docker Desktop нет); входящие пакеты не теряются, но потеря ACK устройства вызывает "
            "повторы и со стороны сервера. Влияние потерь на задержку команды (сервер → устройство) поэтому занижено.",
            "- **Деградация** проверена только на N = 1 000 и REST 15 с (реш. 284); +300 мс — на ответ сервера (RTT +300 мс).",
            ] + ([headers_note] if headers_note else []) + [
            "- **Команды измерителя** (60 в минуту на весь парк, реш. 287) входят в трафик устройств: на устройство их тем "
            "больше, чем меньше N, поэтому трафик на устройство сравним между режимами при одном N, а между N — с этой "
            "поправкой (при N = 100 команд на устройство в 10 раз больше, чем при N = 1 000).",
            "- **Бэкенд пересоздаётся** перед прогоном, если меняется порог presence REST (реш. 289): JIT и кэш Basic "
            "прогреваются в фазе разгона и прогрева, до окна. Кэш Basic живёт 5 мин от успешной проверки (реш. 213), "
            "одинаково для всех устройств: после массового подключения REST-парка повторные проверки bcrypt идут "
            "синхронным всплеском раз в ≈ 5 мин (разброс TTL — этап П, `K5.md`). На реальном парке подключения "
            "размазаны во времени, и пик был бы ниже, а среднее — тем же.",
            "- **REST-доставка команды — «не больше одного раза»** (ревью КМ5, R-9, реш. 312): `GET /poll/commands` "
            "переводит команду в `delivered` в той же транзакции, в которой выдаёт её, — до того как ответ дошёл до "
            "устройства. Обрыв после commit оставляет команду `delivered`: повторно она не выдаётся и истекает. MQTT "
            "(QoS 1, постоянная сессия) доставляет «хотя бы один раз». В прогонах потерь не было, но сравнение "
            "надёжности протоколов опирается на эту асимметрию.",
            ] + ([
                f"- **Окно {window} с короче двух TTL кэша Basic** (ревью КМ5, R-5): при N ≤ 1 000 в окно не попадает ни "
                "одной повторной bcrypt-проверки (CPU REST занижен против установившегося режима ≈ N/300 проверок в "
                "секунду), при N = 5 000 — синхронный всплеск истечения кэша (CPU REST завышен). Вывод о CPU REST — "
                "предварительный; перепрогон — с окном 600 с (реш. 310).",
                "- **Первая команда каждого прогона** включала вход измерителя (`/auth/login`, bcrypt; ревью КМ5, R-4): "
                "«макс.» и среднее задержки MQTT завышены одной выборкой из 240, p50 и p95 не затронуты. С реш. 309 вход "
                "выполняется в фазе прогрева.",
            ] if short_window else [
                f"- **Окно {window} с — {fmt(ttl_multiple, 0)} TTL кэша Basic** (реш. 310): среднее CPU REST включает "
                "стационарную долю повторных проверок bcrypt; вход измерителя — в фазе прогрева, до окна (реш. 309), "
                "поэтому «макс.» задержки — тоже по командам окна.",
            ]) + [""]
    out += series1_section(runs, archive)
    return "\n".join(out)


def rx_per_request(run: dict):
    """Байт приёма устройства на успешный запрос REST (по полям runs.csv; годится и для архива серии)."""
    rx, rps, window = num(run.get("rx_bytes")), num(run.get("requests_per_sec")), num(run.get("window_sec"))
    return rx / (rps * window) if rx and rps and window else None


def rate_limit_headers(runs: dict, archive: dict):
    """Оговорка о заголовках X-RateLimit-* (R-7): приём REST на запрос серии 2 против серии 1."""
    if not archive:
        return None
    deltas, growth, mqtt_growth = [], [], []
    for run in runs.values():
        old = archive.get(run["name"])
        if run["mode"] == "mqtt" and old:
            mqtt_growth.append(run["bytes_per_device_day"] / num(old["bytes_per_device_day"]) - 1)
        if run["mode"] != "rest" or not old:
            continue
        a, b = rx_per_request(old), rx_per_request(run)
        if a and b:
            deltas.append(b - a)
            growth.append(run["bytes_per_device_day"] / num(old["bytes_per_device_day"]) - 1)
    if not deltas:
        return None
    return ("- **Заголовки `X-RateLimit-*` у REST** (ревью КМ5, R-7, реш. 306, 317): маршруты опроса обёрнуты в плагин "
            "RateLimit Ktor (лимит неудачных Basic), и он добавляет к каждому ответу `X-RateLimit-Limit`, `-Remaining`, "
            f"`-Reset` (81 Б). Приём устройства на запрос против серии 1 вырос на {fmt(min(deltas), 0)}…"
            f"{fmt(max(deltas), 0)} Б, трафик REST на устройство — на {fmt(min(growth) * 100, 0)}…"
            f"{fmt(max(growth) * 100, 0)} %, у MQTT изменился на {fmt(min(mqtt_growth or [0]) * 100, 1)}…"
            f"{fmt(max(mqtt_growth or [0]) * 100, 1)} %. Числа выше — для бэкенда как он есть; без этих "
            "заголовков трафик REST был бы ближе к серии 1, отношения REST/MQTT — чуть меньше.")


def series1_section(runs: dict, archive: dict) -> list:
    """Сравнение с отменённой серией 1 (окно 240 с) по архиву data/runs-series1-240s.csv."""
    if not archive:
        return []
    old = lambda name, field: num(archive.get(name, {}).get(field))  # noqa: E731
    old5, oldm = old("rest5-5000", "server_cpu_mean"), old("mqtt-5000", "server_cpu_mean")
    r1 = ratio(old5, oldm)
    out = ["## Серия 1 (отменена)", "",
           "Первая серия (КМ4, 11.11.2026, окно 240 с) отменена ревью КМ5 (`docs/reports/1-coursework/review.md`):",
           "",
           "- **R-4** — первая команда каждого прогона включала вход измерителя (`/auth/login`, bcrypt): «макс.» и среднее "
           "задержки MQTT были завышены одной выборкой.",
           "- **R-5** — окно 240 с было короче TTL кэша Basic (300 с): при N ≤ 1 000 в окно не попадала ни одна повторная "
           "проверка bcrypt (CPU REST занижен), при N = 5 000 в конец окна попадал синхронный всплеск истечения кэша "
           "(CPU REST завышен)" + (f"; вывод «CPU REST 5 с в {fmt(r1, 1)} раза больше MQTT при N = 5 000» держался на "
                                    "этом всплеске." if r1 else "."),
           "",
           "Что изменилось в серии 2: вход измерителя — в фазе прогрева (реш. 309); окно 600 с во всех 16 прогонах "
           "(реш. 310), команд за окно 600 вместо 240; бэкенд и брокер — с исправлениями раунда 5.3 (`dd9e211`: "
           "переподписка на каждом подключении, пределы пакета и очередей брокера, лимит неудачных Basic, перепроверка "
           "статуса после записи в кэш Basic; ответы опроса — с заголовками `X-RateLimit-*`, см. оговорки). "
           "Порядок прогонов, сценарии (кроме окна), машина и ресурсы — те же; между "
           "прогонами — пауза 30 с, сбойный прогон повторяется один раз (реш. 314). "
           f"Сводка серии 1 — `data/{SERIES1[0]}`, сырьё — `data/raw/` в истории git (коммит `551ed0d`) и "
           "`logs/experiments-series1-240s/` (вне git).",
           "",
           "Серия 1 → серия 2 (окно 240 с → 600 с):",
           ""]
    rows = []
    ordered = sorted(runs.values(), key=lambda r: (r["network"] != "baseline", r["devices"], r["mode"], r["poll"] or 0))
    for run in ordered:
        if run["name"] not in archive:
            continue
        name = run["name"]

        def pair(field, render):
            return f"{render(old(name, field))} → {render(run[field])}"
        rows.append([f"`{name}`", pair("bytes_per_device_day", fmt_mb), pair("latency_p50", fmt_ms),
                     pair("latency_max", fmt_ms), pair("server_cpu_mean", lambda v: fmt(v, 1)),
                     pair("server_cpu_median", lambda v: fmt(v, 1)),
                     pair("detection_median", lambda v: fmt(v / 1000 if v is not None else None, 1))])
    out += [table(["Прогон", "МБ/сутки", "p50", "макс. задержки", "CPU сервера, среднее", "CPU, медиана",
                   "обнаружение, с"], rows), ""]
    return out


def conclusions(runs: dict) -> list:
    lines = []
    for n in SIZES:
        mqtt = find(runs, "mqtt", None, n)
        if not mqtt:
            continue
        parts = []
        for poll in (30, 15, 5):
            rest = find(runs, "rest", poll, n)
            r = ratio(rest and rest["bytes_per_device_day"], mqtt["bytes_per_device_day"])
            if r:
                parts.append(f"REST {poll} с — в {fmt(r, 1)} раза больше")
        if parts:
            lines.append(f"- **Трафик, N = {fmt(n, 0)}:** MQTT {fmt_mb(mqtt['bytes_per_device_day'])} МБ/сутки на "
                         f"устройство; {', '.join(parts)}.")
    for n in SIZES:
        mqtt = find(runs, "mqtt", None, n)
        rests = [find(runs, "rest", p, n) for p in (5, 15, 30)]
        if mqtt and all(rests) and mqtt["latency_p50"] is not None and all(r["latency_p50"] for r in rests):
            share = [r["latency_p50"] / (r["poll"] * 1000) for r in rests]
            claim = (" — задержку REST задаёт интервал опроса, а не сервер" if all(0.3 <= f <= 0.7 for f in share)
                     else "")
            lines.append(
                f"- **Задержка команды, N = {fmt(n, 0)}:** MQTT p50 {fmt_ms(mqtt['latency_p50'])}, p95 "
                f"{fmt_ms(mqtt['latency_p95'])}, макс. {fmt_ms(mqtt['latency_max'])}; REST p50 "
                f"{fmt_ms(rests[0]['latency_p50'])} / {fmt_ms(rests[1]['latency_p50'])} / "
                f"{fmt_ms(rests[2]['latency_p50'])} при опросе 5 / 15 / 30 с ({fmt(min(share), 2)}…{fmt(max(share), 2)} "
                f"интервала){claim}.")
    for n in SIZES:
        mqtt = find(runs, "mqtt", None, n)
        rests = [(p, find(runs, "rest", p, n)) for p in (30, 15, 5)]
        rests = [(p, r) for p, r in rests if r]
        if not (mqtt and rests):
            continue
        means = " / ".join(fmt(r["server_cpu_mean"], 1) for _, r in rests)
        times = " / ".join(fmt(ratio(r["server_cpu_mean"], mqtt["server_cpu_mean"]), 1) for _, r in rests)
        medians = " / ".join(fmt(r["server_cpu_median"], 1) for _, r in rests)
        peaks = " / ".join(fmt(r.get("server_cpu_max"), 0) for _, r in rests)
        polls = " / ".join(str(p) for p, _ in rests)
        lines.append(
            f"- **CPU сервера, N = {fmt(n, 0)}** (% одного ядра): среднее за окно MQTT {fmt(mqtt['server_cpu_mean'], 1)}, "
            f"REST {polls} с — {means} (в {times} раза больше MQTT); медиана MQTT {fmt(mqtt['server_cpu_median'], 1)}, "
            f"REST {medians}; пик MQTT {fmt(mqtt.get('server_cpu_max'), 0)}, REST {peaks}.")
    for n in SIZES:
        for poll in (5, 15, 30):
            rest = find(runs, "rest", poll, n)
            if rest and rest.get("late_commands"):
                lines.append(
                    f"- **REST {poll} с, N = {fmt(n, 0)}: команды дольше интервала опроса** — {rest['late_commands']} из "
                    f"{rest['commands_sent']} дольше 1,1 интервала (макс. {fmt_ms(rest['latency_max'])}), из них "
                    f"{rest['late_in_cpu_burst']} — во время всплеска CPU сервера (сэмпл выше 3 медиан окна): под "
                    "всплеском bcrypt запись команды и ответы опроса задерживаются.")
    shares = []
    for n in SIZES:
        rests = [find(runs, "rest", p, n) for p in (30, 15, 5)]
        if all(rests) and all(r["server_cpu_median"] is not None for r in rests):
            shares.append(f"N = {fmt(n, 0)} — " + " / ".join(fmt(r["server_cpu_mean"] - r["server_cpu_median"], 1)
                                                         for r in rests))
    if shares:
        lines.append("- **Вклад всплесков bcrypt** (оценка: среднее − медиана CPU, % ядра) у REST 30 / 15 / 5 с: "
                     + "; ".join(shares) + ". От интервала опроса он почти не зависит и растёт с N: повторная проверка "
                     "идёт раз в TTL на устройство при любом опросе, поэтому при больших N средний CPU REST определяют "
                     "проверки пароля, а не частота опроса; частоту опроса видно по медиане.")
    growth = []
    for mode, poll, label, _ in MODES:
        series = [find(runs, mode, poll, n) for n in SIZES]
        if all(series):
            growth.append(f"{label} {' → '.join(fmt(r['server_cpu_mean'], 1) for r in series)} "
                          f"(× {fmt(ratio(series[-1]['server_cpu_mean'], series[0]['server_cpu_mean']), 1)})")
    if growth:
        lines.append(f"- **Рост CPU сервера с N** (среднее, N = {' → '.join(fmt(n, 0) for n in SIZES)}): "
                     + "; ".join(growth) + ".")
    mqtt = find(runs, "mqtt", None, 1000)
    if mqtt and mqtt["detection_median"] is not None:
        rest = [(p, find(runs, "rest", p, 1000)) for p in (5, 15, 30)]
        rest = [(p, r) for p, r in rest if r and r["detection_median"] is not None]
        tail = ", ".join(f"REST {p} с — {fmt(r['detection_median'] / 1000, 1)} с" for p, r in rest)
        share = [r["detection_median"] / (p * 1000) for p, r in rest]
        claim = (f" ({fmt(min(share), 1)}…{fmt(max(share), 1)} интервала опроса): у REST время задаётся порогом 3 × "
                 "интервал опроса — короче оно только ценой частого опроса и трафика" if share else "")
        lines.append(f"- **Обнаружение пропажи, N = 1 000:** MQTT {fmt(mqtt['detection_median'] / 1000, 1)} с (keep-alive "
                     f"30 с, предел 1,5 × keep-alive); {tail}{claim}.")
    for mode, poll, label in [("mqtt", None, "MQTT"), ("rest", 15, "REST 15 с")]:
        base = find(runs, mode, poll, 1000)
        for net, title in CONDITIONS[1:]:
            run = find(runs, mode, poll, 1000, net)
            if base and run and run["latency_p50"] is not None and base["latency_p50"] is not None:
                lines.append(f"- **{label}, {title}:** p50 {fmt_ms(base['latency_p50'])} → {fmt_ms(run['latency_p50'])}, "
                             f"p95 {fmt_ms(base['latency_p95'])} → {fmt_ms(run['latency_p95'])}, доставлено "
                             f"{run['commands_received']}/{run['commands_sent']}, обрывов соединений {run['connection_losses']}.")
    return lines


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", default="logs/experiments")
    parser.add_argument("--out", default="docs/experiments")
    args = parser.parse_args()
    raw, out = Path(args.raw), Path(args.out)
    runs = {}
    for directory in sorted(p for p in raw.iterdir() if p.is_dir() and not p.name.startswith("smoke")):
        run = load_run(directory)
        if run:
            runs[run["name"]] = run
    if not runs:
        print("нет прогонов в", raw)
        return 1
    out.mkdir(parents=True, exist_ok=True)
    write_data(runs, out)
    images = charts(runs, out)
    archive = {r["name"]: r for r in read_csv(out / "data" / SERIES1[0])}
    (out / "results.md").write_text(results_md(runs, images, archive))
    print(f"прогонов: {len(runs)}; графиков: {len(images)}; → {out}/results.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
