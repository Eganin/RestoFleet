#!/usr/bin/env python3
"""Извлекает раздел «### Задача <N>:» плана в файл брифа: task-brief.py <plan.md> <N> <out-dir>; печатает путь."""
import re
import sys

plan, task, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(plan, encoding="utf-8").read()
start = text.index(f"### Задача {task}:")
nxt = re.search(r"^(### |# )", text[start + 1:], re.M)
end = start + 1 + nxt.start() if nxt else len(text)
path = f"{out_dir}/task-{task}-brief.md"
open(path, "w", encoding="utf-8").write(text[start:end].rstrip() + "\n")
print(path)
