"""Схемы пояснительной записки курсовой: docs/coursework/img/<id>.png (200 dpi, ширина до 16 см).

Рисует все схемные маркеры [[FIG: id | …]] из docs/coursework/0*.md; снимки экрана (SCREENSHOTS) снимаются
при валидации, графики эксперимента (EXPERIMENT_CHARTS) строит tools/experiments/analyze.py — здесь они не рисуются. Содержание схем — по описаниям маркеров и тексту вокруг них,
факты сверены с кодом: shared/protocol (Topics), server/data/.../db/migration/V1__core.sql,
deploy/docker-compose.yml, deploy/toxiproxy.json, ProbeStateMachine, порты agent:domain.

Запуск (matplotlib — из venv вне репозитория, см. tools/coursework/README.md):
  /tmp/rf-venv/bin/python -I tools/coursework/figures.py              # все схемы
  /tmp/rf-venv/bin/python -I tools/coursework/figures.py er-model     # выбранные
"""
import math
import re
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.patches import Circle, FancyArrowPatch, FancyBboxPatch, Polygon, Rectangle, Wedge  # noqa: E402
from matplotlib.path import Path as MPath  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "docs" / "coursework"
OUT = SRC / "img"
SCREENSHOTS = {"agent-wizard", "agent-status"}
# Графики главы 4 рисует tools/experiments/analyze.py в docs/experiments/img/<id>.png; build_docx.py берёт их оттуда
EXPERIMENT_CHARTS = {"traffic", "latency", "server-cpu", "server-mem", "timeline-5000", "detection", "degradation"}

DPI = 200
WIDTH = 16.0  # см: ширина холста = ширине рисунка в записке, кегли на рисунке — как в печати
INK = "#222222"
MUTED = "#5a5a5a"
F1 = "#f2f2f2"
F2 = "#e2e2e2"
F3 = "#cfcfcf"
NOTE = "#fbf7e4"
OUT_F, OUT_L = "#dbe7f3", "#2c5d8a"     # от устройства
IN_F, IN_L = "#f6d9bd", "#9c4f16"       # к устройству
MONO = "DejaVu Sans Mono"
DASH = (0, (3, 2))

plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 8, "path.simplify": False})
WARNINGS = []


def text_width(text, size, mono=False):
    """Оценка ширины текста, см (DejaVu, кириллица: средний знак ≈ 0,6 кегля)."""
    return max(len(line) for line in text.split("\n")) * size * 0.6 / 72 * 2.54


class Box:
    def __init__(self, x, y, w, h):
        self.x, self.y, self.w, self.h = x, y, w, h

    @property
    def cx(self):
        return self.x + self.w / 2

    @property
    def cy(self):
        return self.y + self.h / 2

    @property
    def top(self):
        return self.y + self.h

    @property
    def right(self):
        return self.x + self.w

    def l(self, dy=0.0):
        return (self.x, self.cy + dy)

    def r(self, dy=0.0):
        return (self.right, self.cy + dy)

    def t(self, dx=0.0):
        return (self.cx + dx, self.top)

    def b(self, dx=0.0):
        return (self.cx + dx, self.y)


class Canvas:
    """Холст в сантиметрах: (0, 0) — левый нижний угол."""

    def __init__(self, height, width=WIDTH):
        self.fig = plt.figure(figsize=(width / 2.54, height / 2.54), dpi=DPI)
        self.ax = self.fig.add_axes((0, 0, 1, 1))
        self.ax.set_xlim(0, width)
        self.ax.set_ylim(0, height)
        self.ax.axis("off")
        self.w, self.h = width, height

    def text(self, x, y, s, *, size=7.5, ha="center", va="center", color=INK, weight="normal", style="normal",
             family=None, bg=None, rotation=0, z=6, ls=1.25):
        kw = dict(ha=ha, va=va, fontsize=size, color=color, fontweight=weight, fontstyle=style,
                  rotation=rotation, zorder=z, linespacing=ls)
        if family:
            kw["fontfamily"] = family
        if bg:
            kw["bbox"] = dict(facecolor=bg, edgecolor="none", pad=0.6)
        return self.ax.text(x, y, s, **kw)

    def box(self, x, y, w, h, text="", *, fill="white", edge=INK, lw=0.8, ls="-", r=0.1, size=8, weight="normal",
            color=INK, ha="center", family=None, z=2, sub=None, sub_size=7, sub_family=None, sub_color=MUTED):
        style = f"round,pad=0,rounding_size={r}" if r else "square,pad=0"
        self.ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle=style, fc=fill, ec=edge, lw=lw, ls=ls, zorder=z))
        tx = x + w / 2 if ha == "center" else x + 0.15
        if text and sub:
            self.text(tx, y + h / 2 + 0.03, text, size=size, weight=weight, color=color, ha=ha, va="bottom",
                      family=family, z=z + 1)
            self.text(tx, y + h / 2 - 0.03, sub, size=sub_size, color=sub_color, ha=ha, va="top",
                      family=sub_family, z=z + 1)
        elif text:
            self.text(tx, y + h / 2, text, size=size, weight=weight, color=color, ha=ha, family=family, z=z + 1)
        return Box(x, y, w, h)

    def frame(self, x, y, w, h, title=None, *, ls="-", lw=0.9, fill="none", size=8.5, edge=INK, z=1, r=0.15):
        self.ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle=f"round,pad=0,rounding_size={r}", fc=fill,
                                         ec=edge, lw=lw, ls=ls, zorder=z))
        if title:
            self.text(x + 0.2, y + h - 0.2, title, size=size, weight="bold", ha="left", va="top", z=z + 1)
        return Box(x, y, w, h)

    def arrow(self, pts, *, label=None, seg=None, at=0.5, off=(0, 0.1), lha="center", lva="bottom", color=INK,
              lw=0.8, ls="-", head="-|>", size=7, lcolor=None, family=None, z=4, lbg="white", hs=7):
        """Ломаная со стрелкой на конце (head="-" — без стрелки); подпись — у сегмента seg (по умолчанию средний)."""
        verts = [tuple(p) for p in pts]
        codes = [MPath.MOVETO] + [MPath.LINETO] * (len(verts) - 1)
        self.ax.add_patch(FancyArrowPatch(path=MPath(verts, codes), arrowstyle=head, mutation_scale=hs, lw=lw,
                                          ls=ls, color=color, shrinkA=0, shrinkB=0, zorder=z, joinstyle="miter"))
        if label:
            k = (len(verts) - 2) // 2 if seg is None else seg
            (x1, y1), (x2, y2) = verts[k], verts[k + 1]
            lx, ly = x1 + (x2 - x1) * at + off[0], y1 + (y2 - y1) * at + off[1]
            self.text(lx, ly, label, size=size, ha=lha, va=lva, color=lcolor or color, bg=lbg, family=family,
                      z=z + 1)

    def curve(self, verts, *, color=INK, lw=0.8, ls="-", hs=7, z=4):
        """Кубическая кривая Безье (4 точки) со стрелкой — петли автоматов."""
        codes = [MPath.MOVETO, MPath.CURVE4, MPath.CURVE4, MPath.CURVE4]
        self.ax.add_patch(FancyArrowPatch(path=MPath(verts, codes), arrowstyle="-|>", mutation_scale=hs, lw=lw,
                                          ls=ls, color=color, shrinkA=0, shrinkB=0, zorder=z))

    def dot(self, x, y, color=INK, ms=2.4):
        self.ax.plot([x], [y], "o", ms=ms, color=color, zorder=6)

    def check(self, name):
        """Подписи за краем холста и наложения подписей друг на друга — предупреждения в stdout."""
        self.fig.canvas.draw()
        renderer = self.fig.canvas.get_renderer()
        fw, fh = self.fig.bbox.width, self.fig.bbox.height
        boxes = []
        for t in self.ax.texts:
            if not t.get_text().strip():
                continue
            bb = t.get_window_extent(renderer)
            if bb.x0 < -1 or bb.y0 < -1 or bb.x1 > fw + 1 or bb.y1 > fh + 1:
                WARNINGS.append(f"{name}: подпись за краем холста — «{t.get_text()[:40]}»")
            boxes.append((bb, t.get_text()))
        for i, (a, ta) in enumerate(boxes):
            for b, tb in boxes[i + 1:]:
                w = min(a.x1, b.x1) - max(a.x0, b.x0)
                h = min(a.y1, b.y1) - max(a.y0, b.y0)
                if w > 3 and h > 3:
                    WARNINGS.append(f"{name}: подписи накладываются — «{ta[:30]}» и «{tb[:30]}»")

    def save(self, name):
        """PNG 200 dpi, обрезанный по содержимому: ширина в записке = ширине на холсте (≤ 16 см)."""
        from PIL import Image

        self.check(name)
        OUT.mkdir(parents=True, exist_ok=True)
        path = OUT / f"{name}.png"
        self.fig.savefig(path, dpi=DPI, metadata={"Software": None})
        plt.close(self.fig)
        with Image.open(path) as im:
            rgb = im.convert("RGB")
        bg = Image.new("RGB", rgb.size, (255, 255, 255))
        from PIL import ImageChops

        bbox = ImageChops.difference(rgb, bg).getbbox()
        if bbox:
            pad = 12
            bbox = (max(0, bbox[0] - pad), max(0, bbox[1] - pad), min(rgb.width, bbox[2] + pad),
                    min(rgb.height, bbox[3] + pad))
            rgb = rgb.crop(bbox)
        rgb.save(path, dpi=(DPI, DPI), optimize=True)
        return path


