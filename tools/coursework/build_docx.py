"""Сборка пояснительной записки курсовой из docs/coursework/*.md в docx (решение 217, ГОСТ 7.32-2017).

Порядок и соглашения разметки — docs/coursework/README.md («Порядок сборки», «Соглашения»).
Кроме сборки проверяет записку (рисунки, таблицы, ссылки на них, источники, [[TODO]], оформление)
и печатает отчёт в stdout.

Запуск (python-docx — из venv вне репозитория, см. tools/coursework/README.md):
  /tmp/rf-venv/bin/python -I tools/coursework/build_docx.py            # сборка + отчёт
  /tmp/rf-venv/bin/python -I tools/coursework/build_docx.py --check    # только отчёт, без docx
  ... --strict  — код возврата 1, если в отчёте есть ошибки
"""
import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "docs" / "coursework"
IMG = SRC / "img"
# Графики эксперимента строит tools/experiments/analyze.py; рисунок главы 4 берётся прямо оттуда по id маркера
# (traffic, latency, …), чтобы у графика была одна копия. Свой img/<id>.png записки имеет приоритет.
IMG_EXTRA = (ROOT / "docs" / "experiments" / "img",)
OUT = SRC / "RestoFleet-coursework.docx"

TOPIC = ("Разработка системы мониторинга и удалённого управления Android-устройствами "
         "на основе протокола MQTT")
YEAR = "2026"

# Геометрия страницы, мм (решение 217)
PAGE_W, PAGE_H = 210, 297
MARGIN_L, MARGIN_R, MARGIN_T, MARGIN_B = 30, 15, 20, 20
TEXT_W_MM = PAGE_W - MARGIN_L - MARGIN_R          # 165 мм
TEXT_W_TWIPS = round(TEXT_W_MM / 25.4 * 1440)     # 9354
FIG_MAX_W_CM, FIG_MAX_H_CM = TEXT_W_MM / 10, 22.0
PORTRAIT_MAX_H_CM = 13.0   # снимки экрана телефона: высота > 1,3 ширины
CODE_PT = 10
CODE_LINE_CHARS = int(TEXT_W_MM / 25.4 * 72 / (CODE_PT * 0.6))  # Courier New: ширина знака 0,6 кегля
CODE_MAX_LINES = 12                                 # README: «короткие (до ~10 строк)»
CHARS_PER_PAGE = 1800

STRUCTURAL = {"введение", "заключение", "список использованных источников", "содержание",
              "список сокращений и обозначений", "термины и определения", "реферат"}
SOURCES_TITLE = "список использованных источников"

NUM = r"(?:\d+|[А-ЯЁ])\.\d+"
HEAD_RE = re.compile(r"^(#{1,6})\s+(.*?)\s*$")
FENCE_RE = re.compile(r"^```\s*([\w+-]*)\s*$")
FIG_RE = re.compile(r"^\[\[FIG:(.*)\]\]\s*$")
TCAP_RE = re.compile(rf"^Таблица\s+({NUM})\s+[—–-]\s+(.+)$")
OL_RE = re.compile(r"^(\d+)([.)])\s+(.*)$")
UL_RE = re.compile(r"^[-*+]\s+(.*)$")
TODO_RE = re.compile(r"\[\[TODO.*?\]\]")
CODE_SPAN_RE = re.compile(r"`[^`]*`")
CITE_RE = re.compile(r"\[(\d+(?:\s*[;,–-]\s*\d+)*)\]")
REF_RE = re.compile(rf"(?<![А-Яа-яЁё])(рисун[а-яё]*|рис\.|таблиц[а-яё]*|табл\.)\s+"
                    rf"({NUM}(?:\s*(?:,|и|–|—|-)\s*{NUM})*)", re.IGNORECASE)
CHAPTER_RE = re.compile(r"^(\d+)\s+\S")
APPENDIX_RE = re.compile(r"^Приложение\s+([А-ЯЁ])\b\.?\s*(?:[—–-]\s*)?(.*)$", re.IGNORECASE)
SUB_RE = re.compile(r"^(\d+(?:\.\d+)+)\s+\S")


# ---------- модель документа ----------

@dataclass
class Block:
    kind: str                 # heading | para | list | table | caption | code | figure
    file: str
    line: int
    text: str = ""
    level: int = 0
    items: list = field(default_factory=list)   # list: [(маркер, текст, строка)]
    ordered: bool = False
    header: list = field(default_factory=list)  # table
    rows: list = field(default_factory=list)
    aligns: list = field(default_factory=list)
    caption: "Block | None" = None              # table: блок подписи
    num: str = ""                               # номер рисунка или таблицы
    fig_id: str = ""
    desc: str = ""
    lang: str = ""
    code: list = field(default_factory=list)
    chapter: object = None                      # номер главы / буква приложения / None


class Report:
    def __init__(self):
        self.sections = {}
        self.errors = 0
        self.warnings = 0

    def add(self, section, text, level="info"):
        prefix = {"error": "ОШИБКА: ", "warn": "внимание: ", "info": ""}[level]
        self.errors += level == "error"
        self.warnings += level == "warn"
        self.sections.setdefault(section, []).append(prefix + text)

    def print(self):
        for name, lines in self.sections.items():
            print(f"\n== {name} ==")
            for line in lines:
                print(f"  {line}")
        print(f"\nИтого: ошибок — {self.errors}, предупреждений — {self.warnings}")


def figure_path(fig_id):
    """PNG рисунка: img/<id>.png записки, иначе docs/experiments/img/<id>.png; None — картинки нет нигде."""
    for folder in (IMG, *IMG_EXTRA):
        path = folder / f"{fig_id}.png"
        if path.exists():
            return path
    return None


def source_files():
    numbered = sorted(p for p in SRC.glob("[0-9][0-9]-*.md"))
    appendices = sorted(SRC.glob("appendix-*.md"))
    return numbered + appendices


def split_row(line):
    cells = re.split(r"(?<!\\)\|", line.strip().strip("|") if line.strip() != "|" else "")
    return [c.strip().replace("\\|", "|") for c in cells]


