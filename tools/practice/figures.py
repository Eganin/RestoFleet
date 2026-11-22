"""Схемы отчёта по практике: docs/practice/img/<id>.png (200 dpi, ширина до 16 см), решение 504.

Холст, рамки, стрелки и проверка наложений подписей — те же, что у схем курсовой: tools/coursework/figures.py
импортируется как модуль и не меняется (меняется только каталог вывода). Здесь — элементы BPMN 2.0
(дорожки, события, задачи, шлюзы, потоки управления и сообщений, аннотации) и четыре схемы:
  it-landscape      — 1.3, ИТ-ландшафт на уровне блоков по ТЗ 1, 3.1, 3.2, 15 (внутреннее устройство не раскрывается);
  bpmn-as-is        — 2.2, обработка обращения «бэкенд не работает» по допущению architecture.md разд. 1;
  incident-pipeline — 3.1, поток данных подсистемы тревог и инцидентов по плану П (ПМ2, ПМ3);
  bpmn-to-be        — 5.1, тот же процесс с RestoFleet.

Запуск (matplotlib — из venv вне репозитория, см. tools/coursework/README.md):
  /tmp/rf-venv/bin/python -I tools/practice/figures.py               # все схемы
  /tmp/rf-venv/bin/python -I tools/practice/figures.py bpmn-as-is    # выбранные
"""
import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "docs" / "practice"
BASE = ROOT / "tools" / "coursework" / "figures.py"