class Seq:
    """Диаграмма последовательности: участники сверху, линии жизни, сообщения сверху вниз."""

    def __init__(self, c, parts, top, head_w=2.4, head_h=0.85, size=7.8):
        self.c, self.x = c, {}
        self.head_bottom = top - head_h
        for key, label, x in parts:
            self.x[key] = x
            c.box(x - head_w / 2, top - head_h, head_w, head_h, label, fill=F2, size=size, weight="bold", r=0.06)
        self.y = self.head_bottom - 0.5

    def msg(self, a, b, label, *, dashed=False, size=7, step=0.5, color=INK):
        """Сообщение a → b; ответ — dashed. Подпись над стрелкой, по центру между линиями жизни."""
        x1, x2 = self.x[a], self.x[b]
        y = self.y - 0.27 * label.count("\n")
        self.c.arrow([(x1, y), (x2, y)], ls=(0, (4, 2.5)) if dashed else "-", color=color)
        self.c.text((x1 + x2) / 2, y + 0.07, label, size=size, va="bottom", bg="white", color=color, z=7)
        self.y = y - step
        return y

    def self_call(self, a, label, *, size=7, step=0.6, w=0.42, h=0.3, color=INK, side="right"):
        x = self.x[a]
        half = 0.27 * label.count("\n") * 0.5
        y = self.y - half
        d = 1 if side == "right" else -1
        self.c.arrow([(x, y + h / 2), (x + d * w, y + h / 2), (x + d * w, y - h / 2), (x + d * 0.03, y - h / 2)],
                     color=color)
        self.c.text(x + d * (w + 0.12), y, label, size=size, ha="left" if d > 0 else "right", bg="white",
                    color=color, z=7)
        self.y = y - h / 2 - half - step
        return y

    def note(self, a, b, text, *, size=7, pad_x=0.95, fill=NOTE):
        x1 = min(self.x[a], self.x[b]) - pad_x
        x2 = max(self.x[a], self.x[b]) + pad_x
        need = text_width(text, size) + 0.35
        if x2 - x1 < need:
            mid = (x1 + x2) / 2
            x1, x2 = mid - need / 2, mid + need / 2
        if x2 > self.c.w - 0.05:
            x1, x2 = x1 - (x2 - self.c.w + 0.05), self.c.w - 0.05
        if x1 < 0.05:
            x1, x2 = 0.05, x2 + 0.05 - x1
        h = 0.28 * (text.count("\n") + 1) + 0.22
        top = self.y + 0.18
        self.c.box(x1, top - h, x2 - x1, h, "", fill=fill, edge=MUTED, lw=0.6, r=0.04, z=8)
        self.c.text((x1 + x2) / 2, top - h / 2, text, size=size, z=9)
        self.y = top - h - 0.5
        return top, top - h

    def gap(self, d):
        self.y -= d

    def open_group(self):
        """Верх рамки alt/группы; сообщения внутри начинаются ниже ярлыка."""
        top = self.y + 0.1
        self.y -= 0.8
        return top

    def group(self, x1, x2, top, bottom, tag, cond="", *, size=7):
        c = self.c
        c.ax.add_patch(Rectangle((x1, bottom), x2 - x1, top - bottom, fc="none", ec=MUTED, lw=0.7, zorder=3))
        tw = 0.22 + text_width(tag, size) * 1.05
        c.ax.add_patch(Polygon([(x1, top), (x1 + tw, top), (x1 + tw, top - 0.2), (x1 + tw - 0.12, top - 0.32),
                                (x1, top - 0.32)], closed=True, fc=F2, ec=MUTED, lw=0.7, zorder=3))
        c.text(x1 + 0.08, top - 0.16, tag, size=size, weight="bold", ha="left", z=8)
        if cond:
            c.text(x1 + tw + 0.12, top - 0.16, cond, size=size, ha="left", style="italic", z=8, bg="white")

    def finish(self, bottom=None):
        bottom = self.y + 0.2 if bottom is None else bottom
        for x in self.x.values():
            self.c.ax.plot([x, x], [self.head_bottom, bottom], color=MUTED, lw=0.7, ls=(0, (3, 3)), zorder=1)
        return bottom


# ---------- 1.1 Уровни IoT-системы ----------

def iot_layers():
    c = Canvas(8.2)
    x0, w, h, gap, y0 = 3.7, 9.4, 1.42, 0.42, 0.35
    layers = [
        ("Устройства", "датчики, исполнительные механизмы, агент"),
        ("Шлюз / брокер сообщений", "транспорт, аутентификация, маршрутизация"),
        ("Платформа", "реестр, приём данных, тени, команды, хранение"),
        ("Приложения", "консоль оператора, отчёты, интеграции"),
    ]
    fills = ["#f7f7f7", "#efefef", "#e6e6e6", "#dddddd"]
    boxes = []
    for k, (name, sub) in enumerate(layers):
        y = y0 + k * (h + gap)
        boxes.append(c.box(x0, y, w, h, fill=fills[k], r=0.08))
        c.text(x0 + 0.3, y + h / 2 + 0.2, name, size=9, weight="bold", ha="left")
        c.text(x0 + 0.3, y + h / 2 - 0.25, sub, size=8, ha="left", color=MUTED)
    dev = boxes[0]
    c.box(dev.right - 2.05, dev.y + 0.2, 1.85, dev.h - 0.4, "edge:\nлокальная\nобработка", fill="white",
          edge=MUTED, ls=DASH, lw=0.7, size=7, r=0.08, z=3)
    for k in range(3):
        y1, y2 = boxes[k].top, boxes[k + 1].y
        c.arrow([(x0 + 3.2, y1), (x0 + 3.2, y2)], hs=6)
        c.arrow([(x0 + 5.6, y2), (x0 + 5.6, y1)], hs=6, ls=(0, (4, 2.5)))
    top = boxes[-1].top
    c.box(x0 + w + 0.35, y0, 1.45, top - y0, fill=F3, r=0.08)
    c.text(x0 + w + 1.075, (y0 + top) / 2, "Безопасность и\nуправление устройствами", size=8.3, weight="bold",
           rotation=90)
    c.arrow([(1.65, y0 + 0.2), (1.65, top - 0.2)], lw=1.2, hs=9)
    c.text(1.2, (y0 + top) / 2, "Телеметрия, события", size=8, rotation=90)
    c.arrow([(2.7, top - 0.2), (2.7, y0 + 0.2)], lw=1.2, hs=9, ls=(0, (5, 3)))
    c.text(3.15, (y0 + top) / 2, "Команды, параметры", size=8, rotation=90)
    return c.save("iot-layers")


# ---------- 1.2 Издатель — подписчик ----------