def is_block_start(stripped):
    return (stripped.startswith(("#", "```", "|", "[[FIG:")) or TCAP_RE.match(stripped)
            or OL_RE.match(stripped) or UL_RE.match(stripped))


def parse_file(path, report):
    name = path.name
    lines = path.read_text(encoding="utf-8").splitlines()
    blocks, i = [], 0
    while i < len(lines):
        raw, s = lines[i], lines[i].strip()
        ln = i + 1
        if not s:
            i += 1
            continue
        if m := FENCE_RE.match(s):
            body, i = [], i + 1
            while i < len(lines) and not lines[i].strip().startswith("```"):
                body.append(lines[i].rstrip())
                i += 1
            if i >= len(lines):
                report.add("Разметка", f"{name}:{ln}: незакрытый блок кода", "error")
            blocks.append(Block("code", name, ln, lang=m.group(1), code=body))
            i += 1
            continue
        if m := HEAD_RE.match(raw):
            blocks.append(Block("heading", name, ln, text=m.group(2), level=len(m.group(1))))
            i += 1
            continue
        if m := FIG_RE.match(s):
            parts = [p.strip() for p in m.group(1).split("|", 2)]
            if len(parts) < 3 or not all(parts):
                report.add("Рисунки", f"{name}:{ln}: маркер не по формату "
                                      f"[[FIG: id | Подпись | описание]]", "error")
                parts = (parts + ["", "", ""])[:3]
            blocks.append(Block("figure", name, ln, fig_id=parts[0], text=parts[1], desc=parts[2]))
            i += 1
            continue
        if s.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(split_row(lines[i]))
                i += 1
            blk = Block("table", name, ln)
            if len(rows) >= 2 and all(re.fullmatch(r":?-{3,}:?", c) for c in rows[1]):
                blk.header, body, seps = rows[0], rows[2:], rows[1]
                blk.aligns = ["center" if c.startswith(":") and c.endswith(":") else
                              "right" if c.endswith(":") else "left" for c in seps]
            else:
                report.add("Разметка", f"{name}:{ln}: таблица без строки-разделителя шапки", "error")
                blk.header, body, blk.aligns = rows[0], rows[1:], []
            width = len(blk.header)
            for r_i, row in enumerate(body):
                if len(row) != width:
                    report.add("Таблицы", f"{name}:{ln + 2 + r_i}: в строке {len(row)} ячеек, "
                                          f"в шапке {width}", "warn")
                blk.rows.append((row + [""] * width)[:width])
            if blocks and blocks[-1].kind == "caption":
                blk.caption = blocks.pop()
            blocks.append(blk)
            continue
        if m := TCAP_RE.match(s):
            blocks.append(Block("caption", name, ln, num=m.group(1), text=m.group(2)))
            i += 1
            continue
        if OL_RE.match(s) or UL_RE.match(s):
            ordered = bool(OL_RE.match(s))
            blk = Block("list", name, ln, ordered=ordered)
            while i < len(lines):
                t = lines[i]
                st = t.strip()
                mo, mu = OL_RE.match(st), UL_RE.match(st)
                if ordered and mo:
                    blk.items.append([mo.group(1) + mo.group(2), mo.group(3), i + 1])
                elif not ordered and mu:
                    blk.items.append(["–", mu.group(1), i + 1])
                elif st and t[:1].isspace() and blk.items:      # продолжение пункта
                    blk.items[-1][1] += " " + st
                else:
                    break
                i += 1
            blocks.append(blk)
            continue
        para = [s]
        i += 1
        while i < len(lines) and lines[i].strip() and not is_block_start(lines[i].strip()):
            para.append(lines[i].strip())
            i += 1
        blocks.append(Block("para", name, ln, text=" ".join(para)))
    for b in blocks:
        if b.kind == "caption":
            report.add("Таблицы", f"{name}:{b.line}: подпись «Таблица {b.num}» без таблицы после неё", "error")
    return blocks


# ---------- нумерация и проверки структуры ----------

def heading_kind(text):
    if CHAPTER_RE.match(text):
        return "chapter"
    if APPENDIX_RE.match(text):
        return "appendix"
    return "structural"


def number_document(docs, report):
    """Главы, рисунки, таблицы: номер = глава + порядковый номер в главе."""
    figures, tables = {}, {}
    chapter, fig_n, tab_n, sub = None, 0, 0, []
    for name, blocks in docs:
        for b in blocks:
            b.chapter = chapter
            if b.kind == "heading" and b.level == 1:
                kind = heading_kind(b.text)
                chapter = (int(CHAPTER_RE.match(b.text).group(1)) if kind == "chapter" else
                           APPENDIX_RE.match(b.text).group(1).upper() if kind == "appendix" else None)
                b.chapter = chapter
                fig_n = tab_n = 0
                sub = []
                if kind == "structural" and b.text.strip().lower() not in STRUCTURAL:
                    report.add("Структура", f"{name}:{b.line}: заголовок 1-го уровня «{b.text}» без номера "
                                            f"главы и не из списка структурных элементов", "warn")
            elif b.kind == "heading":
                m = SUB_RE.match(b.text)
                if b.level > 3:
                    report.add("Структура", f"{name}:{b.line}: заголовок уровня {b.level} — в содержание "
                                            f"не попадёт (ГОСТ 7.32: до пунктов)", "warn")
                if not m:
                    report.add("Структура", f"{name}:{b.line}: заголовок «{b.text}» без номера", "warn")
                    continue
                parts = m.group(1).split(".")
                if len(parts) != b.level:
                    report.add("Структура", f"{name}:{b.line}: номер {m.group(1)} не соответствует "
                                            f"уровню заголовка {b.level}", "error")
                    continue
                if str(chapter) != parts[0]:
                    report.add("Структура", f"{name}:{b.line}: подраздел {m.group(1)} в главе {chapter}", "error")
                depth = b.level - 1
                sub = sub[:depth]
                while len(sub) < depth:
                    sub.append(0)
                sub[depth - 1] += 1
                expected = ".".join([str(chapter)] + [str(x) for x in sub])
                if m.group(1) != expected:
                    report.add("Структура", f"{name}:{b.line}: номер {m.group(1)}, ожидался {expected}", "error")
            elif b.kind == "figure":
                if chapter is None:
                    report.add("Рисунки", f"{name}:{b.line}: рисунок «{b.fig_id}» вне главы — "
                                          f"не нумеруется", "error")
                    b.num = "?"
                    continue
                fig_n += 1
                b.num = f"{chapter}.{fig_n}"
                if b.fig_id in {f.fig_id for f in figures.values()}:
                    report.add("Рисунки", f"{name}:{b.line}: id «{b.fig_id}» повторяется", "error")
                figures[b.num] = b
            elif b.kind == "table":
                if chapter is None:
                    report.add("Таблицы", f"{name}:{b.line}: таблица вне главы — не нумеруется", "warn")
                    b.num = b.caption.num if b.caption else "?"
                    continue
                tab_n += 1
                expected = f"{chapter}.{tab_n}"
                if b.caption is None:
                    report.add("Таблицы", f"{name}:{b.line}: таблица без подписи «Таблица {expected} — …»", "error")
                    b.num = expected
                else:
                    b.num = b.caption.num
                    if b.caption.num != expected:
                        report.add("Таблицы", f"{name}:{b.caption.line}: подпись «Таблица {b.caption.num}», "
                                              f"по порядку должна быть {expected}", "error")
                tables[b.num] = b
    return figures, tables


