"""Сборка отчёта по практике из docs/practice/*.md в docx тем же генератором, что и курсовая (решения 403, 501).

Оформление, разбор разметки и проверки (рисунки, таблицы, ссылки, источники, [[TODO]]) — tools/coursework/build_docx.py,
он импортируется как модуль и не меняется: курсовая собирается и проверяется по-прежнему. Здесь добавлены:
  - умолчания практики: --src docs/practice, --out docs/practice/RestoFleet-practice.docx, вид работы и тема по ТЗ 16.4,
    титул из docs/practice/title.local.yaml (в .gitignore), без него — заглушки из образца title.example.yaml;
  - заглушки [[ДАННЫЕ ПРЕДПРИЯТИЯ: …]] (решение 402): выделяются в docx жёлтым, как [[TODO]], и перечисляются в отчёте
    раздела «Данные предприятия»; любой другой маркер [[…]], кроме FIG и TODO, — ошибка разметки;
  - защита анонимности: название вида ООО «Имя» вместо ООО «…» — ошибка;
  - режим --final (сдача отчёта): оставшиеся [[ДАННЫЕ ПРЕДПРИЯТИЯ]] и [[TODO]] — ошибки, с --strict код возврата 1.
    Без --final (черновик) заглушки — информация, и --strict проходит, если нет других ошибок.

Запуск (python-docx и PyYAML — из venv вне репозитория, см. tools/coursework/README.md):
  /tmp/rf-venv/bin/python -I tools/practice/figures.py                 # рисунки docs/practice/img/*.png
  /tmp/rf-venv/bin/python -I tools/practice/build_report.py --strict   # черновик: docx и отчёт
  /tmp/rf-venv/bin/python -I tools/practice/build_report.py --check    # только отчёт
  /tmp/rf-venv/bin/python -I tools/practice/build_report.py --strict --final   # перед сдачей: заглушек быть не должно
"""
import argparse
import importlib.util
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
GENERATOR = ROOT / "tools" / "coursework" / "build_docx.py"

SRC = ROOT / "docs" / "practice"
OUT_NAME = "RestoFleet-practice.docx"
TITLE_LOCAL = SRC / "title.local.yaml"
TITLE_EXAMPLE = SRC / "title.example.yaml"      # без title.local.yaml — заглушки практики, а не курсовой
WORK_TYPE = "ОТЧЁТ"
TOPIC = ("Анализ инфраструктуры устройств ресторанов и инцидентов доступности серверной части ООО „…“; "
         "проектирование и реализация подсистемы проверок доступности и корреляции инцидентов")

DATA_TAG = "ДАННЫЕ ПРЕДПРИЯТИЯ"
DATA_RE = re.compile(r"\[\[ДАННЫЕ ПРЕДПРИЯТИЯ:\s*([^\[\]]+?)\s*\]\]")
ANY_MARK_RE = re.compile(r"\[\[([^\]:|]*)")          # имя любого маркера [[ИМЯ: …]] / [[ИМЯ …]]
NAMED_COMPANY_RE = re.compile(r"ООО\s*[«\"„“](?!…)")   # ООО «…» — можно, ООО «Имя» — нет (решение 402)
SECTION = "Данные предприятия"


def known_mark(name):
    """FIG, TODO и TODO с пояснением, ДАННЫЕ ПРЕДПРИЯТИЯ — только с двоеточием (имя без хвоста)."""
    return name in ("FIG", "TODO", DATA_TAG) or name.startswith("TODO ")


def load_generator():
    sys.dont_write_bytecode = True       # без __pycache__ в tools/coursework (чужой каталог)
    spec = importlib.util.spec_from_file_location("rf_build_docx", GENERATOR)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module      # dataclass ищет модуль класса в sys.modules
    spec.loader.exec_module(module)
    return module


def install_highlight(bd):
    """[[ДАННЫЕ ПРЕДПРИЯТИЯ: …]] в docx выделяется жёлтым, как [[TODO]]: разбор строки генератора — вокруг заглушек."""
    original = bd.parse_inline

    def parse_inline(text):
        runs, pos = [], 0
        for m in DATA_RE.finditer(text):
            runs += original(text[pos:m.start()])
            runs.append((m.group(0), False, False, False, True))
            pos = m.end()
        return runs + original(text[pos:])

    bd.parse_inline = parse_inline