def mqtt_pubsub():
    c = Canvas(8.4)
    agents = [c.box(0.2, 6.15 - k * 1.85, 2.4, 0.95, f"Агент {n}", sub="издатель", fill=F1, size=8.5,
                    weight="bold") for k, n in enumerate("ABC")]
    broker = c.box(6.45, 2.3, 3.0, 5.0, "", fill=F2, r=0.12)
    c.text(broker.cx, broker.top - 0.45, "Брокер MQTT", size=8.8, weight="bold")
    c.text(broker.cx, broker.top - 0.85, "маршрутизация\nпо фильтрам\nподписок", size=7.5, color=MUTED, va="top")
    for k, t in enumerate(("сохраняемые сообщения", "постоянные сессии", "завещание (LWT)")):
        c.box(5.95, 1.45 - k * 0.55, 4.0, 0.42, t, fill="white", edge=MUTED, lw=0.6, size=7.5, r=0.2)
    c.arrow([(broker.cx, broker.y), (broker.cx, 1.87)], head="-", color=MUTED, lw=0.6)
    for k, a in enumerate(agents):
        y = a.cy + (0.18 if k == 0 else 0)
        c.arrow([(a.right, y), (broker.x, y)], label=f"rf/v1/dev/{'ABC'[k]}/telemetry", family=MONO, size=6.8)
    c.text(13.95, 6.95, "подписчики (фильтр подписки)", size=7, color=MUTED, style="italic")
    srv = c.box(12.0, 5.05, 3.9, 1.4, "Серверная часть", fill=F1, weight="bold", size=8.3,
                sub="rf/v1/dev/+/telemetry", sub_family=MONO, sub_size=7)
    obs = c.box(12.0, 2.45, 3.9, 1.6, "Инструмент\nнаблюдения", fill=F1, weight="bold", size=8.3,
                sub="rf/v1/#", sub_family=MONO, sub_size=7)
    c.arrow([(broker.right, srv.cy - 0.3), (srv.x, srv.cy - 0.3)], label="телеметрия\nA, B, C", off=(0, -0.08),
            lva="top", size=7)
    c.arrow([(broker.right, obs.cy), (obs.x, obs.cy)], label="все\nсообщения", size=7)
    cmd = dict(color=IN_L, ls=(0, (4, 2.5)))
    c.arrow([(srv.x, srv.cy + 0.3), (broker.right, srv.cy + 0.3)], label="PUBLISH", size=7, **cmd)
    a = agents[0]
    c.arrow([(broker.x, a.cy - 0.22), (a.right, a.cy - 0.22)], label="rf/v1/dev/A/cmd/req", family=MONO,
            size=6.8, off=(0, -0.07), lva="top", **cmd)
    c.text(12.0, 1.35, "———  публикация телеметрии\n- - -   команда агенту A", size=7, ha="left", va="top",
           color=MUTED)
    return c.save("mqtt-pubsub")


# ---------- 1.3 Синхронизация тени ----------

def shadow_sync():
    c = Canvas(9.5)
    s = Seq(c, [("app", "Приложение /\nоператор", 2.0), ("pf", "Платформа", 7.6), ("dev", "Устройство", 13.2)],
            top=9.4, head_w=3.0)
    s.msg("app", "pf", "1) запись desired: intervalSec = 60 (версия 13)")
    s.note("pf", "pf", "документ тени\ndesired v13: {intervalSec: 60}\nreported v12: {intervalSec: 30}\n"
                       "delta: {intervalSec: 60}", pad_x=2.1, fill=F1)
    s.gap(0.2)
    s.msg("pf", "dev", "2) desired v13 (при подключении —\nсохранённое состояние)")
    s.self_call("dev", "применение\nпараметров")
    s.msg("dev", "pf", "3) reported: {intervalSec: 60}, appliedVersion 13")
    s.self_call("pf", "4) пересчёт delta")
    s.note("pf", "pf", "desired v13, reported v13\ndelta: пусто — параметры применены", pad_x=2.1, fill=F1)
    s.finish()
    return c.save("shadow-sync")


# ---------- 2.1 Структурная схема ----------

def system_overview():
    c = Canvas(11.8)
    lab = dict(size=6.9)
    rest = c.frame(0.15, 6.45, 3.1, 4.75, "Ресторан")
    for k, r in enumerate(("кухня", "зал", "сборка")):
        c.box(0.4, 9.2 - k * 1.2, 2.6, 0.85, f"Агент: {r}", fill=F1, size=8)
    ext = c.box(0.15, 0.4, 3.1, 1.75, "Бэкенд компании,\nкухонная станция,\nэталонная цель", fill="white",
                ls=(0, (4, 2.5)), size=7.5)
    c.frame(3.9, 0.15, 8.2, 11.5, "Стенд RestoFleet (Docker Compose)")
    nginx = c.box(8.4, 9.2, 3.3, 1.1, "nginx", sub="TLS для REST, 8443", fill=F2, weight="bold")
    tox = c.box(4.3, 7.55, 2.7, 1.1, "Toxiproxy", sub="отказы, задержки", fill=F2, weight="bold")
    backend = c.box(8.4, 5.4, 3.3, 1.4, "Backend", sub="Ktor + Koin", fill=F2, weight="bold", size=9)
    mosq = c.box(4.3, 2.3, 3.0, 1.6, "Mosquitto 2.1", sub="TLS 8883,\nDynamic Security", fill=F2, weight="bold")
    pg = c.box(9.45, 2.5, 2.4, 1.0, "PostgreSQL 17", fill=F2, weight="bold", size=7.8)
    console = c.box(12.6, 10.05, 3.25, 1.15, "Консоль /\nSwagger UI", fill=F1, size=8)
    sim = c.box(12.6, 0.75, 3.25, 1.15, "Симулятор\nустройств", fill=F1, size=8)
    # агенты
    c.arrow([(rest.right, 9.75), (nginx.x, 9.75)], label="регистрация: HTTPS 8443", **lab)
    c.arrow([(rest.right, tox.cy), (tox.x, tox.cy)], label="проверки", **lab)
    c.arrow([(tox.right, tox.cy - 0.25), (8.95, tox.cy - 0.25), (8.95, nginx.y)], label="цель api",
            seg=0, off=(0, 0.07), **lab)
    c.arrow([(2.6, rest.y), (2.6, mosq.cy + 0.2), (mosq.x, mosq.cy + 0.2)], label="MQTT 5 /\nTLS", seg=1,
            at=0.55, off=(0, 0.07), **lab)
    c.arrow([(1.0, rest.y), (1.0, ext.top)], label="HTTPS-\nпроверки", seg=0, off=(0.12, 0), lha="left",
            lva="center", lbg=None, **lab)
    # консоль и nginx
    c.arrow([(console.x, console.cy), (11.3, console.cy), (11.3, nginx.top)], label="HTTPS 8443", seg=0,
            off=(0, 0.07), **lab)
    c.text(console.cx, console.y - 0.25, "WebSocket — этап П", size=6.8, color=MUTED, style="italic")
    c.arrow([(nginx.cx, nginx.y), (nginx.cx, backend.top)], label="HTTP 8080,\nтолько внутри\nстенда;\n/health",
            off=(0.12, 0), lha="left", lva="center", lbg=None, **lab)
    # брокер и бэкенд
    c.arrow([(8.85, backend.y), (8.85, 3.55), (mosq.right, 3.55)], label="$CONTROL/dynamic-security/v1",
            seg=0, at=0.55, off=(-0.12, 0), lha="right", lva="center", family=MONO, lbg=None, size=6.6)
    c.arrow([(mosq.right, 2.75), (9.2, 2.75), (9.2, backend.y)], label="$share/\nbackend/…", seg=1, at=0.62,
            off=(0.12, 0), lha="left", lva="center", family=MONO, lbg=None, size=6.6)
    c.arrow([(11.2, backend.y), (11.2, pg.top)], label="SQL", off=(0.1, 0), lha="left", lva="center",
            lbg=None, **lab)
    # симулятор
    c.arrow([(sim.x, 1.3), (5.8, 1.3), (5.8, mosq.y)], label="MQTT 5", seg=0, off=(0, 0.07), **lab)
    c.arrow([(14.2, sim.top), (14.2, 6.1), (backend.right, 6.1)], label="REST-\npolling", seg=0,
            off=(0.12, 0), lha="left", lva="center", lbg=None, **lab)
    return c.save("system-overview")


