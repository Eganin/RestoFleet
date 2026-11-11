"""Сэмплер `docker stats --no-stream` (КМ4, план 4.2): раз в N секунд пишет CPU, ОЗУ и NET I/O контейнеров стенда в CSV.

Запуск: python3 -I tools/experiments/sample_docker_stats.py <out.csv> [--interval 5] [--prefix restofleet-]
Останавливается по SIGTERM/SIGINT. CPU — проценты одного ядра (как в docker stats), память и сеть — байты.
"""
import argparse
import csv
import json
import re
import signal
import subprocess
import sys
import time

UNITS = {
    "b": 1, "kb": 1000, "mb": 1000 ** 2, "gb": 1000 ** 3, "tb": 1000 ** 4,
    "kib": 1024, "mib": 1024 ** 2, "gib": 1024 ** 3, "tib": 1024 ** 4,
}
SIZE = re.compile(r"^\s*([0-9.]+)\s*([A-Za-z]*)\s*$")


def to_bytes(text: str) -> int:
    """'12.5MiB' / '3.4kB' / '0B' → байты."""
    match = SIZE.match(text)
    if not match:
        return 0
    value, unit = match.groups()
    return int(float(value) * UNITS.get(unit.lower() or "b", 1))


def pair(text: str) -> tuple:
    left, _, right = text.partition("/")
    return to_bytes(left), to_bytes(right)


def sample(prefix: str) -> list:
    out = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{json .}}"],
        capture_output=True, text=True, check=False, timeout=60,
    )
    rows = []
    for line in out.stdout.splitlines():
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        name = item.get("Name", "")
        if not name.startswith(prefix):
            continue
        mem_used, mem_limit = pair(item.get("MemUsage", "0B / 0B"))
        net_rx, net_tx = pair(item.get("NetIO", "0B / 0B"))
        rows.append({
            "name": name,
            "cpu_pct": item.get("CPUPerc", "0%").rstrip("%") or "0",
            "mem_used_bytes": mem_used,
            "mem_limit_bytes": mem_limit,
            "net_rx_bytes": net_rx,
            "net_tx_bytes": net_tx,
            "pids": item.get("PIDs", ""),
        })
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("--interval", type=float, default=5.0)
    parser.add_argument("--prefix", default="restofleet-")
    args = parser.parse_args()
    running = {"on": True}

    def stop(*_):
        running["on"] = False

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    fields = ["ts_ms", "name", "cpu_pct", "mem_used_bytes", "mem_limit_bytes", "net_rx_bytes", "net_tx_bytes", "pids"]
    with open(args.out, "w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        while running["on"]:
            started = time.time()
            ts_ms = int(started * 1000)
            for row in sample(args.prefix):
                writer.writerow({"ts_ms": ts_ms, **row})
            handle.flush()
            pause = args.interval - (time.time() - started)
            while running["on"] and pause > 0:
                time.sleep(min(pause, 0.5))
                pause -= 0.5
    return 0


if __name__ == "__main__":
    sys.exit(main())