def check_placeholders(bd, files, report, *, final):
    """Заглушки предприятия и единообразие разметки; в режиме --final заглушки и [[TODO]] — ошибки."""
    total, by_file = 0, {}
    for path in files:
        in_code = False
        for ln, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if raw.strip().startswith("```"):
                in_code = not in_code
                continue
            if in_code:
                continue
            for m in ANY_MARK_RE.finditer(raw):
                name = m.group(1).strip()
                if not known_mark(name):
                    report.add(SECTION, f"{path.name}:{ln}: неизвестный маркер «[[{name[:40]}…» — допустимы "
                                        f"[[FIG: …]], [[TODO …]], [[{DATA_TAG}: …]]", "error")
                elif name == DATA_TAG and not DATA_RE.match(raw, m.start()):
                    report.add(SECTION, f"{path.name}:{ln}: заглушка не по формату [[{DATA_TAG}: что вписать]]",
                               "error")
            for m in NAMED_COMPANY_RE.finditer(raw):
                report.add(SECTION, f"{path.name}:{ln}: название компании вместо ООО «…» (решение 402)", "error")
            for m in DATA_RE.finditer(raw):
                total += 1
                by_file[path.name] = by_file.get(path.name, 0) + 1
                report.add(SECTION, f"{path.name}:{ln}: {m.group(1)[:100]}", "error" if final else "info")
            if final:
                for m in bd.TODO_RE.finditer(raw):
                    report.add("TODO", f"{path.name}:{ln}: [[TODO]] в режиме --final", "error")
    summary = ", ".join(f"{name} — {n}" for name, n in by_file.items()) or "нет"
    report.add(SECTION, f"всего заглушек: {total} ({summary})")
    return total


def main():
    bd = load_generator()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="только проверки, docx не собирать")
    ap.add_argument("--src", type=Path, default=SRC, help="каталог исходников (по умолчанию docs/practice)")
    ap.add_argument("--out", type=Path, default=None, help=f"путь к docx (по умолчанию <src>/{OUT_NAME})")
    ap.add_argument("--strict", action="store_true", help="код возврата 1 при ошибках в отчёте")
    ap.add_argument("--final", action="store_true",
                    help="перед сдачей: оставшиеся [[ДАННЫЕ ПРЕДПРИЯТИЯ]] и [[TODO]] — ошибки")
    ap.add_argument("--title", type=Path, default=TITLE_LOCAL,
                    help="YAML с реквизитами титула (по умолчанию docs/practice/title.local.yaml; нет — заглушки)")
    ap.add_argument("--work-type", default=WORK_TYPE, help=f"вид работы на титуле (по умолчанию «{WORK_TYPE}»)")
    ap.add_argument("--topic", default=TOPIC, help="тема (по умолчанию — индивидуальное задание ТЗ 16.4)")
    args = ap.parse_args()

    bd.SRC = args.src.resolve()
    bd.IMG = bd.SRC / "img"
    bd.IMG_EXTRA = ()                 # графики эксперимента курсовой в отчёт по практике не подставляются
    install_highlight(bd)
    out = (args.out or bd.SRC / OUT_NAME).resolve()

    report = bd.Report()
    files = bd.source_files()
    report.add("Сборка", "файлы: " + ", ".join(p.name for p in files))
    docs = [(p.name, bd.parse_file(p, report)) for p in files]
    figures, tables = bd.number_document(docs, report)
    for num, b in figures.items():
        path = bd.figure_path(b.fig_id)
        shown = f"img/{path.name}" if path else "файла нет, в документе рамка"
        report.add("Рисунки", f"{num} {b.fig_id} ({b.file}:{b.line}) — {shown}", "info" if path else "warn")
    for num, b in tables.items():
        cap = b.caption.text if b.caption else "—"
        report.add("Таблицы", f"{num} {bd.plain_text(cap)[:70]} ({b.file}:{b.line}, строк: {len(b.rows)})")

    bd.check_refs(files, figures, tables, report)
    bd.check_sources(files, docs, report)
    bd.check_todos(files, report)
    check_placeholders(bd, files, report, final=args.final)
    bd.check_layout(docs, report)
    bd.check_volume(docs, report)
    title_path = args.title.resolve()
    if not title_path.exists() and title_path == TITLE_LOCAL:
        report.add("Титульный лист", f"{TITLE_LOCAL.relative_to(ROOT)} нет — заглушки из образца")
        title_path = TITLE_EXAMPLE
    title = bd.load_title(title_path, report)

    if not args.check:
        try:
            bd.build_docx(docs, figures, tables, out, report, topic=args.topic.strip(),
                          work_type=args.work_type.strip(), title=title)
        except ImportError:
            report.add("Сборка", "нет python-docx: /tmp/rf-venv/bin/pip install python-docx", "error")
        report.add("Оформление", "содержание — поле TOC, Word обновит его при открытии; «Продолжение таблицы» "
                                 "ставится вручную при финальной вёрстке (как в курсовой, решение 324)")
    report.print()
    return 1 if args.strict and report.errors else 0


if __name__ == "__main__":
    sys.exit(main())