# ---------- 2.2 Слои Clean Architecture ----------

def clean_layers():
    c = Canvas(9.2)
    cx, cy = 4.55, 4.6
    r1, r2, r3 = 1.55, 2.95, 4.35
    c.ax.add_patch(Circle((cx, cy), r3, fc="#f7f7f7", ec=INK, lw=0.9, zorder=1))
    c.ax.add_patch(Wedge((cx, cy), r2, 0, 180, width=r2 - r1, fc="#e9e9e9", ec=INK, lw=0.8, zorder=2))
    c.ax.add_patch(Wedge((cx, cy), r2, 180, 360, width=r2 - r1, fc="#e0e0e0", ec=INK, lw=0.8, zorder=2))
    c.ax.add_patch(Circle((cx, cy), r1, fc="#c9c9c9", ec=INK, lw=0.9, zorder=3))
    c.text(cx, cy + 0.6, "domain", size=10, weight="bold", z=6)
    c.text(cx, cy - 0.2, "agent:domain\nserver:domain\nshared:kernel …", size=6.5, family=MONO, z=6)
    rm, ro = (r1 + r2) / 2, (r2 + r3) / 2
    c.text(cx, cy + rm + 0.28, "presentation", size=9, weight="bold", z=6)
    c.text(cx, cy + rm - 0.3, "agent:presentation\nserver:api", size=6.5, family=MONO, z=6)
    c.text(cx, cy - rm + 0.3, "agent:data\nserver:data", size=6.5, family=MONO, z=6)
    c.text(cx, cy - rm - 0.32, "data", size=9, weight="bold", z=6)
    c.text(cx, cy + ro + 0.05, "composition root", size=9, weight="bold", z=6)
    c.text(cx, cy - ro - 0.05, "agent:app · server:app", size=6.5, family=MONO, z=6)

    def polar(ang, r):
        a = math.radians(ang)
        return cx + r * math.cos(a), cy + r * math.sin(a)

    for ang in (125, 160, 200, 235):
        c.arrow([polar(ang, r3 - 0.2), polar(ang, r1 + 0.05)], lw=1.1, hs=9, z=7)
    notes = [
        ("composition root", "модули Koin, Application, main;\nсборка графа объектов, внедрение\nчерез конструктор",
         polar(35, ro)),
        ("presentation", "MVI-экраны агента, маршруты Ktor;\nк домену — только через use cases", polar(25, rm)),
        ("data", "репозитории, источники данных,\nDTO протокола, мапперы;\nреализуют порты domain", polar(-25, rm)),
        ("domain", "сущности, use cases, порты\n(интерфейсы репозиториев, Clock,\nIdGenerator); без Android SDK,\n"
                   "Ktor, Exposed, Room, сериализации, Koin", polar(-62, 1.25)),
    ]
    for (name, desc, (ax_, ay_)), y in zip(notes, (8.4, 6.5, 4.4, 2.1)):
        c.text(9.95, y + 0.05, name, size=8.5, weight="bold", ha="left", va="bottom")
        c.text(9.95, y - 0.05, desc, size=7.3, ha="left", va="top")
        c.arrow([(9.85, y), (ax_, ay_)], head="-", color=MUTED, lw=0.6, z=8)
        c.dot(ax_, ay_, ms=2.5)
    c.arrow([(9.95, 0.35), (10.85, 0.35)], lw=1.1, hs=9)
    c.text(11.0, 0.35, "зависимость — только внутрь", size=7.3, ha="left")
    return c.save("clean-layers")


# ---------- 2.3 Дерево топиков ----------

def topic_tree():
    c = Canvas(9.4)

    def n(x, y, w, label, kind="mid"):
        fill, edge, ls, color = {"mid": ("white", INK, "-", INK), "out": (OUT_F, OUT_L, "-", INK),
                                 "in": (IN_F, IN_L, "-", INK), "future": ("white", MUTED, DASH, MUTED)}[kind]
        return c.box(x, y - 0.27, w, 0.54, label, fill=fill, edge=edge, ls=ls, color=color, size=8, family=MONO,
                     r=0.08, lw=1.0 if kind in ("out", "in") else 0.8)

    def link(a, b, ls="-", color=INK):
        xm = a.right + 0.3
        c.arrow([a.r(), (xm, a.cy), (xm, b.cy), b.l()], head="-", ls=ls, color=color, lw=0.8)

    def tag(after, text, dx=0.25, ls="-"):
        w = 0.3 + 0.13 * len(text)
        c.box(after.right + dx, after.cy - 0.19, w, 0.38, text, fill=F2 if ls == "-" else "white", edge=MUTED,
              lw=0.6, size=6.8, r=0.18, ls=ls)
        return after.right + dx + w

    rf = n(0.2, 5.4, 0.8, "rf")
    v1 = n(1.4, 5.4, 0.8, "v1")
    dev = n(2.6, 6.4, 0.95, "dev")
    did = n(3.95, 6.4, 2.0, "{deviceId}")
    for a, b in ((rf, v1), (v1, dev), (dev, did)):
        link(a, b)
    leaves = {}
    for name, y, kind in (("status", 9.0, "out"), ("telemetry", 8.2, "out"), ("probe", 7.4, "out"),
                          ("events", 6.6, "out"), ("shadow", 5.0, "mid"), ("cmd", 3.0, "mid")):
        leaves[name] = n(6.65, y, 1.9, name, kind)
        link(did, leaves[name])
    subs = {}
    for parent, name, y, kind in (("shadow", "reported", 5.5, "out"), ("shadow", "desired", 4.5, "in"),
                                  ("cmd", "req", 3.5, "in"), ("cmd", "res", 2.5, "out")):
        subs[name] = n(9.25, y, 1.7, name, kind)
        link(leaves[parent], subs[name])
    e = tag(leaves["status"], "retained")
    c.box(e + 0.15, leaves["status"].cy - 0.19, 0.75, 0.38, "LWT", fill=F2, edge=MUTED, lw=0.6, size=6.8, r=0.18)
    tag(subs["desired"], "retained")
    rest = n(2.6, 1.0, 0.95, "rest", "future")
    rid = n(3.95, 1.0, 2.6, "{restaurantId}", "future")
    bc = n(6.95, 1.0, 1.9, "broadcast", "future")
    for a, b in ((v1, rest), (rest, rid), (rid, bc)):
        link(a, b, ls=DASH, color=MUTED)
    tag(bc, "этап Д", ls=DASH)
    lx = 12.3
    for k, (fill, edge, text) in enumerate(((OUT_F, OUT_L, "публикует\nустройство"),
                                            (IN_F, IN_L, "публикует\nсервер"),
                                            ("white", INK, "промежуточный\nуровень"))):
        y = 9.0 - k * 0.85
        c.box(lx, y - 0.18, 0.55, 0.36, fill=fill, edge=edge, lw=1.0, r=0.06)
        c.text(lx + 0.7, y, text, size=7, ha="left")
    c.text(lx, 6.25, "retained — сохраняемое\nсообщение\nLWT — завещание\nброкера", size=7, ha="left", va="top",
           color=MUTED)
    c.text(lx, 4.0, "Пример полного\nтопика:", size=7, ha="left", va="top", color=MUTED)
    c.text(lx, 3.35, "rf/v1/dev/{deviceId}/\nshadow/desired", size=6.6, ha="left", va="top", family=MONO,
           color=MUTED)
    return c.save("topic-tree")


# ---------- 2.4 Отправка и исполнение команды ----------