def plain_lines(path):
    """Строки текста для поиска ссылок: без кода, маркеров рисунков, подписей и [[TODO]]."""
    in_code = False
    for ln, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        s = raw.strip()
        if s.startswith("```"):
            in_code = not in_code
            continue
        if in_code or s.startswith("[[FIG:") or s.startswith("#") or TCAP_RE.match(s):
            continue
        yield ln, CODE_SPAN_RE.sub(" ", TODO_RE.sub(" ", raw))


def check_refs(files, figures, tables, report):
    order = {p.name: k for k, p in enumerate(files)}
    first_ref = {}
    for path in files:
        if path.name.startswith("99"):
            continue
        for ln, text in plain_lines(path):
            for m in REF_RE.finditer(text):
                is_fig = m.group(1).lower().startswith("рис")
                pool, word = (figures, "рисунок") if is_fig else (tables, "таблица")
                for num in re.findall(NUM, m.group(2)):
                    if num not in pool:
                        report.add("Ссылки на рисунки и таблицы",
                                   f"{path.name}:{ln}: «{m.group(0).strip()}» — {word} {num} не существует", "error")
                    else:
                        first_ref.setdefault((word, num), (order[path.name], ln, path.name))
    for word, pool in (("рисунок", figures), ("таблица", tables)):
        for num, b in pool.items():
            ref = first_ref.get((word, num))
            if ref is None:
                report.add("Ссылки на рисунки и таблицы", f"{word} {num} ({b.file}:{b.line}) — "
                                                          f"в тексте нет ссылки", "warn")
            elif ref[:2] > (order[b.file], (b.caption.line if b.kind == "table" and b.caption else b.line)):
                report.add("Ссылки на рисунки и таблицы", f"{word} {num}: первая ссылка ({ref[2]}:{ref[1]}) "
                                                          f"стоит после самого объекта ({b.file}:{b.line})", "warn")
    if not report.sections.get("Ссылки на рисунки и таблицы"):
        report.add("Ссылки на рисунки и таблицы",
                   f"все ссылки указывают на существующие номера ({len(first_ref)} объектов упомянуто)")


def expand_cite(body):
    nums = []
    for part in re.split(r"[;,]", body):
        part = part.strip()
        if m := re.fullmatch(r"(\d+)\s*[–-]\s*(\d+)", part):
            nums.extend(range(int(m.group(1)), int(m.group(2)) + 1))
        elif part.isdigit():
            nums.append(int(part))
    return nums


def check_sources(files, docs, report):
    sources = {}
    for name, blocks in docs:
        if not name.startswith("99"):
            continue
        expected = 1
        for b in blocks:
            if b.kind != "list":
                continue
            for marker, _, ln in b.items:
                n = int(re.match(r"\d+", marker).group(0))
                if n != expected:
                    report.add("Источники", f"{name}:{ln}: номер {n}, ожидался {expected}", "error")
                sources[n] = ln
                expected = n + 1
    total = max(sources) if sources else 0
    seen, max_seen, cited = set(), 0, 0
    for path in files:
        if path.name.startswith("99"):
            continue
        for ln, text in plain_lines(path):
            for m in CITE_RE.finditer(text):
                for n in expand_cite(m.group(1)):
                    cited += 1
                    if n not in sources:
                        report.add("Источники", f"{path.name}:{ln}: [{n}] — нет в списке источников "
                                                f"(в списке {total})", "error")
                        continue
                    if n in seen:
                        continue
                    if n > max_seen + 1:
                        report.add("Источники", f"{path.name}:{ln}: [{n}] упомянут впервые раньше, чем "
                                                f"[{max_seen + 1}]", "error")
                    elif n < max_seen:
                        report.add("Источники", f"{path.name}:{ln}: [{n}] упомянут впервые после [{max_seen}]",
                                   "error")
                    seen.add(n)
                    max_seen = max(max_seen, n)
    unused = sorted(set(sources) - seen)
    if unused:
        report.add("Источники", "в тексте не упомянуты: " + ", ".join(f"[{n}]" for n in unused), "warn")
    if not any(line.startswith("ОШИБКА") for line in report.sections.get("Источники", [])):
        report.add("Источники", f"в списке {total}; ссылок {cited}, упомянуто {len(seen)}; "
                                f"номера в пределах списка, порядок первого упоминания соблюдён")
    return total


def check_todos(files, report):
    found = 0
    for path in files:
        for ln, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for m in TODO_RE.finditer(raw):
                found += 1
                text = m.group(0)
                report.add("TODO", f"{path.name}:{ln}: {text[:110]}{'…' if len(text) > 110 else ''}")
    if not found:
        report.add("TODO", "нет")
    return found


def plain_text(text):
    text = TODO_RE.sub("", text)
    return re.sub(r"\*\*|`|(?<!\w)\*|\*(?!\w)", "", text)