def load_base():
    spec = importlib.util.spec_from_file_location("rf_figures", BASE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.OUT = SRC / "img"          # Canvas.save пишет в module.OUT
    return module


cw = load_base()
Canvas, INK, MUTED, F1, F2, F3, NOTE, MONO, DASH = (cw.Canvas, cw.INK, cw.MUTED, cw.F1, cw.F2, cw.F3, cw.NOTE,
                                                    cw.MONO, cw.DASH)
OUT_L = cw.OUT_L                       # цвет RestoFleet на схемах (синий курсовой: «от устройства»)
OUT_F = cw.OUT_F
MSG_DASH = (0, (4, 2.5))

from matplotlib.patches import Circle, Polygon  # noqa: E402  (matplotlib уже настроен модулем курсовой)


# ---------- элементы BPMN ----------

class Bpmn:
    """Пул с дорожками и элементы BPMN 2.0 на холсте курсовой (координаты в см)."""

    R = 0.22             # радиус события
    G = 0.3              # полудиагональ шлюза

    def __init__(self, c):
        self.c = c

    def pool(self, x, y_top, w, lanes, *, head=0.95, title=None):
        """Дорожки сверху вниз: [(подпись, высота)]. Возвращает {подпись: (y_низ, y_верх)}."""
        c, y, spans = self.c, y_top, {}
        total = sum(h for _, h in lanes)
        if title:
            c.box(x, y_top - total, 0.42, total, "", fill=F2, r=0)
            c.text(x + 0.21, y_top - total / 2, title, size=7.4, weight="bold", rotation=90)
            x, w = x + 0.42, w - 0.42
        for label, h in lanes:
            c.box(x, y - h, w, h, "", fill="white", lw=0.7, r=0)
            c.box(x, y - h, head, h, "", fill=F1, lw=0.7, r=0)
            c.text(x + head / 2, y - h / 2, label, size=6.9, weight="bold", rotation=90, ls=1.15)
            spans[label] = (y - h, y)
            y -= h
        return spans

    def band(self, x, y, w, h, label, *, vertical=False):
        """Свёрнутый пул (внешний участник, «чёрный ящик»)."""
        self.c.box(x, y, w, h, "", fill=F2, lw=0.8, r=0)
        self.c.text(x + w / 2, y + h / 2, label, size=6.8, weight="bold", rotation=90 if vertical else 0,
                    ls=1.15)
        return cw.Box(x, y, w, h)

    def event(self, x, y, kind="start", label=None, *, lpos="below", size=6.4, dx=0.0):
        """kind: start | end | message-start | message-end | timer-start."""
        c, r = self.c, self.R
        lw = 2.0 if kind.endswith("end") else 0.9
        c.ax.add_patch(Circle((x, y), r, fc="white", ec=INK, lw=lw, zorder=5))
        if kind.startswith("message"):
            filled = kind.endswith("end")
            ew, eh = 0.24, 0.16
            c.ax.add_patch(Polygon([(x - ew / 2, y - eh / 2), (x + ew / 2, y - eh / 2), (x + ew / 2, y + eh / 2),
                                    (x - ew / 2, y + eh / 2)], closed=True, fc=INK if filled else "white",
                                   ec=INK if not filled else "white", lw=0.6, zorder=6))
            c.ax.plot([x - ew / 2, x, x + ew / 2], [y + eh / 2, y, y + eh / 2],
                      color="white" if filled else INK, lw=0.6, zorder=7)
        if kind == "timer-start":
            c.ax.add_patch(Circle((x, y), r * 0.72, fc="white", ec=INK, lw=0.6, zorder=6))
            c.ax.plot([x, x], [y, y + r * 0.5], color=INK, lw=0.6, zorder=7)
            c.ax.plot([x, x + r * 0.38], [y, y], color=INK, lw=0.6, zorder=7)
        if label:
            if lpos == "below":
                c.text(x + dx, y - r - 0.06, label, size=size, va="top", color=INK, ls=1.1)
            elif lpos == "above":
                c.text(x + dx, y + r + 0.06, label, size=size, va="bottom", color=INK, ls=1.1)
            elif lpos == "right":
                c.text(x + r + 0.08, y, label, size=size, ha="left", color=INK, ls=1.1)
            else:
                c.text(x - r - 0.08, y, label, size=size, ha="right", color=INK, ls=1.1)
        return cw.Box(x - r, y - r, 2 * r, 2 * r)

    def task(self, x, cy, w, text, *, h=0.95, fill="white", size=6.6, edge=INK):
        return self.c.box(x, cy - h / 2, w, h, text, fill=fill, edge=edge, size=size, r=0.12, z=4)

    def gateway(self, x, y, label=None, *, lpos="above", size=6.4):
        c, g = self.c, self.G
        c.ax.add_patch(Polygon([(x - g, y), (x, y + g), (x + g, y), (x, y - g)], closed=True, fc="white", ec=INK,
                               lw=0.9, zorder=5))
        d = g * 0.38
        c.ax.plot([x - d, x + d], [y - d, y + d], color=INK, lw=1.1, zorder=6)
        c.ax.plot([x - d, x + d], [y + d, y - d], color=INK, lw=1.1, zorder=6)
        if label:
            if lpos == "above":
                c.text(x, y + g + 0.06, label, size=size, va="bottom", ls=1.1)
            else:
                c.text(x, y - g - 0.06, label, size=size, va="top", ls=1.1)
        return cw.Box(x - g, y - g, 2 * g, 2 * g)

    def flow(self, pts, label=None, **kw):
        """Поток управления: сплошная линия со стрелкой."""
        kw.setdefault("size", 6.3)
        self.c.arrow(pts, label=label, hs=6, lw=0.8, **kw)

    def message(self, pts, label=None, **kw):
        """Поток сообщений: пунктир, кружок в начале, стрелка в конце."""
        kw.setdefault("size", 6.3)
        color = kw.pop("color", INK)
        self.c.arrow(pts, label=label, hs=6, lw=0.8, ls=MSG_DASH, color=color, **kw)
        x, y = pts[0]
        self.c.ax.add_patch(Circle((x, y), 0.07, fc="white", ec=color, lw=0.7, zorder=6))

    def note(self, x, y, w, h, text, *, size=6.4, to=None, frm="left"):
        """Текстовая аннотация: скобка слева, пунктир-ассоциация к точке to от левого края или верха (frm="top")."""
        c = self.c
        c.ax.plot([x + 0.15, x, x, x + 0.15], [y + h, y + h, y, y], color=MUTED, lw=0.8, zorder=5)
        c.text(x + 0.12, y + h / 2, text, size=size, ha="left", color=INK, ls=1.15)
        if to is not None:
            start = (x, y + h / 2) if frm == "left" else (x + 0.4, y + h)
            c.ax.plot([start[0], to[0]], [start[1], to[1]], color=MUTED, lw=0.6, ls=(0, (1, 1.5)), zorder=3)



# ---------- 1.1 ИТ-ландшафт ----------

def it_landscape():
    c = Canvas(9.6)
    lab = dict(size=6.5)
    top = 8.75
    rest = c.frame(0.1, 2.55, 5.55, top - 2.55, "Ресторан (в сети их много)", size=8)
    devs = [("Кухня", "планшеты, кухонная\nстанция в браузере"),
            ("Зал", "устройства с приложением\nофицианта"),
            ("Сборка", "устройства с приложением\nсборщика")]
    tags = []
    for k, (role, sub) in enumerate(devs):
        y = 6.85 - k * 1.42
        c.box(0.35, y, 3.95, 1.2, role, sub=sub, fill=F1, weight="bold", size=7.4, sub_size=6.4, sub_color=INK)
        tags.append(c.box(4.45, y + 0.3, 1.0, 0.6, "агент", fill=OUT_F, edge=OUT_L, size=6.5, color=OUT_L, r=0.08,
                          z=4))
    lan = c.box(0.35, 2.8, 5.1, 0.95, "Сеть ресторана:\nWi-Fi, маршрутизатор", fill="white", size=6.8)
    for k in range(3):
        c.ax.plot([0.6, 0.6], [6.85 - k * 1.42, 6.85 - k * 1.42 - 0.22], color=MUTED, lw=0.6, zorder=1)
    isp = c.box(6.2, 4.45, 2.45, 1.75, "Интернет-\nпровайдер,\nDNS", fill=F2, size=7.4, weight="bold")
    c.arrow([lan.r(), (5.9, lan.cy), (5.9, isp.y + 0.35), (isp.x, isp.y + 0.35)], head="-", lw=1.0)
    comp = c.frame(9.2, 3.35, 6.65, top - 3.35, "Инфраструктура ООО «…»", size=8)
    be = c.box(9.45, 6.05, 3.05, 1.75, "Бэкенд компании", sub="API для всех\nприложений", fill=F2,
               weight="bold", size=7.4, sub_size=6.4, sub_color=INK)
    su = c.box(12.8, 6.05, 2.8, 1.75, "СУ — система\nучёта", sub="бэк-офис", fill=F2, weight="bold", size=7.2,
               sub_size=6.4, sub_color=INK)
    c.arrow([be.r(), su.l()], head="<|-|>", lw=0.8, hs=6)
    kds = c.box(9.45, 4.45, 3.05, 1.15, "Кухонная станция", sub="веб-приложение", fill="white", size=7,
                sub_size=6.3, sub_color=INK)
    c.text(14.2, 4.95, "внутреннее\nустройство —\nпо данным\nпредприятия", size=6.3, color=MUTED, style="italic")
    c.arrow([(isp.right, 5.85), (9.15, 5.85), (9.15, be.cy - 0.2), (be.x, be.cy - 0.2)], head="<|-|>", hs=6,
            lw=1.0)
    c.arrow([(isp.right, kds.cy - 0.2), (kds.x, kds.cy - 0.2)], head="<|-|>", hs=6, lw=1.0)
    apps = c.box(9.2, 1.75, 6.65, 1.15, "Приложения вне ресторана", sub="две доставки (клиенты), курьер, хостес",
                 fill=F1, weight="bold", size=7.2, sub_size=6.4, sub_color=INK)
    c.arrow([(apps.cx, apps.top), (apps.cx, comp.y)], head="<|-|>", hs=6, lw=0.8)
    c.text(apps.cx + 0.15, (apps.top + comp.y) / 2, "через интернет", size=6.3, ha="left")
    # RestoFleet
    rf = c.frame(0.1, 0.1, 8.55, 1.95, None, ls=DASH, edge=OUT_L)
    c.text(rf.x + 0.2, rf.top - 0.15, "RestoFleet — внешний наблюдатель, без интеграции", size=7.2, weight="bold",
           ha="left", va="top", color=OUT_L)
    con = c.box(0.35, 0.3, 3.6, 0.95, "консоль поддержки", fill=OUT_F, edge=OUT_L, size=7, color=OUT_L)
    srv = c.box(4.8, 0.3, 3.6, 0.95, "сервер RestoFleet", fill=OUT_F, edge=OUT_L, size=7, color=OUT_L)
    c.arrow([srv.l(), con.r()], color=OUT_L, hs=6)
    c.arrow([(8.2, isp.y), (8.2, srv.top)], color=OUT_L, lw=0.9, hs=6)
    c.text(8.05, 2.3, "MQTT/TLS: телеметрия, события, проверки", size=6.3, ha="right", color=OUT_L)
    c.arrow([(tags[0].right, tags[0].cy), (5.95, tags[0].cy), (5.95, top + 0.35), (be.cx, top + 0.35),
             (be.cx, be.top)], color=OUT_L, ls=(0, (1.5, 1.5)), lw=0.9, hs=6)
    c.text(7.6, top + 0.42, "проверки доступности публичных адресов: лёгкий запрос, не чаще 1 в 30 с",
           size=6.4, va="bottom", color=OUT_L)
    return c.save("it-landscape")


# ---------- 2.1 BPMN «как есть» ----------

def bpmn_as_is():
    c = Canvas(8.5)
    p = Bpmn(c)
    lr, ls_, lo = "Ресторан", "Служба\nподдержки", "Эксплуатация\nсерверной\nчасти"
    lanes = p.pool(0.1, 8.4, 15.8, [(lr, 2.15), (ls_, 3.2), (lo, 2.25)],
                   title="Обращение «бэкенд не работает»: как есть")
    (r0, r1), (s0, s1), (o0, o1) = lanes[lr], lanes[ls_], lanes[lo]
    ry = (r0 + r1) / 2 - 0.15
    sy1, sy2 = s1 - 0.85, s0 + 0.8
    oy = (o0 + o1) / 2
    w = 2.45
    # ресторан
    st = p.event(2.15, ry, "start", "заказы\nне проходят", lpos="below", size=6.2)
    t1 = p.task(2.85, ry, w - 0.1, "Перезапустить\nприложение\nили устройство")
    g1 = p.gateway(t1.right + 0.65, ry, "помогло?", lpos="below")
    ok = p.event(g1.cx, r1 - 0.38, "end", "работа восстановлена", lpos="right", size=6.2)
    t2 = p.task(g1.right + 0.6, ry, w, "Позвонить\nв поддержку")
    p.flow([st.r(), t1.l()])
    p.flow([t1.r(), g1.l()])
    p.flow([g1.t(), ok.b()], label="да", off=(0.08, 0), lha="left", lva="center", lbg=None)
    p.flow([g1.r(), t2.l()], label="нет", off=(0, 0.06))
    # поддержка: верхний ряд
    t3 = p.task(t2.x, sy1, w, "Расспросить\nперсонал: экран,\nсвязь")
    p.flow([t2.b(), t3.t()], label="звонок,\nмессенджер", off=(0.08, 0), lha="left", lva="center", lbg=None)
    t4 = p.task(t3.right + 0.35, sy1, w, "Посмотреть логи\nи мониторинг\nсервера")
    g2 = p.gateway(t4.right + 0.65, sy1, None)
    c.text(g2.cx, sy1 + 0.53, "отказ виден на сервере?", size=6.2, va="bottom")
    t5 = p.task(g2.right + 0.55, sy1, w - 0.15, "Передать\nэксплуатации")
    p.flow([t3.r(), t4.l()])
    p.flow([t4.r(), g2.l()])
    p.flow([g2.r(), t5.l()], label="да", off=(0, 0.06))
    # поддержка: нижний ряд, справа налево
    t6 = p.task(t4.x, sy2, w, "Предположить сеть\nресторана или\nпровайдера")
    t7 = p.task(t3.x, sy2, w, "Посоветовать\nперезагрузить\nроутер")
    end_u = p.event(t7.x - 0.6, sy2, "end", "закрыто,\nпричина не\nподтверждена", lpos="left", size=6.2)
    p.flow([g2.b(), (g2.cx, sy2), t6.r()], label="нет", seg=0, off=(0.08, 0), lha="left", lva="center", lbg=None)
    p.flow([t6.l(), t7.r()])
    p.flow([t7.l(), end_u.r()])
    # эксплуатация
    t8 = p.task(t5.x, oy, t5.w, "Найти и устранить\nотказ сервера")
    end_o = p.event(t8.x - 0.6, oy, "end", "сервис\nвосстановлен", lpos="left", size=6.2)
    p.flow([t5.b(), t8.t()])
    p.flow([t8.l(), end_o.r()])
    # аннотации
    p.note(9.6, r0 + 0.3, 6.1, 1.15, "При общем отказе каждый ресторан\nобращается отдельно: столько\n"
                                             "обращений, сколько ресторанов", to=(t2.right, t2.cy))
    p.note(3.6, o0 + 0.35, 5.6, 1.15, "Логи сервера не показывают, как бэкенд\nвиден из ресторана: «сеть или "
                                        "провайдер»\nостаётся догадкой", to=(t6.x + 0.4, t6.y), frm="top")
    c.text(0.1, 0.05, "Модель построена по допущению (интервью не проведено); каждый шаг проверяется на интервью "
                      "(подраздел 2.3).", size=6.3, ha="left", va="bottom", color=MUTED, style="italic")
    return c.save("bpmn-as-is")


# ---------- 3.1 Поток данных подсистемы тревог и инцидентов ----------

def incident_pipeline():
    c = Canvas(8.0)
    lab = dict(size=6.4)
    y1, h = 6.1, 1.6
    ag = c.box(0.1, y1, 2.2, h, "Агенты,\nсимулятор", fill=F1, weight="bold", size=7.4)
    br = c.box(2.85, y1, 1.8, h, "Брокер\nMQTT", fill=F2, weight="bold", size=7.4)
    ing = c.box(5.2, y1, 5.4, h, "", fill=F1)
    c.text(ing.cx, ing.top - 0.25, "Приём: одна транзакция на пакет", size=7, weight="bold")
    c.text(ing.cx, ing.cy - 0.05, "IngestBatch, HandleStatus →\nAlertProcessor → RuleEngine", family=MONO, size=6.6)
    c.text(ing.cx, ing.y + 0.22, "R-01…R-06; ack брокеру — после фиксации", size=6.2, color=MUTED, style="italic")
    db = c.box(12.4, 3.1, 3.45, h + 3.0 + 0.0, "", fill=F2)
    c.text(db.cx, db.top - 0.3, "PostgreSQL", size=7.4, weight="bold")
    c.text(db.cx, db.top - 0.65, "alerts\nprobe_states\nincidents\nprobe_agg_1m", family=MONO,
           size=6.6, va="top", ls=1.35)
    c.arrow([ag.r(), br.l()])
    c.arrow([br.r(), ing.l()])
    c.arrow([(ing.right, ing.cy), (db.x, ing.cy)])
    c.text((ing.right + db.x) / 2, ing.cy + 0.1, "тревоги,\nсостояния\nпроверок", size=6.2, va="bottom")
    tick = c.frame(0.1, 2.85, 11.0, 2.55, "EvaluateIncidents — раз в 30 с", size=7.2)
    y2 = 3.1
    kw = dict(fill="white", weight="bold", family=MONO, size=6.8, sub_size=6.3, sub_color=INK)
    pa = c.box(0.35, y2, 3.25, 1.4, "ProbeAggregator", sub="окно 2 мин,\nресторан × цель", **kw)
    ic = c.box(3.95, y2, 3.35, 1.4, "IncidentClassifier", sub="таблица решений,\nпороги P и M", **kw)
    rc = c.box(7.65, y2, 3.2, 1.4, "IncidentReconciler", sub="открыть, обновить,\nзакрыть, заменить", **kw)
    c.arrow([pa.r(), ic.l()])
    c.arrow([ic.r(), rc.l()])
    c.arrow([(db.x, 5.7), (pa.cx, 5.7), (pa.cx, pa.top)], label="состояния проверок свежее окна", seg=0, at=0.62,
            off=(0, 0.06), **lab)
    c.arrow([(rc.right, rc.cy), (db.x, rc.cy)], head="<|-|>", hs=6)
    c.text((rc.right + db.x) / 2, rc.cy - 0.12, "инциденты", size=6.2, va="top")
    bus = c.box(0.1, 0.15, 3.4, 1.35, "EventBus", sub="события приёма и тика\nпосле фиксации", fill=F2,
                weight="bold", family=MONO, size=7, sub_size=6.2, sub_color=INK)
    ws = c.box(4.2, 0.15, 4.4, 1.35, "WebSocket /api/v1/ws", sub="статусы, тревоги, инциденты,\nсводка",
               fill=F1, weight="bold", family=MONO, size=6.9, sub_size=6.2, sub_color=INK)
    rest = c.box(12.4, 1.85, 3.45, 0.95, "REST /alerts, /incidents,\n/summary", fill=F1, family=MONO, size=6.3)
    cons = c.box(12.4, 0.15, 3.45, 1.1, "Android-консоль", fill=OUT_F, edge=OUT_L, color=OUT_L, weight="bold",
                 size=7.4)
    c.arrow([(1.8, tick.y), (1.8, bus.top)])
    c.arrow([bus.r(), ws.l()])
    c.arrow([(ws.right, cons.cy), (cons.x, cons.cy)])
    c.text((ws.right + cons.x) / 2, cons.cy + 0.08, "события в реальном времени", size=6.3, va="bottom")
    c.arrow([(db.cx, db.y), (db.cx, rest.top)])
    c.arrow([(rest.cx, rest.y), (rest.cx, cons.top)], head="<|-|>", hs=6)
    return c.save("incident-pipeline")


# ---------- 5.1 BPMN «как будет» ----------

def bpmn_to_be():
    c = Canvas(9.9)
    p = Bpmn(c)
    la, lv, le = "RestoFleet:\nагенты", "RestoFleet:\nсервер", "Инженер\nподдержки"
    pool_top = 9.8
    lanes = p.pool(0.1, pool_top, 15.8, [(la, 1.85), (lv, 2.9), (le, 3.3)],
                   title="Инцидент доступности: как будет")
    (a0, a1), (v0, v1), (e0, e1) = lanes[la], lanes[lv], lanes[le]
    ay = (a0 + a1) / 2
    vy1, vy2 = v1 - 0.8, v0 + 0.75
    ey1, ey2 = e1 - 1.05, e0 + 0.75
    fill = OUT_F
    # агенты
    st = p.event(2.1, ay, "timer-start")
    c.text(2.1, ay - 0.3, "интервал\nпроверки", size=6.0, va="top")
    a_1 = p.task(2.65, ay, 2.3, "Проверить цели\napi, kds, ref", fill=fill)
    a_2 = p.task(a_1.right + 0.4, ay, 2.65, "K = 3 неудачи →\nprobe_state down;\nобрыв связи → LWT", fill=fill)
    p.flow([st.r(), a_1.l()])
    p.flow([a_1.r(), a_2.l()])
    p.note(a_2.right + 0.5, ay - 0.5, 6.5, 1.0, "Обнаружение: K · интервал проверки + шаг\nагрегатора; цель ТЗ — "
                                                "не более 2 мин")
    # сервер
    s_1 = p.task(a_2.x, vy1, 2.65, "Агрегировать\nпо ресторану и цели\n(окно 2 мин)", fill=fill)
    p.flow([a_2.b(), s_1.t()], label="MQTT", off=(0.08, 0), lha="left", lva="center", lbg=None)
    s_2 = p.task(s_1.right + 0.4, vy1, 2.6, "Классифицировать:\nR-07, R-08, R-09\nили R-01", fill=fill)
    s_3 = p.task(s_2.right + 0.4, vy1, 2.65, "Открыть инцидент,\nподавить дочерние\nтревоги", fill=fill)
    s_4 = p.task(s_3.x, vy2, 2.65, "Закрыть, когда\nусловия нет\n2 тика подряд", fill=fill)
    end_s = p.event(s_4.right + 0.55, vy2, "end", "закрыт", lpos="below", size=6.2)
    p.flow([s_1.r(), s_2.l()])
    p.flow([s_2.r(), s_3.l()])
    p.flow([s_3.b(), s_4.t()])
    p.flow([s_4.r(), end_s.l()])
    # инженер
    e_1 = p.task(1.85, ey1, 2.8, "Открыть карточку:\n«Почему так решили»,\nподтвердить")
    wsx = s_3.right + 1.25
    p.flow([s_3.r(), (wsx, s_3.cy), (wsx, e1 - 0.25), (e_1.cx, e1 - 0.25), e_1.t()])
    c.text(12.3, e1 - 0.3, "WebSocket", size=6.3, va="top")
    g = p.gateway(e_1.right + 0.6, ey1, None)
    c.text(g.cx - 0.1, ey1 - 0.55, "вид инцидента?", size=6.2, ha="right", va="top")
    e_2 = p.task(g.right + 0.95, ey1, 2.65, "Передать\nэксплуатации\nс доказательствами")
    e_3 = p.task(e_2.x, ey2, 2.65, "Связаться\nс рестораном")
    p.flow([e_1.r(), g.l()])
    p.flow([g.r(), e_2.l()], label="бэкенд", off=(0, 0.06))
    p.flow([g.b(), (g.cx, ey2), e_3.l()], label="сеть, устройство", seg=1, off=(0, 0.06), size=6.0)
    end_3 = p.event(e_3.right + 0.5, ey2, "message-end")
    end_2 = p.event(e_2.right + 1.25, ey1, "message-end")
    p.flow([e_2.r(), end_2.l()])
    p.flow([e_3.r(), end_3.l()])
    p.note(end_2.right + 0.45, ey1 - 0.5, 15.85 - end_2.right - 0.5, 1.0, "Обращение ресторана при\n"
                                                                         "открытом инциденте —\nпричина уже известна")
    p.note(end_2.right + 0.45, ey2 - 0.55, 15.85 - end_2.right - 0.5, 1.1, "Одна запись вместо тревоги\n"
                                                                          "на каждое устройство;\nR-01, R-05 подавлены")
    # внешние участники (свёрнутые пулы)
    bands_top = e0 - 0.45
    rest = p.band(0.1, bands_top - 0.7, end_3.cx + 0.3 - 0.1, 0.7, "Ресторан")
    ops = p.band(end_2.cx - 0.3, bands_top - 0.7, 15.9 - end_2.cx + 0.3, 0.7, "Эксплуатация серверной части")
    p.message([end_3.b(), (end_3.cx, rest.top)])
    p.message([end_2.b(), (end_2.cx, ops.top)])
    return c.save("bpmn-to-be")


FIGURES = {
    "it-landscape": it_landscape,
    "bpmn-as-is": bpmn_as_is,
    "incident-pipeline": incident_pipeline,
    "bpmn-to-be": bpmn_to_be,
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
    missing = [i for i in ids if i not in FIGURES]
    unused = [i for i in FIGURES if i not in ids]
    print(f"маркеров {len(ids)}, схем {len(FIGURES)}")
    if missing:
        print("ВНИМАНИЕ: маркеры без схемы: " + ", ".join(missing))
    if unused:
        print("ВНИМАНИЕ: схемы без маркера в тексте: " + ", ".join(unused))
    for w in cw.WARNINGS:
        print("ВНИМАНИЕ: " + w)
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