def cmd_sequence():
    c = Canvas(15.0)
    s = Seq(c, [("op", "Оператор\n(Swagger UI)", 1.4), ("be", "Backend", 4.6), ("pg", "PostgreSQL", 7.6),
                ("mq", "Mosquitto", 10.6), ("ag", "Агент", 13.9)], top=14.9, head_w=2.5, head_h=0.95)
    s.gap(0.15)
    s.msg("op", "be", "1) POST /devices/{id}/commands\n{type, params, ttlSec}")
    s.msg("be", "pg", "INSERT commands: queued,\nexpiresAt; COMMIT")
    s.msg("be", "mq", "2) PUBLISH cmd/req, QoS 1: Response Topic,\nCorrelation Data = cmdId, Message Expiry 300 с")
    s.msg("be", "op", "201 {cmdId, status: queued}", dashed=True)
    s.note("mq", "ag", "3) агент офлайн — сообщение ждёт в постоянной\nсессии, пока не истёк Message Expiry",
           pad_x=0.9)
    s.msg("mq", "ag", "4) cmd/req")
    s.self_call("ag", "повтор? — по журналу", side="left")
    s.msg("ag", "mq", "cmd/res received (outbox)")
    s.msg("mq", "be", "cmd/res received")
    s.msg("be", "pg", "5) delivered")
    s.self_call("ag", "6) cmdId → журнал;\nисполнение (≤ 60 с)", side="left")
    s.msg("ag", "mq", "cmd/res succeeded |\nfailed | unsupported")
    s.msg("mq", "be", "cmd/res (итог)")
    s.msg("be", "pg", "7) итоговый статус;\nдедупликация (cmdId, status)")
    s.msg("ag", "mq", "8) PUBACK cmd/req\nпосле обработки")
    s.gap(0.1)
    top = s.open_group()
    s.self_call("be", "таймер (каждые 5 с)")
    s.msg("be", "pg", "expired")
    s.group(3.3, 9.0, top, s.y + 0.22, "alt", "[итога нет к expiresAt]")
    s.finish(s.y + 0.05)
    return c.save("cmd-sequence")


# ---------- 2.5 ER-модель ----------

def er_model():
    c = Canvas(15.8)
    RH, HH, KW = 0.33, 0.46, 0.95

    def table(x, top, w, name, rows):
        h = HH + RH * len(rows) + 0.08
        y = top - h
        c.ax.add_patch(Rectangle((x, y), w, h, fc="white", ec=INK, lw=0.8, zorder=2))
        c.ax.add_patch(Rectangle((x, top - HH), w, HH, fc=F3, ec=INK, lw=0.8, zorder=2))
        c.text(x + w / 2, top - HH / 2, name, size=7.4, weight="bold", family=MONO, z=4)
        c.ax.plot([x + KW - 0.08] * 2, [y, top - HH], color=F3, lw=0.6, zorder=3)
        for k, (key, field) in enumerate(rows):
            ry = top - HH - RH * (k + 0.5) - 0.04
            if key:
                c.text(x + 0.07, ry, key, size=5.7, weight="bold", ha="left", z=4, color=MUTED)
            c.text(x + KW, ry, field, size=6.4, ha="left", z=4, weight="bold" if key.startswith("PK") else "normal",
                   style="italic" if "JSONB" in field else "normal")
        return Box(x, y, w, h)

    def card(x, y, s, ha="left"):
        c.text(x, y, s, size=7, weight="bold", ha=ha, z=6)

    line = dict(head="-", lw=0.8, z=1)
    W = 3.45
    col = [0.12, 4.25, 8.38, 12.51]
    top1, top2 = 15.6, 10.75
    rest = table(col[0], top1, W, "restaurants", [("PK", "id uuid"), ("UK", "code"), ("", "name, city,"),
                                                  ("", "address, timezone")])
    dev = table(col[1], top1, W, "devices", [("PK", "id (d-<UUID>)"), ("FK", "restaurant_id"),
                                             ("", "role, name, status,"), ("", "transport, mode,"),
                                             ("", "last_seen_at,"), ("", "decommissioned_at,"),
                                             ("", "model, api_level,"), ("", "manufacturer,"), ("", "os_version,"),
                                             ("", "agent_version")])
    cred = table(col[2], top1, W, "device_credentials", [("PK, FK", "device_id"), ("", "password_hash"),
                                                         ("", "revoked_at")])
    shad = table(col[3], top1, W, "shadows", [("PK, FK", "device_id"), ("", "desired JSONB,"),
                                              ("", "desired_version,"), ("", "reported JSONB,"),
                                              ("", "reported_version,"), ("", "errors JSONB,"), ("", "applied_at,"),
                                              ("", "reported_at")])
    tok = table(col[0], top2 - 0.3, W, "provisioning_tokens", [("PK", "id uuid"), ("UK", "token_hash"),
                                                               ("FK", "restaurant_id"), ("FK", "device_id"),
                                                               ("", "role, name,"), ("", "expires_at, used_at")])
    sess = table(col[1], top2, W, "device_sessions", [("PK, FK", "device_id"), ("PK", "conn_id"),
                                                      ("", "connected_at,"), ("", "disconnected_at,"),
                                                      ("", "reason")])
    tel = table(col[2], top2, W, "telemetry", [("PK, FK", "device_id"), ("PK", "ts"), ("", "seq, battery_level,"),
                                               ("", "charging, temp_c,"), ("", "thermal, rssi,"),
                                               ("", "free_mb, avail_mb,"), ("", "fg_app"), ("", "payload JSONB")])
    prb = table(col[3], top2, W, "probe_results", [("PK, FK", "device_id"), ("PK", "ts"), ("PK", "target"),
                                                   ("", "ok, status, error,"), ("", "total_ms, dns_ms,"),
                                                   ("", "connect_ms, tls_ms,"), ("", "ttfb_ms")])
    top3 = tel.y - 0.75
    ev = table(col[1], top3, W, "events", [("PK", "id (identity)"), ("FK", "device_id"), ("", "ts, type"),
                                           ("", "payload JSONB")])
    cmd = table(col[2], top3, W, "commands", [("PK", "cmd_id (c-<UUID>)"), ("FK", "device_id"),
                                              ("", "type, status,"), ("", "expires_at,"), ("", "delivered_at,"),
                                              ("", "finished_at, reason"), ("", "params JSONB,"),
                                              ("", "result JSONB")])
    users = table(col[0], tok.y - 0.75, W, "users", [("PK", "id uuid"), ("UK", "login"), ("", "password_hash,"),
                                                     ("", "role")])
    rtok = table(col[0], users.y - 0.75, W, "refresh_tokens", [("PK", "id uuid"), ("FK", "user_id"),
                                                               ("UK", "token_hash"), ("", "expires_at,"),
                                                               ("", "revoked_at")])
    # restaurants — devices, provisioning_tokens
    y = rest.top - 0.75
    c.arrow([(rest.right, y), (dev.x, y)], **line)
    card(rest.right + 0.07, y + 0.14, "1")
    card(dev.x - 0.07, y + 0.14, "N", "right")
    c.arrow([(1.2, rest.y), (1.2, tok.top)], **line)
    card(1.35, rest.y - 0.17, "1")
    card(1.35, tok.top + 0.17, "N")
    # provisioning_tokens — devices (0..1)
    y = dev.y + 0.45
    c.arrow([(3.0, tok.top), (3.0, y), (dev.x, y)], **line)
    card(3.15, tok.top + 0.17, "N")
    card(dev.x - 0.07, y + 0.14, "0..1", "right")
    # devices — device_credentials, shadows
    c.arrow([(dev.right, cred.cy), (cred.x, cred.cy)], **line)
    card(dev.right + 0.07, cred.cy + 0.14, "1")
    card(cred.x - 0.07, cred.cy + 0.14, "1", "right")
    y = shad.y + 0.35
    c.arrow([(dev.right, y), (shad.x, y)], **line)
    card(dev.right + 0.07, y + 0.14, "1")
    card(shad.x - 0.07, y + 0.14, "1", "right")
    # devices — таблицы 1:N (шина)
    bus, bus2 = top2 + 0.42, top3 + 0.38
    xb = dev.cx
    gap_x = (col[1] + W + col[2]) / 2
    c.arrow([(xb, dev.y), (xb, bus)], **line)
    card(xb + 0.15, dev.y - 0.17, "1")
    c.arrow([(sess.cx, bus), (prb.cx, bus)], **line)
    for t in (sess, tel, prb):
        c.arrow([(t.cx, bus), (t.cx, t.top)], **line)
        card(t.cx + 0.15, t.top + 0.17, "N")
    c.arrow([(gap_x, bus), (gap_x, bus2)], **line)
    c.arrow([(ev.cx, bus2), (cmd.cx, bus2)], **line)
    for t in (ev, cmd):
        c.arrow([(t.cx, bus2), (t.cx, t.top)], **line)
        card(t.cx + 0.15, t.top + 0.17, "N")
    for x, yb in ((xb, bus), (tel.cx, bus), (gap_x, bus), (gap_x, bus2)):
        c.dot(x, yb, ms=2.6)
    # users — refresh_tokens
    c.arrow([(1.2, users.y), (1.2, rtok.top)], **line)
    card(1.35, users.y - 0.17, "1")
    card(1.35, rtok.top + 0.17, "N")
    c.text(col[3], prb.y - 0.6, "PK — первичный ключ\n(несколько PK —\nсоставной),\nFK — внешний ключ,\n"
                                "UK — уникальное поле;\nкурсив — JSONB;\n1, N, 0..1 — кратность.\n"
                                "Поля created_at,\nupdated_at не показаны.", size=6.8, ha="left", va="top",
           color=MUTED)
    return c.save("er-model")