def check_volume(docs, report):
    for name, blocks in docs:
        chars = 0
        for b in blocks:
            if b.kind in ("para", "heading"):
                chars += len(plain_text(b.text))
            elif b.kind == "list":
                chars += sum(len(plain_text(t)) + len(mk) + 1 for mk, t, _ in b.items)
            elif b.kind == "table":
                chars += sum(len(plain_text(c)) for row in [b.header] + b.rows for c in row)
                chars += len(plain_text(b.caption.text)) if b.caption else 0
            elif b.kind == "code":
                chars += sum(len(x) for x in b.code)
            elif b.kind == "figure":
                chars += len(b.text)
        report.add("Объём (знаков с пробелами, без разметки; страница ≈ 1800)",
                   f"{name}: {chars:,}".replace(",", " ") + f" (≈ {chars / CHARS_PER_PAGE:.1f} стр.)")


def check_layout(docs, report):
    for name, blocks in docs:
        for b in blocks:
            if b.kind == "code":
                if len(b.code) > CODE_MAX_LINES:
                    report.add("Оформление", f"{name}:{b.line}: листинг {len(b.code)} строк — "
                                             f"длинные фрагменты по README выносятся в приложения", "warn")
                for k, line in enumerate(b.code):
                    if len(line) > CODE_LINE_CHARS:
                        report.add("Оформление", f"{name}:{b.line + 1 + k}: строка листинга {len(line)} "
                                                 f"знаков > {CODE_LINE_CHARS} — Word перенесёт её", "warn")


# ---------- docx: низкоуровневые помощники ----------

