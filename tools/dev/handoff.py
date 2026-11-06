#!/usr/bin/env python3
"""Обновляет docs/handoff.md: handoff.py <done-task> <next-task> <итог> [блокеры]."""
import datetime
import re
import sys

path = "docs/handoff.md"
done, nxt, result = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path, encoding="utf-8").read()
text = re.sub(r"(\*\*Последняя завершённая задача:\*\*).*", rf"\1 {done}", text)
text = re.sub(r"(\*\*Следующая задача:\*\*).*", rf"\1 {nxt}", text)
if len(sys.argv) > 4:
    text = re.sub(r"(\*\*Блокеры:\*\*).*", rf"\1 {sys.argv[4]}", text)
today = datetime.date.today().isoformat()
text = text.rstrip("\n") + f"\n| {today} | {done} | см. `git log` | {result} |\n"
open(path, "w", encoding="utf-8").write(text)