# ---------- 2.6 Провижининг ----------

def provisioning_sequence():
    c = Canvas(19.6)
    s = Seq(c, [("adm", "Администратор\n(Swagger UI)", 1.25), ("ng", "nginx", 3.9), ("be", "Backend", 6.55),
                ("pg", "PostgreSQL", 9.2), ("mq", "Mosquitto", 11.85), ("ag", "Агент\n(мастер)", 14.6)],
            top=19.5, head_w=2.4, head_h=0.95, size=7.3)
    s.gap(0.25)
    s.msg("adm", "ng", "1) POST /api/v1/provisioning/\ntokens {restaurantId, role,\nname}, JWT", size=6.8)
    s.msg("ng", "be", "HTTP 8080")
    s.msg("be", "pg", "INSERT token_hash =\nSHA-256(код), TTL 24 ч")
    s.msg("be", "adm", "201 {code}", dashed=True)
    s.note("adm", "ag", "2) администратор передаёт код технику; техник вводит его в мастере агента", pad_x=1.1)
    s.msg("ag", "ng", "3) POST /api/v1/provision {code, deviceInfo,\nagentVersion}, HTTPS 8443")
    s.msg("ng", "be", "HTTP 8080")
    tx_top = s.open_group()
    s.msg("be", "pg", "4) UPDATE provisioning_tokens\n… RETURNING (не использован,\nне истёк)")
    s.msg("be", "pg", "INSERT devices (provisioned;\nроль, ресторан из кода)")
    s.msg("be", "pg", "INSERT device_credentials:\nbcrypt(пароль 32 байта)")
    s.msg("be", "pg", "INSERT shadows: desired v1")
    s.msg("be", "mq", "$CONTROL/dynamic-security/v1: createClient\n(username = clientid = deviceId, роль device)")
    s.msg("mq", "be", "ответ (correlationData)", dashed=True)
    alt_top = s.open_group()
    s.msg("be", "pg", "COMMIT")
    split = s.y + 0.22
    s.gap(0.6)
    s.msg("be", "pg", "ROLLBACK — код не погашен")
    s.msg("be", "ag", "503 broker_unavailable", dashed=True)
    bottom = s.y + 0.22
    s.group(5.45, 15.8, alt_top, bottom, "alt", "[брокер создал учётную запись]")
    c.ax.plot([5.45, 15.8], [split, split], color=MUTED, lw=0.7, ls=(0, (4, 3)), zorder=3)
    c.text(5.6, split - 0.17, "[отказ брокера]", size=7, style="italic", ha="left", bg="white", z=8)
    s.group(5.25, 15.95, tx_top, bottom - 0.15, "транзакция")
    s.gap(0.5)
    s.msg("be", "mq", "5) PUBLISH retained shadow/desired v1")
    s.msg("be", "ag", "6) 201 {deviceId, password, restaurantId, role, mqtt {host, port}}", dashed=True)
    s.self_call("ag", "7) шифрование учётной\nзаписи (Android Keystore)", side="left")
    s.msg("ag", "mq", "CONNECT (TLS 8883,\nWill: status offline)")
    s.msg("ag", "mq", "PUBLISH status online")
    s.msg("mq", "ag", "retained desired v1")
    s.msg("ag", "mq", "PUBLISH shadow/reported")
    s.finish(s.y + 0.05)
    return c.save("provisioning-sequence")


# ---------- 3.1 Автомат проверки доступности ----------

def probe_state_machine():
    c = Canvas(8.4)
    st = dict(fill=F2, size=10, weight="bold", family=MONO, r=0.3, lw=1.0)
    unk = c.box(1.2, 3.55, 2.8, 1.0, "UNKNOWN", **st)
    up = c.box(7.0, 5.9, 2.4, 1.0, "UP", **st)
    down = c.box(7.0, 1.3, 2.4, 1.0, "DOWN", **st)
    c.ax.add_patch(Circle((0.5, unk.cy), 0.16, fc=INK, ec=INK, zorder=4))
    c.arrow([(0.66, unk.cy), (unk.x, unk.cy)])
    c.text(0.5, unk.cy - 0.32, "старт\nагента", size=6.8, color=MUTED, va="top")
    ev = "/ probe_state"
    c.arrow([(unk.right, unk.cy + 0.3), (up.x, up.cy)], label=f"успех\n{ev} up", at=0.45, off=(-0.35, 0.3),
            lha="right", size=7.3, lbg=None)
    c.arrow([(unk.right, unk.cy - 0.3), (down.x, down.cy)], label=f"K-я неудача подряд\n{ev} down", at=0.55,
            off=(-0.2, -0.3), lha="right", lva="top", size=7.3, lbg=None)
    c.arrow([(up.x + 0.6, up.y), (down.x + 0.6, down.top)], label=f"K-я неудача\nподряд\n{ev} down",
            off=(-0.12, 0), lha="right", lva="center", size=7.3)
    c.arrow([(down.right - 0.6, down.top), (up.right - 0.6, up.y)], label=f"успех\n{ev} up", off=(0.12, 0),
            lha="left", lva="center", size=7.3)

    def loop(b, x, label, top=True, dx=0.75):
        y = b.top if top else b.y
        d = 1 if top else -1
        c.curve([(x, y), (x - 0.3, y + d * 1.0), (x + dx + 0.3, y + d * 1.0), (x + dx, y)])
        c.text(x + dx / 2, y + d * 0.85, label, size=7.3, va="bottom" if top else "top")

    loop(up, up.x + 0.15, "успех")
    loop(up, up.right - 0.9, "неудача < K")
    loop(down, down.x + 0.85, "неудача (счётчик растёт)", top=False)
    loop(unk, unk.x + 0.3, "неудача < K", top=False)
    c.box(11.1, 3.15, 4.8, 3.05, "", fill=NOTE, edge=MUTED, lw=0.6, r=0.06)
    c.text(11.25, 5.95, "Переход между состояниями\nпубликует событие probe_state\n{target, state, error, failures};\n"
                        "петли событий не порождают.\n\nK — failThreshold цели,\nпо умолчанию 3; UNKNOWN\n"
                        "наружу не передаётся.", size=7.2, ha="left", va="top")
    return c.save("probe-state-machine")


# ---------- 3.2 Компоненты агента ----------