def build_docx(docs, figures, tables, out_path, report):
    from docx import Document
    from docx.enum.style import WD_STYLE_TYPE
    from docx.enum.table import WD_TABLE_ALIGNMENT
    from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK, WD_COLOR_INDEX
    from docx.image.image import Image as DocxImage
    from docx.oxml import OxmlElement
    from docx.oxml.ns import qn
    from docx.shared import Cm, Mm, Pt

    ALIGN = {"left": WD_ALIGN_PARAGRAPH.LEFT, "center": WD_ALIGN_PARAGRAPH.CENTER,
             "right": WD_ALIGN_PARAGRAPH.RIGHT, "justify": WD_ALIGN_PARAGRAPH.JUSTIFY}

    PPR_ORDER = ["pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl", "numPr",
                 "suppressLineNumbers", "pBdr", "shd", "tabs", "suppressAutoHyphens", "kinsoku", "wordWrap",
                 "overflowPunct", "topLinePunct", "autoSpaceDE", "autoSpaceDN", "bidi", "adjustRightInd",
                 "snapToGrid", "spacing", "ind", "contextualSpacing", "mirrorIndents", "suppressOverlap", "jc",
                 "textDirection", "textAlignment", "textboxTightWrap", "outlineLvl", "divId", "cnfStyle", "rPr",
                 "sectPr", "pPrChange"]
    RPR_ORDER = ["rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline",
                 "shadow", "emboss", "imprint", "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing",
                 "w", "kern", "position", "sz", "szCs", "highlight", "u", "effect", "bdr", "shd", "fitText",
                 "vertAlign", "rtl", "cs", "em", "lang", "eastAsianLayout", "specVanish", "oMath"]
    SETTINGS_ORDER = ["writeProtection", "view", "zoom", "removePersonalInformation", "removeDateAndTime",
                      "doNotDisplayPageBoundaries", "displayBackgroundShape", "printPostScriptOverText",
                      "printFractionalCharacterWidth", "printFormsData", "embedTrueTypeFonts", "embedSystemFonts",
                      "saveSubsetFonts", "saveFormsData", "mirrorMargins", "alignBordersAndEdges",
                      "bordersDoNotSurroundHeader", "bordersDoNotSurroundFooter", "gutterAtTop",
                      "hideSpellingErrors", "hideGrammaticalErrors", "activeWritingStyle", "proofState",
                      "formsDesign", "attachedTemplate", "linkStyles", "stylePaneFormatFilter",
                      "stylePaneSortMethod", "documentType", "mailMerge", "revisionView", "trackRevisions",
                      "doNotTrackMoves", "doNotTrackFormatting", "documentProtection", "autoFormatOverride",
                      "styleLockTheme", "styleLockQFSet", "defaultTabStop", "autoHyphenation",
                      "consecutiveHyphenLimit", "hyphenationZone", "doNotHyphenateCaps", "showEnvelope",
                      "summaryLength", "clickAndTypeStyle", "defaultTableStyle", "evenAndOddHeaders",
                      "bookFoldRevPrinting", "bookFoldPrinting", "bookFoldPrintingSheets",
                      "drawingGridHorizontalSpacing", "drawingGridVerticalSpacing",
                      "displayHorizontalDrawingGridEvery", "displayVerticalDrawingGridEvery",
                      "doNotUseMarginsForDrawingGridOrigin", "drawingGridHorizontalOrigin",
                      "drawingGridVerticalOrigin", "doNotShadeFormData", "noPunctuationKerning",
                      "characterSpacingControl", "printTwoOnOne", "strictFirstAndLastChars", "noLineBreaksAfter",
                      "noLineBreaksBefore", "savePreviewPicture", "doNotValidateAgainstSchema", "saveInvalidXml",
                      "ignoreMixedContent", "alwaysShowPlaceholderText", "doNotDemarcateInvalidXml",
                      "saveXmlDataOnly", "useXSLTWhenSaving", "saveThroughXslt", "showXMLTags",
                      "alwaysMergeEmptyNamespace", "updateFields", "hdrShapeDefaults", "footnotePr", "endnotePr",
                      "compat", "docVars", "rsids", "mathPr", "attachedSchema", "themeFontLang",
                      "clrSchemeMapping", "doNotIncludeSubdocsInStats", "doNotAutoCompressPictures",
                      "forceUpgrade", "captions", "readModeInkLockDown", "smartTagType", "schemaLibrary",
                      "shapeDefaults", "doNotEmbedSmartTags", "decimalSymbol", "listSeparator"]
    TBLPR_ORDER = ["tblStyle", "tblpPr", "tblOverlap", "bidiVisual", "tblStyleRowBandSize", "tblStyleColBandSize",
                   "tblW", "jc", "tblCellSpacing", "tblInd", "tblBorders", "shd", "tblLayout", "tblCellMar",
                   "tblLook", "tblCaption", "tblDescription"]

    def put(parent, tag, order, **attrs):
        """Дочерний элемент w:<tag> на своём месте по схеме (замена существующего)."""
        for old in parent.findall(qn(f"w:{tag}")):
            parent.remove(old)
        el = OxmlElement(f"w:{tag}")
        for k, v in attrs.items():
            el.set(qn(f"w:{k}"), str(v))
        later = set(order[order.index(tag) + 1:])
        for idx, child in enumerate(parent):
            local = child.tag.split("}")[-1]
            if local in later:
                parent.insert(idx, el)
                return el
        parent.append(el)
        return el

    def set_fonts(rpr, name):
        rf = put(rpr, "rFonts", RPR_ORDER, ascii=name, hAnsi=name, cs=name, eastAsia=name)
        return rf

    def set_lang(rpr, lang):
        put(rpr, "lang", RPR_ORDER, val=lang, eastAsia=lang, bidi="ar-SA")

    def style_rpr(style):
        return style.element.get_or_add_rPr()

    def style_ppr(style):
        return style.element.get_or_add_pPr()

    doc = Document()

    # --- значения по умолчанию, стили ---
    styles_el = doc.styles.element
    defaults = styles_el.find(qn("w:docDefaults"))
    rpr_def = defaults.find(qn("w:rPrDefault")).find(qn("w:rPr"))
    set_fonts(rpr_def, "Times New Roman")
    put(rpr_def, "sz", RPR_ORDER, val=28)
    put(rpr_def, "szCs", RPR_ORDER, val=28)
    set_lang(rpr_def, "ru-RU")
    ppr_def = defaults.find(qn("w:pPrDefault")).find(qn("w:pPr"))
    put(ppr_def, "spacing", PPR_ORDER, before=0, after=0, line=240, lineRule="auto")

    def para_style(name, base="Normal", *, size=None, bold=None, italic=None, font=None, align=None,
                   first=None, left=None, right=None, line=None, before=None, after=None, keep_next=None,
                   page_break=None, no_hyphen=False, outline=None):
        st = doc.styles[name] if name in [s.name for s in doc.styles] else \
            doc.styles.add_style(name, WD_STYLE_TYPE.PARAGRAPH)
        if base and st.name != "Normal":
            st.base_style = doc.styles[base]
        f, pf = st.font, st.paragraph_format
        rpr = style_rpr(st)
        for tag in ("color",):
            for old in rpr.findall(qn(f"w:{tag}")):
                rpr.remove(old)
        if font:
            set_fonts(rpr, font)
        elif rpr.find(qn("w:rFonts")) is not None:
            set_fonts(rpr, "Times New Roman")
        if size is not None:
            f.size = Pt(size)
            put(rpr, "szCs", RPR_ORDER, val=int(size * 2))
        if bold is not None:
            f.bold = bold
            f.cs_bold = bold
        if italic is not None:
            f.italic = italic
        if align:
            pf.alignment = ALIGN[align]
        if first is not None:
            pf.first_line_indent = Cm(first)
        if left is not None:
            pf.left_indent = Cm(left)
        if right is not None:
            pf.right_indent = Cm(right)
        if line is not None:
            pf.line_spacing = line
        if before is not None:
            pf.space_before = Pt(before)
        if after is not None:
            pf.space_after = Pt(after)
        if keep_next is not None:
            pf.keep_with_next = keep_next
            pf.keep_together = keep_next
        if page_break is not None:
            pf.page_break_before = page_break
        if no_hyphen:
            put(style_ppr(st), "suppressAutoHyphens", PPR_ORDER)
        if outline is not None:
            put(style_ppr(st), "outlineLvl", PPR_ORDER, val=outline)
        return st

    normal = para_style("Normal", base=None, size=14, line=1.5, before=0, after=0, align="left")
    normal.paragraph_format.widow_control = True
    set_fonts(style_rpr(normal), "Times New Roman")
    set_lang(style_rpr(normal), "ru-RU")

    para_style("RF Body", align="justify", first=1.25)
    para_style("RF Source", align="left", first=1.25)
    para_style("Heading 1", size=14, bold=True, italic=False, align="left", first=1.25, before=0, after=18,
               keep_next=True, page_break=True, no_hyphen=True, outline=0)
    para_style("Heading 2", size=14, bold=True, italic=False, align="left", first=1.25, before=12, after=6,
               keep_next=True, no_hyphen=True, outline=1)
    para_style("Heading 3", size=14, bold=True, italic=False, align="left", first=1.25, before=12, after=6,
               keep_next=True, no_hyphen=True, outline=2)
    para_style("RF Structural", size=14, bold=True, align="center", first=0, after=18, page_break=True,
               no_hyphen=True)
    para_style("RF Figure", align="center", first=0, line=1.0, before=6, keep_next=True)
    para_style("RF Figure Caption", align="center", first=0, after=6, no_hyphen=True)
    para_style("RF Table Caption", align="left", first=0, before=6, keep_next=True, no_hyphen=True)
    para_style("RF Table Text", size=12, align="left", first=0, line=1.0)
    para_style("RF Code", size=CODE_PT, font="Courier New", align="left", first=0, line=1.0, no_hyphen=True)
    para_style("RF Placeholder", size=12, align="center", first=0, line=1.0, before=6, keep_next=True)
    para_style("RF Title", align="center", first=0, line=1.0, no_hyphen=True)
    para_style("Footer", size=12, align="center", first=0, line=1.0)
    for lvl, indent in ((1, 0.0), (2, 0.5), (3, 1.0)):
        st = para_style(f"toc {lvl}", align="left", first=0, left=indent)
        st.element.set(qn("w:styleId"), f"TOC{lvl}")
        put(style_ppr(st), "tabs", PPR_ORDER)
        tab = OxmlElement("w:tab")
        tab.set(qn("w:val"), "right")
        tab.set(qn("w:leader"), "dot")
        tab.set(qn("w:pos"), str(TEXT_W_TWIPS))
        style_ppr(st).find(qn("w:tabs")).append(tab)

    # --- страница, колонтитулы ---
    sec = doc.sections[0]
    sec.page_width, sec.page_height = Mm(PAGE_W), Mm(PAGE_H)
    sec.left_margin, sec.right_margin = Mm(MARGIN_L), Mm(MARGIN_R)
    sec.top_margin, sec.bottom_margin = Mm(MARGIN_T), Mm(MARGIN_B)
    sec.header_distance, sec.footer_distance = Mm(10), Mm(10)
    sec.different_first_page_header_footer = True     # титул: номер не печатается
    sec.first_page_footer.is_linked_to_previous = False

    def add_field(paragraph, instr, placeholder=""):
        def fld(kind):
            r = paragraph.add_run()
            el = OxmlElement("w:fldChar")
            el.set(qn("w:fldCharType"), kind)
            if kind == "begin":
                el.set(qn("w:dirty"), "true")
            r._r.append(el)
        fld("begin")
        r = paragraph.add_run()
        it = OxmlElement("w:instrText")
        it.set(qn("xml:space"), "preserve")
        it.text = f" {instr} "
        r._r.append(it)
        fld("separate")
        paragraph.add_run(placeholder)
        fld("end")

    footer_p = sec.footer.paragraphs[0]
    footer_p.style = doc.styles["Footer"]
    add_field(footer_p, "PAGE", "1")

    # --- настройки: обновление полей при открытии, переносы ---
    settings = doc.settings.element
    put(settings, "autoHyphenation", SETTINGS_ORDER, val="true")
    put(settings, "consecutiveHyphenLimit", SETTINGS_ORDER, val=3)
    put(settings, "doNotHyphenateCaps", SETTINGS_ORDER, val="true")
    put(settings, "updateFields", SETTINGS_ORDER, val="true")
    tfl = settings.find(qn("w:themeFontLang"))
    if tfl is not None:
        tfl.set(qn("w:val"), "ru-RU")
    for cs in settings.iter(qn("w:compatSetting")):
        if cs.get(qn("w:name")) == "compatibilityMode":
            cs.set(qn("w:val"), "15")

    cp = doc.core_properties
    cp.title, cp.subject, cp.author, cp.last_modified_by = TOPIC, "Курсовая работа", "", ""
    cp.language, cp.comments, cp.keywords = "ru-RU", "", "MQTT, IoT, Android"

    # --- inline-разметка ---
    def add_runs(paragraph, text, *, bold=False, size=None):
        for chunk, b, it, code, todo in parse_inline(text):
            run = paragraph.add_run(chunk)
            run.bold = (b or bold) or None
            if it:
                run.italic = True
            if size:
                run.font.size = Pt(size)
            if code:
                rpr = run._r.get_or_add_rPr()
                set_fonts(rpr, "Courier New")
                set_lang(rpr, "en-US")
            if todo:
                run.font.highlight_color = WD_COLOR_INDEX.YELLOW
        return paragraph

    def paragraph(style, text="", **kw):
        p = doc.add_paragraph(style=doc.styles[style])
        if text:
            add_runs(p, text, **kw)
        return p

    # --- титульный лист (только поля-заглушки, решение 217) ---
    for line in ("[Наименование министерства]", "[Наименование вуза]", "[Институт/факультет]", "[Кафедра]"):
        paragraph("RF Title", line)
    p = paragraph("RF Title", "КУРСОВАЯ РАБОТА", bold=True, size=16)
    p.paragraph_format.space_before = Pt(170)
    p.paragraph_format.space_after = Pt(12)
    paragraph("RF Title", "по дисциплине [Дисциплина]").paragraph_format.space_after = Pt(12)
    paragraph("RF Title", f"на тему «{TOPIC}»", bold=True)
    for k, line in enumerate(("[Студент: ФИО, группа]", "[Руководитель: ФИО, должность]")):
        p = paragraph("RF Title", line)
        p.alignment = WD_ALIGN_PARAGRAPH.LEFT
        p.paragraph_format.left_indent = Cm(8.5)
        p.paragraph_format.line_spacing = 1.5
        if k == 0:
            p.paragraph_format.space_before = Pt(150)
    p = paragraph("RF Title", f"[Город] {YEAR}")
    put(p._p.get_or_add_pPr(), "framePr", PPR_ORDER, w=TEXT_W_TWIPS, wrap="around", vAnchor="margin",
        hAnchor="margin", xAlign="center", yAlign="bottom")

    # --- содержание: поле TOC (уровни 1–3), Word обновит его при открытии ---
    paragraph("RF Structural", "СОДЕРЖАНИЕ")
    toc_entries = []
    for _, blocks in docs:
        for b in blocks:
            if b.kind == "heading" and b.level <= 3:
                toc_entries.append((b.level, heading_display(b)[0]))
    toc_paras = []
    for k, (lvl, text) in enumerate(toc_entries or [(1, "")]):
        p = doc.add_paragraph(style=doc.styles[f"toc {lvl}"])
        if k == 0:
            r = p.add_run()
            el = OxmlElement("w:fldChar")
            el.set(qn("w:fldCharType"), "begin")
            el.set(qn("w:dirty"), "true")
            r._r.append(el)
            r = p.add_run()
            it = OxmlElement("w:instrText")
            it.set(qn("xml:space"), "preserve")
            it.text = ' TOC \\o "1-3" \\h \\z \\u '
            r._r.append(it)
            r = p.add_run()
            el = OxmlElement("w:fldChar")
            el.set(qn("w:fldCharType"), "separate")
            r._r.append(el)
        p.add_run(plain_text(text.replace("\n", " ")))
        p.add_run("\t")
        toc_paras.append(p)
    r = toc_paras[-1].add_run()
    el = OxmlElement("w:fldChar")
    el.set(qn("w:fldCharType"), "end")
    r._r.append(el)

    # --- основной текст ---
    pending_gap = [False]

    def body_par(style, text, **kw):
        p = paragraph(style, text, **kw)
        if pending_gap[0]:
            p.paragraph_format.space_before = Pt(6)
            pending_gap[0] = False
        return p

    def set_cell(cell, text, *, bold=False, align="left", width_twips=None):
        p = cell.paragraphs[0]
        p.style = doc.styles["RF Table Text"]
        p.alignment = ALIGN[align]
        add_runs(p, text, bold=bold)
        tcpr = cell._tc.get_or_add_tcPr()
        if width_twips:
            for old in tcpr.findall(qn("w:tcW")):
                tcpr.remove(old)
            w = OxmlElement("w:tcW")
            w.set(qn("w:w"), str(width_twips))
            w.set(qn("w:type"), "dxa")
            tcpr.insert(0, w)

    def column_widths(header, rows):
        cols = len(header)
        longest_word, weight = [0] * cols, [0.0] * cols
        for row in [header] + rows:
            for c, cell in enumerate(row):
                t = plain_text(cell)
                longest_word[c] = max([longest_word[c]] + [len(w) for w in t.split()])
                weight[c] = max(weight[c], min(len(t), 80))
        weight = [w ** 0.8 + 4 for w in weight]
        total = TEXT_W_TWIPS
        widths = [total * w / sum(weight) for w in weight]
        min_w = [min(total / cols * 1.6, lw * 115 + 230) for lw in longest_word]  # ≈ 115 twips на знак 12 pt
        for _ in range(4):
            short = [c for c in range(cols) if widths[c] < min_w[c]]
            if not short:
                break
            for c in short:
                widths[c] = min_w[c]
            free = [c for c in range(cols) if c not in short]
            rest = total - sum(widths[c] for c in short)
            fw = sum(weight[c] for c in free) or 1
            for c in free:
                widths[c] = rest * weight[c] / fw
        widths = [int(w) for w in widths]
        widths[-1] += total - sum(widths)
        return widths

    def add_table(b):
        cap = b.caption.text if b.caption else ""
        body_par("RF Table Caption", f"Таблица {b.num} — {cap}" if cap else f"Таблица {b.num}")
        cols = len(b.header)
        table = doc.add_table(rows=1 + len(b.rows), cols=cols)
        table.style = doc.styles["Table Grid"]
        table.alignment = WD_TABLE_ALIGNMENT.CENTER
        tblpr = table._tbl.tblPr
        put(tblpr, "tblW", TBLPR_ORDER, w=TEXT_W_TWIPS, type="dxa")
        put(tblpr, "tblLayout", TBLPR_ORDER, type="fixed")
        widths = column_widths(b.header, b.rows)
        grid = table._tbl.tblGrid
        for gc, w in zip(grid.findall(qn("w:gridCol")), widths):
            gc.set(qn("w:w"), str(w))
        for r_i, row in enumerate([b.header] + b.rows):
            tr = table.rows[r_i]
            trpr = tr._tr.get_or_add_trPr()
            if r_i == 0:
                trpr.append(OxmlElement("w:tblHeader"))      # шапка повторяется на новой странице
            for c in range(cols):
                align = "center" if r_i == 0 else (b.aligns[c] if c < len(b.aligns) else "left")
                set_cell(tr.cells[c], row[c], bold=r_i == 0, align=align, width_twips=widths[c])
                if r_i == 0:
                    tr.cells[c].paragraphs[0].paragraph_format.keep_with_next = True
        pending_gap[0] = True

    def add_figure(b):
        path = figure_path(b.fig_id)
        if path is not None:
            img = DocxImage.from_file(str(path))
            w_cm = img.px_width / (img.horz_dpi or 72) * 2.54
            h_cm = img.px_height / (img.vert_dpi or 72) * 2.54
            max_h = PORTRAIT_MAX_H_CM if h_cm > 1.3 * w_cm else FIG_MAX_H_CM
            scale = min(1.0, FIG_MAX_W_CM / w_cm, max_h / h_cm)
            p = body_par("RF Figure", "")
            p.add_run().add_picture(str(path), width=Cm(w_cm * scale))
            pending_gap[0] = False
        else:
            p = body_par("RF Placeholder", "")
            ppr = p._p.get_or_add_pPr()
            bdr = put(ppr, "pBdr", PPR_ORDER)
            for side in ("top", "left", "bottom", "right"):
                e = OxmlElement(f"w:{side}")
                for k, v in (("val", "single"), ("sz", "6"), ("space", "8"), ("color", "auto")):
                    e.set(qn(f"w:{k}"), v)
                bdr.append(e)
            p.paragraph_format.left_indent = Cm(1)
            p.paragraph_format.right_indent = Cm(1)
            add_runs(p, b.desc)
            p.add_run().add_break(WD_BREAK.LINE)
            r = p.add_run("[рисунок будет добавлен]")
            r.italic = True
            r.font.highlight_color = WD_COLOR_INDEX.YELLOW
        cap = paragraph("RF Figure Caption", f"Рисунок {b.num} — {b.text}")
        cap.paragraph_format.keep_with_next = False
        pending_gap[0] = True

    def add_code(b):
        lines = b.code or [""]
        for k, line in enumerate(lines):
            p = doc.add_paragraph(style=doc.styles["RF Code"])
            if line:
                run = p.add_run(line)
                set_lang(run._r.get_or_add_rPr(), "en-US")
            if k == 0:
                p.paragraph_format.space_before = Pt(6)
            if k < len(lines) - 1:
                p.paragraph_format.keep_with_next = True
            else:
                p.paragraph_format.space_after = Pt(6)
        pending_gap[0] = False

    counts = {"heading": [0, 0, 0, 0], "table": 0, "figure": 0, "picture": 0, "placeholder": 0, "code": 0}
    for name, blocks in docs:
        sources_mode = False
        for b in blocks:
            if b.kind == "heading":
                text, kind = heading_display(b)
                counts["heading"][min(b.level, 3)] += 1
                if b.level == 1:
                    sources_mode = b.text.strip().lower() == SOURCES_TITLE
                    p = doc.add_paragraph(style=doc.styles["Heading 1"])
                    if kind in ("structural", "appendix"):
                        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
                        p.paragraph_format.first_line_indent = Cm(0)
                    lines = text.split("\n")
                    for k, line in enumerate(lines):
                        add_runs(p, line, bold=True)
                        if k < len(lines) - 1:
                            p.add_run().add_break(WD_BREAK.LINE)
                else:
                    style = "Heading 2" if b.level == 2 else "Heading 3"
                    add_runs(doc.add_paragraph(style=doc.styles[style]), text, bold=True)
                pending_gap[0] = False
            elif b.kind == "para":
                body_par("RF Body", b.text)
            elif b.kind == "list":
                for k, (marker, text, _) in enumerate(b.items):
                    if sources_mode:
                        body_par("RF Source", f"{marker} {text}")
                    else:
                        mk = marker if not b.ordered else f"{int(b.items[0][0][:-1]) + k}{marker[-1]}"
                        body_par("RF Body", f"{mk} {text}")
            elif b.kind == "table":
                counts["table"] += 1
                add_table(b)
            elif b.kind == "figure":
                counts["figure"] += 1
                counts["picture" if figure_path(b.fig_id) else "placeholder"] += 1
                add_figure(b)
            elif b.kind == "code":
                counts["code"] += 1
                add_code(b)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    doc.save(str(out_path))
    h = counts["heading"]
    shown = out_path.relative_to(ROOT) if out_path.is_relative_to(ROOT) else out_path
    report.add("Сборка", f"записан {shown}")
    report.add("Сборка", f"заголовков: 1-го уровня — {h[1]}, 2-го — {h[2]}, 3-го — {h[3]}; таблиц — "
                         f"{counts['table']}; рисунков — {counts['figure']} (картинок {counts['picture']}, "
                         f"рамок {counts['placeholder']}); листингов — {counts['code']}")
    return counts


