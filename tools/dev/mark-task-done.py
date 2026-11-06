#!/usr/bin/env python3
"""Отмечает [x] все шаги задачи плана: mark-task-done.py <plan.md> <номер задачи, напр. 0.2>."""
import re
import sys

plan, task = sys.argv[1], sys.argv[2]
text = open(plan, encoding="utf-8").read()
start = text.index(f"### Задача {task}:")
nxt = re.search(r"^(### |# )", text[start + 1:], re.M)
end = start + 1 + nxt.start() if nxt else len(text)
section = text[start:end].replace("- [ ] ", "- [x] ")
open(plan, "w", encoding="utf-8").write(text[:start] + section + text[end:])