def agent_components():
    c = Canvas(12.2)
    lab = dict(size=6.6)
    src = dict(fill=F1, size=7.5, sub_size=6.6, sub_family=MONO)
    uc = dict(fill="white", size=6.9, family=MONO)
    port = dict(sub_size=6.1, sub_family=MONO, sub_color=MUTED)
    c.text(0.2, 11.55, "agent:data,\nagent:presentation", size=7, weight="bold", ha="left", va="top", color=MUTED)
    dom = c.frame(3.75, 1.65, 7.45, 10.2, "agent:domain — use cases", fill="#f7f7f7")
    x_uc, w_uc = 4.0, 3.8
    col = c.box(x_uc, 9.95, w_uc, 0.9, "CollectTelemetry /\nPublishTelemetry", **uc)
    thr = c.box(x_uc, 9.0, w_uc, 0.6, "EvaluateThresholds", **uc)
    run = c.box(x_uc, 7.9, w_uc, 0.6, "RunProbes", **uc)
    app = c.box(x_uc, 6.3, w_uc, 0.85, "ApplyDesiredShadow", sub="порт ShadowStore", **uc, **port)
    hic = c.box(x_uc, 4.6, w_uc, 1.35, "HandleIncomingCommand\n→ ExecuteCommand", sub="порт ProcessedCommands",
                **uc, **port)
    prov = c.box(x_uc, 3.1, w_uc, 0.85, "Provision", sub="порт CredentialStore", **uc, **port)
    gas = c.box(x_uc, 2.0, w_uc, 0.6, "GetAgentStatus", **uc)
    pob = c.box(8.55, 8.05, 2.45, 0.9, "PublishOrBuffer", fill=F2, size=6.9, family=MONO, weight="bold")
    flo = c.box(8.55, 4.95, 2.45, 0.7, "FlushOutbox", fill=F2, size=6.9, family=MONO, weight="bold")
    tel = c.box(0.15, col.cy - 0.45, 2.75, 0.9, "Тик телеметрии", sub="TelemetryLoop", **src)
    prl = c.box(0.15, run.cy - 0.45, 2.75, 0.9, "Цикл проверок", sub="ProbeLoop", **src)
    inc = c.box(0.15, hic.cy - 0.75, 2.75, app.cy - hic.cy + 1.5, "Входящие\ndesired\nи cmd/req",
                sub="MqttBroker-\nSession", **src)
    scr = c.box(0.15, gas.cy - 0.6, 2.75, prov.cy - gas.cy + 1.2, "Экраны", sub="Setup, Status\n(MVI)", **src)
    c.arrow([tel.r(), col.l()])
    c.arrow([col.b(), thr.t()], hs=6)
    c.arrow([prl.r(), run.l()])
    c.arrow([(inc.right, app.cy), app.l()], label="desired", at=0.37, size=6.2)
    c.arrow([(inc.right, hic.cy), hic.l()], label="cmd/req", at=0.37, size=6.2)
    c.arrow([(scr.right, prov.cy), prov.l()])
    c.arrow([(scr.right, gas.cy), gas.l()])
    # шина в PublishOrBuffer
    bx = 8.15
    for b in (col, thr, run, app, hic):
        c.arrow([b.r(), (bx, b.cy)], head="-")
        c.dot(bx, b.cy, ms=2.2)
    c.arrow([(bx, col.cy), (bx, hic.cy)], head="-")
    c.arrow([(bx, pob.cy), pob.l()])
    # data: сессия и outbox
    data = dict(fill=F1, size=7.4, sub_size=6.2, sub_family=MONO, sub_color=MUTED)
    ses = c.box(14.05, 3.6, 1.85, 5.8, "MQTT-\nсессия\n(HiveMQ,\nTLS)", sub="MqttBroker-\nSession", **data)
    obx = c.box(11.65, 4.85, 2.0, 1.5, "Outbox\n(Room)", sub="RoomOutbox", **data)
    c.arrow([(pob.right, pob.cy + 0.2), (ses.x, pob.cy + 0.2)], label="сессия активна\nи буфер пуст", **lab)
    c.arrow([(pob.cx + 0.6, pob.y), (pob.cx + 0.6, 7.25), (obx.cx, 7.25), (obx.cx, obx.top)], label="иначе",
            seg=2, off=(0.1, 0), lha="left", lva="center", lbg=None, **lab)
    c.arrow([(obx.x, flo.cy), (flo.right, flo.cy)])
    c.arrow([(flo.cx, flo.y), (flo.cx, 4.2), (ses.x, 4.2)],
            label="досылка пачками; после PUBACK\nзапись удаляется из outbox", seg=1, at=0.45, off=(0, -0.08),
            lva="top", lbg=None, **lab)
    # хранилища
    st = dict(fill=F1, size=7.2, sub_size=6.1, sub_family=MONO, sub_color=MUTED)
    ks = c.box(3.75, 0.05, 3.3, 1.05, "Android Keystore", sub="KeystoreCredentialStore", **st)
    sh = c.box(7.25, 0.05, 2.4, 1.05, "Файл тени", sub="FileShadowStore", **st)
    jr = c.box(9.85, 0.05, 3.55, 1.05, "Журнал команд (Room)", sub="RoomProcessedCommands", **st)
    dep = dict(ls=DASH, color=MUTED, hs=8, lw=0.8)
    for b in (ks, sh):
        c.arrow([(b.cx, b.top), (b.cx, dom.y)], **dep)
    c.arrow([(jr.x + 0.6, jr.top), (jr.x + 0.6, dom.y)], **dep)
    c.arrow([(ses.cx, ses.top), (ses.cx, 10.6), (dom.right, 10.6)], label="реализует порты", seg=1,
            off=(0, 0.08), lcolor=MUTED, lbg=None, **dep, **lab)
    c.text(13.35, 2.9, "——▸ поток данных\n- -▸ зависимость\n      data → domain\n      (реализация\n      порта)",
           size=6.4, ha="left", va="top", color=MUTED)
    return c.save("agent-components")


# ---------- 3.5 Конвейер приёма ----------

def ingest_pipeline():
    c = Canvas(8.4)
    y, h = 4.7, 2.3
    mq = c.box(0.1, y, 1.85, h, "Mosquitto", fill=F2, weight="bold", size=7.4)
    hm = c.box(2.3, y, 2.5, h, "HiveMqClient", sub="$share/backend/\nrf/v1/dev/+/…\nручной ack",
               fill=F1, weight="bold", family=MONO, size=7.0, sub_family=MONO, sub_size=6.2)
    q = c.box(5.15, y + 0.5, 1.2, h - 1.0, "очередь", fill="white", size=7)
    for k in range(4):
        c.ax.plot([5.38 + 0.24 * k] * 2, [y + 0.68, y + 0.93], color=MUTED, lw=0.6, zorder=5)
    pl = c.box(6.7, y, 2.4, h, "IngestPipeline", sub="пакет ≤ 500\nсообщений\nили 500 мс", fill=F1,
               weight="bold", family=MONO, size=7.0, sub_size=6.8)
    pr = c.box(9.45, y - 0.35, 4.1, h + 0.7, "", fill=F1)
    c.text(pr.cx, pr.top - 0.3, "IngestProcessor", size=7.0, weight="bold", family=MONO)
    c.text(pr.x + 0.15, pr.top - 0.55, "1) декодирование\n2) status — по порядку\n3) IngestBatch одной\n"
                                       "    транзакцией: телеметрия,\n    проверки, события\n4) reported, cmd/res",
           size=6.7, ha="left", va="top")
    pg = c.box(13.9, y, 2.0, h, "PostgreSQL", fill=F2, weight="bold", size=7.4)
    for a_, b_ in ((mq, hm), (hm, q), (q, pl), (pl, pr), (pr, pg)):
        c.arrow([a_.r(0.25), b_.l(0.25)])
    c.arrow([(pl.cx, pl.top), (pl.cx, 7.65), (mq.cx, 7.65), (mq.cx, mq.top)], label="ack после фиксации",
            seg=1, off=(0, 0.07), color=OUT_L, lw=1.0)
    br = dict(fill="white", edge=MUTED, lw=0.7, size=6.8, r=0.08)
    b1 = c.box(5.2, 1.55, 3.5, 1.15, "временный сбой БД:\nповтор пакета через\n1…30 с, без ack", **br)
    br["size"] = 6.6
    b2 = c.box(8.9, 1.55, 3.55, 1.15, "детерминированный сбой:\nпо одному; «ядовитое»\n→ ack + счётчик", **br)
    b3 = c.box(12.6, 1.55, 3.3, 1.15, "нечитаемое (топик, JSON,\nразмер, NUL, время)\n→ ack + undecodable", **br)
    dash = dict(ls=DASH, color=MUTED)
    c.arrow([(pl.cx + 0.3, pl.y), (pl.cx + 0.3, b1.top)], **dash)
    c.arrow([(b1.x, b1.cy), (4.85, b1.cy), (4.85, 3.9), (pl.x + 0.4, 3.9), (pl.x + 0.4, pl.y)], label="повтор",
            seg=1, off=(0.1, 0), lha="left", lva="center", lbg=None, size=6.6, **dash)
    c.arrow([(b2.cx, pr.y), (b2.cx, b2.top)], **dash)
    c.arrow([(pr.right - 0.4, pr.y), (pr.right - 0.4, 3.3), (b3.cx, 3.3), (b3.cx, b3.top)], **dash)
    c.text(0.15, 1.2, "Пока пакет не зафиксирован, сообщения остаются\nу брокера: перезапуск сервера "
                      "не теряет QoS 1.", size=7, ha="left", va="top", color=MUTED)
    return c.save("ingest-pipeline")