def heading_display(b):
    """Текст заголовка в документе и вид: structural (прописными, по центру), chapter, appendix, sub."""
    text = b.text.strip()
    if b.level > 1:
        return text, "sub"
    kind = heading_kind(text)
    if kind == "structural":
        return text.upper(), kind
    if kind == "appendix":
        m = APPENDIX_RE.match(text)
        title = m.group(2).strip()
        return f"ПРИЛОЖЕНИЕ {m.group(1).upper()}" + (f"\n{title}" if title else ""), kind
    return text, kind


def parse_inline(text):
    """Разбор `код`, **жирный**, *курсив*, [[TODO…]] → [(текст, bold, italic, code, todo)]."""
    runs, buf = [], []
    bold = italic = False
    i, n = 0, len(text)

    def flush():
        if buf:
            runs.append(("".join(buf), bold, italic, False, False))
            buf.clear()

    while i < n:
        if text.startswith("[[TODO", i):
            j = text.find("]]", i)
            j = n if j < 0 else j + 2
            flush()
            runs.append((text[i:j], bold, italic, False, True))
            i = j
            continue
        c = text[i]
        if c == "\\" and i + 1 < n and text[i + 1] in "\\`*_[]|#":
            buf.append(text[i + 1])
            i += 2
            continue
        if c == "`":
            j = text.find("`", i + 1)
            if j > i:
                flush()
                runs.append((text[i + 1:j], bold, italic, True, False))
                i = j + 1
                continue
        if text.startswith("**", i):
            flush()
            bold = not bold
            i += 2
            continue
        if c == "*":
            prev = text[i - 1] if i else " "
            nxt = text[i + 1] if i + 1 < n else " "
            if (not italic and not nxt.isspace()) or (italic and not prev.isspace()):
                flush()
                italic = not italic
                i += 1
                continue
        buf.append(c)
        i += 1
    flush()
    return runs


def main():
    global SRC, IMG
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="только проверки, docx не собирать")
    ap.add_argument("--out", type=Path, default=None, help=f"путь к docx (по умолчанию {OUT.relative_to(ROOT)})")
    ap.add_argument("--src", type=Path, default=SRC, help="каталог исходников (по умолчанию docs/coursework)")
    ap.add_argument("--strict", action="store_true", help="код возврата 1 при ошибках в отчёте")
    args = ap.parse_args()
    SRC, IMG = args.src.resolve(), args.src.resolve() / "img"
    out = args.out or SRC / OUT.name

    report = Report()
    files = source_files()
    report.add("Сборка", "файлы: " + ", ".join(p.name for p in files))
    docs = [(p.name, parse_file(p, report)) for p in files]
    figures, tables = number_document(docs, report)

    for num, b in figures.items():
        path = figure_path(b.fig_id)
        if path is None:
            shown = "файла нет, в документе рамка"
        elif path.parent == IMG:
            shown = f"img/{path.name}"
        else:
            shown = str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path)
        report.add("Рисунки", f"{num} {b.fig_id} ({b.file}:{b.line}) — {shown}", "info" if path else "warn")
    for num, b in tables.items():
        cap = b.caption.text if b.caption else "—"
        report.add("Таблицы", f"{num} {plain_text(cap)[:70]} ({b.file}:{b.line}, строк: {len(b.rows)})")

    check_refs(files, figures, tables, report)
    check_sources(files, docs, report)
    check_todos(files, report)
    check_layout(docs, report)
    check_volume(docs, report)

    if not args.check:
        try:
            build_docx(docs, figures, tables, out.resolve(), report)
        except ImportError:
            report.add("Сборка", "нет python-docx: /tmp/rf-venv/bin/pip install python-docx", "error")
        report.add("Оформление", "содержание и номера страниц в нём — поле TOC: Word обновит его при открытии "
                                 "(запрос «Обновить поля»), иначе — F9 по содержанию")
        report.add("Оформление", "переносы включены (autoHyphenation), расставляет их Word при вёрстке")
        report.add("Оформление", "«Продолжение таблицы N.M» над перенесённой частью таблицы (ГОСТ 7.32) "
                                 "python-docx не ставит: страницы известны только Word — проверить при "
                                 "финальной вёрстке; шапка таблиц повторяется автоматически")
    report.print()
    return 1 if args.strict and report.errors else 0


if __name__ == "__main__":
    sys.exit(main())