# ---------- 3.7 Стенд Docker Compose ----------

def stand_compose():
    c = Canvas(11.6)
    lab = dict(size=6.6)
    c.frame(3.45, 0.9, 12.45, 9.0, "сеть compose (default)")
    svc = dict(fill=F1, size=7.6, weight="bold", family=MONO, sub_size=6.4, r=0.08)
    small = {**svc, "size": 7.2, "sub_size": 6.2}
    A, B, C = (3.75, 3.8), (8.0, 3.0), (11.5, 4.1)       # x и ширина колонок
    tox = c.box(A[0], 6.3, A[1], 2.75, "toxiproxy",
                sub="api :18443 → apk-host:443\nmqtt :18883 → mosquitto:8883\nrest :18080 → backend:8080\n"
                    "управление :8474", **svc)
    apk = c.box(B[0], 7.6, B[1], 1.2, "apk-host", sub="nginx :443\n(хост :8443)", **svc)
    mq = c.box(C[0], 7.6, C[1], 1.2, "mosquitto", sub=":8883 MQTT/TLS,\nDynamic Security", **svc)
    be = c.box(B[0], 4.3, B[1], 1.5, "backend", sub=":8080, serve:\nREST + ingest", **svc)
    pg = c.box(B[0], 1.3, B[1], 1.75, "postgres", sub=":5432\nTimescaleDB, PG 17", **svc)
    mg = c.box(4.3, 2.35, 3.2, 0.75, "migrate", sub="до старта backend", **small)
    sd = c.box(4.3, 1.3, 3.2, 0.75, "seed", sub="профиль dev", **small)
    sim = c.box(C[0], 1.3, C[1], 1.5, "simulator", sub="профиль experiment\n(эксперимент, КМ4)",
                **{**svc, "fill": "white"})
    c.ax.patches[-1].set_linestyle(DASH)
    c.arrow([(apk.cx, apk.y), (apk.cx, be.top)], label="/api/, /health,\n/swagger", off=(0.1, 0), lha="left",
            lva="center", lbg=None, **lab)
    c.arrow([(be.right, 5.4), (12.4, 5.4), (12.4, mq.y)], label="MQTT/TLS\n:8883", seg=1, off=(0.1, 0),
            lha="left", lva="center", lbg=None, **lab)
    c.arrow([(apk.cx, be.y), (apk.cx, pg.top)], label="SQL :5432", off=(0.1, 0), lha="left", lva="center",
            lbg=None, **lab)
    c.arrow([(mg.right, mg.cy), (pg.x, mg.cy)], hs=6)
    c.arrow([(sd.right, sd.cy), (pg.x, sd.cy)], hs=6)
    dash = dict(ls=DASH, color=MUTED)
    c.arrow([(14.6, sim.top), (14.6, mq.y)], label="MQTT", off=(0.1, 0), lha="left", lva="center", lbg=None,
            **dash, **lab)
    c.arrow([(sim.x, 2.2), (11.25, 2.2), (11.25, 4.7), (be.right, 4.7)], label="REST-\npolling", seg=1,
            at=0.4, off=(0.12, 0), lha="left", lva="center", lbg=None, **dash, **lab)
    # снаружи: эмулятор и хост
    emu = c.box(B[0], 10.35, 7.6, 1.15, "Эмулятор Android", sub="10.0.2.2 — адрес хоста", fill="white", size=7.6,
                weight="bold")
    c.arrow([(apk.cx, emu.y), (apk.cx, apk.top)], label="HTTPS :8443", off=(0.1, 0.05), lha="left",
            lva="center", lbg=None, **lab)
    c.arrow([(mq.cx, emu.y), (mq.cx, mq.top)], label="MQTT/TLS :8883", off=(0.1, 0.05), lha="left",
            lva="center", lbg=None, **lab)
    host = c.box(0.15, 4.25, 2.8, 4.75, "Хост", sub="127.0.0.1", fill="white", size=7.6, weight="bold",
                 sub_family=MONO)
    c.arrow([(host.right, 8.0), (tox.x, 8.0)], label=":8474", **lab)
    c.arrow([(host.right, 5.05), (be.x, 5.05)], label="127.0.0.1:8080", at=0.55, **lab)
    # секреты: deploy/out → /run/secrets
    sec = c.box(0.15, 0.6, 2.8, 2.6, "deploy/out", sub="секреты,\nправа 600,\nвне git", fill=F2, size=7.6,
                weight="bold", family=MONO)
    red = dict(color=IN_L, lw=0.75)
    xb = 3.6
    c.arrow([(sec.right, 1.9), (xb, 1.9)], head="-", **red)
    c.arrow([(xb, sd.cy), (xb, 4.55)], head="-", **red)
    c.arrow([(xb, sd.cy), (sd.x, sd.cy)], hs=6, **red)
    c.arrow([(xb, mg.cy), (mg.x, mg.cy)], hs=6, **red)
    c.arrow([(xb, 3.5), (8.5, 3.5), (8.5, pg.top)], hs=6, **red)
    c.arrow([(xb, 4.55), (be.x, 4.55)], hs=6, **red)
    for yy in (sd.cy, 1.9, mg.cy, 3.5):
        c.dot(xb, yy, color=IN_L)
    c.text(3.75, 3.82, "/run/secrets", size=6.6, ha="left", color=IN_L, family=MONO)
    c.text(0.15, 0.35, "- - - профиль experiment", size=6.6, ha="left", color=MUTED)
    return c.save("stand-compose")


FIGURES = {
    "iot-layers": iot_layers,
    "mqtt-pubsub": mqtt_pubsub,
    "shadow-sync": shadow_sync,
    "system-overview": system_overview,
    "clean-layers": clean_layers,
    "topic-tree": topic_tree,
    "cmd-sequence": cmd_sequence,
    "er-model": er_model,
    "provisioning-sequence": provisioning_sequence,
    "probe-state-machine": probe_state_machine,
    "agent-components": agent_components,
    "ingest-pipeline": ingest_pipeline,
    "stand-compose": stand_compose,
}


def marker_ids():
    ids = []
    for path in sorted(SRC.glob("[0-9][0-9]-*.md")) + sorted(SRC.glob("appendix-*.md")):
        for line in path.read_text(encoding="utf-8").splitlines():
            if m := re.match(r"^\[\[FIG:\s*([^|\]]+?)\s*\|", line.strip()):
                ids.append(m.group(1))
    return ids


def main(argv):
    wanted = argv or list(FIGURES)
    unknown = [w for w in wanted if w not in FIGURES]
    if unknown:
        print("нет такой схемы: " + ", ".join(unknown), file=sys.stderr)
        return 2
    for name in wanted:
        path = FIGURES[name]()
        print(f"  {path.relative_to(ROOT)}")
    ids = marker_ids()
    missing = [i for i in ids if i not in FIGURES and i not in SCREENSHOTS and i not in EXPERIMENT_CHARTS]
    unused = [i for i in FIGURES if i not in ids]
    shots = [i for i in ids if i in SCREENSHOTS]
    charts = [i for i in ids if i in EXPERIMENT_CHARTS]
    print(f"маркеров {len(ids)}: схем {len([i for i in ids if i in FIGURES])}, снимков экрана {len(shots)} "
          f"({', '.join(shots)} — снимает валидатор), графиков эксперимента {len(charts)} "
          f"(строит tools/experiments/analyze.py)")
    if missing:
        print("ВНИМАНИЕ: маркеры без схемы и не снимки экрана: " + ", ".join(missing))
    if unused:
        print("ВНИМАНИЕ: схемы без маркера в тексте: " + ", ".join(unused))
    for w in WARNINGS:
        print("ВНИМАНИЕ: " + w)
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
