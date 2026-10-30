"""Aidy の顔を Python（Pillow）で直接描く。Chromium を使わないので軽い。

  r = Renderer()
  r.command("face happy")       表情を変える（名前は FACES）
  r.command("look -60 0")       その方向を見る（x: -60〜60, y: -30〜30）
  r.command("level 0.7")        声の大きさ（聞いている・話している間、目が少し動く）
  r.command("screen weather")   ウィジェット（weather / news）。screen face で顔に戻る
  r.command('card {...}')       ウィジェットに出す中身（JSON。lookup.py の card）
  r.command("charge on")        右上に充電中のアイコン（表情は変えない）
  img = r.frame(t)              その時刻の画面（PIL の RGB 480x320）。変化がなければ None

表情のもとはチームの aidy_screen_test.html（目の大きさ・位置・まぶたの角度）。
10/26 に残す・作り直すものを決めた：
  残す   normal ふつう / sad かなしい / angry おこる / sleepy ねむい / sleep ねてる / dazed ぼーっと / lowbatt 電池少ない
  作り直し happy（下まぶたで平らに細める）/ scared（汗なし）/ listen（動きを小さく・目は水色のまま）
  表情にしないもの thinking（左上のくるくる）/ charging（右上のアイコン）。顔はそのまま
  ほか    speak（話している。ふつうの目が声に合わせて少し動く）
"""
import json
import math
import os
import random
import subprocess
import time
from datetime import datetime

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 480, 320
SS = 2                                   # なめらかに描くため、2倍の大きさで描いて縮める
EYE_X = (146, 334)                       # 左目・右目の中心（画面の左右）
EYE_Y = 160
EYE_COLOR = (255, 255, 255)
GLOW_COLOR = (90, 200, 255)
CYAN = (127, 224, 255)                   # tint で混ぜる水色（チームの版と同じ）
BG = (0, 0, 0)

BASE = dict(w=50, h=140, dx=0, dy=0, top=0.0, tilt=0.0, smile=0.0, low=0.0, dim=0.0, tint=0.0, glow=0.0)   # glow：目の周りのぼかし（10/26 になしに）

# 表情ごとの目の形（BASE からの変更）と動き（fx）
FACES = {
    "normal":   dict(label="ふつう"),
    "happy":    dict(label="うれしい", p=dict(w=54, h=100, dy=-10, low=0.32), fx="bounce"),   # 下まぶたで平らに細める（10/26 に B 案に決定）
    "sad":      dict(label="かなしい", p=dict(h=112, dy=16, top=0.2, tilt=-18)),
    "angry":    dict(label="おこる", p=dict(w=56, h=122, dy=4, top=0.3, tilt=24)),
    "sleepy":   dict(label="ねむい", p=dict(h=74, dy=22, top=0.38), blink=(3.0, 6.0)),
    "sleep":    dict(label="ねてる", p=dict(w=64, h=9, dy=22), fx="zz", blink=None),
    "dazed":    dict(label="ぼーっと", p=dict(w=34, h=36, dy=12), fx="drift"),
    "lowbatt":  dict(label="電池少ない", p=dict(w=44, h=100, dy=-26, top=0.2, tilt=-14, dim=0.4), fx="battlow"),
    "scared":   dict(label="おびえる", p=dict(w=40, h=104, dy=8, top=0.1, tilt=-16), fx="shake"),
    "listen":   dict(label="聞いている", p=dict(w=54, h=146, tint=0.85), fx="level"),
    "speak":    dict(label="話している", p=dict(w=52, h=124), fx="level"),
}
# 前の名前や使わなくなった表情は近いものにする（ほかのプログラムから来ても止まらないように）
ALIAS = {"love": "happy", "laugh": "happy", "smug": "happy", "wink": "happy", "surprised": "normal",
         "confused": "normal", "tilt": "normal", "noticed": "normal", "lookaround": "normal", "waiting": "normal",
         "wake": "normal", "stare": "normal", "nod": "normal", "doubletake": "normal", "lookup": "normal",
         "lookdown": "normal", "cry": "sad", "shy": "normal", "suspicious": "angry", "dizzy": "dazed",
         "charging": "normal", "error": "sad", "attach": "normal", "fall": "scared", "hot": "dazed", "cold": "scared"}
BLINK_DEFAULT = (5.0, 11.0)              # 瞬きの間隔（秒）。チームの版（2.5〜6秒）より少なく


def find_font():
    cands = ["/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
             "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc", "/usr/share/fonts/opentype/ipafont-gothic/ipag.ttf",
             "/usr/share/fonts/truetype/fonts-japanese-gothic.ttf", "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf"]
    for c in cands:
        if os.path.exists(c):
            return c
    try:   # 日本語の文字を持っているフォントだけを探す（fc-match は持っていなくても何かを返すので使わない）
        out = subprocess.run(["fc-list", ":lang=ja", "file"], capture_output=True, text=True, timeout=5).stdout
        files = sorted({ln.split(":")[0].strip() for ln in out.splitlines() if ln.strip()},
                       key=lambda f: (("Sans" not in f and "gothic" not in f.lower()), f))
        for f in files:
            if os.path.exists(f):
                return f
    except Exception:
        pass
    return None


FONT_PATH = find_font()
_fonts = {}


def font(size):
    if size not in _fonts:
        _fonts[size] = ImageFont.truetype(FONT_PATH, size) if FONT_PATH else ImageFont.load_default()
    return _fonts[size]


def lerp(a, b, k):
    return a + (b - a) * k


# ====================================================================== 目
def draw_eye(p, side, extra=None):
    """1つの目を 2 倍の大きさで描き、縮めた白黒の画像（L）と、貼る位置を返す"""
    w, h = max(2.0, p["w"]), max(2.0, p["h"])
    cw, ch = int(w + 40), int(h + 40)                       # 余白つきの小さな画像に描く
    img = Image.new("L", (cw * SS, ch * SS), 0)
    d = ImageDraw.Draw(img)
    cx, cy = cw * SS / 2, ch * SS / 2
    x0, y0, x1, y1 = cx - w * SS / 2, cy - h * SS / 2, cx + w * SS / 2, cy + h * SS / 2
    r = min(w, h) * SS / 2
    d.rounded_rectangle([x0, y0, x1, y1], radius=r, fill=255)
    # まぶた（上を斜めに切る）。tilt>0 は内側が下がる（おこる）、<0 は外側が下がる（かなしい）
    if p["top"] > 0.001 or abs(p["tilt"]) > 0.5:
        base = y0 + p["top"] * h * SS
        k = math.tan(math.radians(p["tilt"])) * (1 if side == 0 else -1)   # 左目は右が内側
        yl = base + k * (x0 - 4 - cx)
        yr = base + k * (x1 + 4 - cx)
        d.polygon([(x0 - 8, -10), (x1 + 8, -10), (x1 + 8, yr), (x0 - 8, yl)], fill=0)
    # 下まぶた：目の下を平らに持ち上げる
    if p["low"] > 0.01:
        d.rectangle([x0 - 8, y1 - p["low"] * h * SS, x1 + 8, y1 + 10], fill=0)
    # うれしい：目の下を丸く持ち上げる
    if p["smile"] > 0.01:
        R = w * SS * 0.95
        lift = p["smile"] * h * SS
        d.ellipse([cx - R * 1.25, y1 - lift, cx + R * 1.25, y1 - lift + 2 * R], fill=0)
    small = img.resize((cw, ch), Image.BILINEAR)
    return small, (cw, ch)


def draw_spinner(t, size=78):
    """読み込み中のくるくる（考え中）"""
    img = Image.new("L", (size * SS, size * SS), 0)
    d = ImageDraw.Draw(img)
    a0 = (t * 330) % 360
    m, lw = max(2, size // 10) * SS, max(3, size // 7) * SS
    d.arc([m, m, size * SS - m, size * SS - m], a0, a0 + 250, fill=255, width=lw)
    d.arc([m, m, size * SS - m, size * SS - m], a0 + 270, a0 + 300, fill=110, width=lw)
    return img.resize((size, size), Image.BILINEAR)


def draw_battery(d, cx, cy, color, level, scale=1.0, bolt=False):
    s = scale
    w, h = 74 * s, 38 * s
    x0, y0 = cx - w / 2, cy - h / 2
    d.rounded_rectangle([x0, y0, x0 + w, y0 + h], radius=8 * s, outline=color, width=max(2, int(4 * s)))
    d.rounded_rectangle([x0 + w + 2 * s, cy - 8 * s, x0 + w + 7 * s, cy + 8 * s], radius=2 * s, fill=color)
    bars = max(0, min(3, int(level)))
    for i in range(bars):
        bx = x0 + 7 * s + i * 21 * s
        d.rounded_rectangle([bx, y0 + 7 * s, bx + 17 * s, y0 + h - 7 * s], radius=3 * s, fill=color)
    if bolt:
        pts = [(cx + 4 * s, y0 + 4 * s), (cx - 10 * s, cy + 3 * s), (cx - 1 * s, cy + 3 * s),
               (cx - 5 * s, y0 + h - 4 * s), (cx + 10 * s, cy - 3 * s), (cx + 1 * s, cy - 3 * s)]
        d.polygon(pts, fill=(255, 220, 80))


def draw_z(d, x, y, size, color):
    """Z の字（フォントを使わずに線で描く）"""
    s = size
    d.line([(x, y), (x + s, y), (x, y + s), (x + s, y + s)], fill=color, width=max(2, int(s / 6)), joint="curve")


# ====================================================================== 天気・ニュースの絵
def wx_icon(d, kind, cx, cy, r, night=False):
    """天気のアイコン（sun / partly / cloud / rain / snow / thunder / fog）"""
    sun_c, cloud_c, rain_c = (255, 196, 61), (210, 220, 235), (110, 190, 255)

    def cloud(x, y, s, col=cloud_c):
        d.ellipse([x - 0.95 * s, y - 0.15 * s, x - 0.15 * s, y + 0.55 * s], fill=col)
        d.ellipse([x - 0.55 * s, y - 0.6 * s, x + 0.35 * s, y + 0.35 * s], fill=col)
        d.ellipse([x + 0.05 * s, y - 0.35 * s, x + 0.9 * s, y + 0.5 * s], fill=col)
        d.rounded_rectangle([x - 0.95 * s, y + 0.05 * s, x + 0.9 * s, y + 0.55 * s], radius=0.25 * s, fill=col)

    def sun(x, y, s):
        if night:
            d.ellipse([x - s * 0.55, y - s * 0.55, x + s * 0.55, y + s * 0.55], fill=(250, 230, 150))
            d.ellipse([x - s * 0.25, y - s * 0.7, x + s * 0.75, y + s * 0.35], fill=BG)
            return
        for i in range(8):
            a = math.radians(i * 45)
            d.line([(x + math.cos(a) * s * 0.72, y + math.sin(a) * s * 0.72),
                    (x + math.cos(a) * s * 0.98, y + math.sin(a) * s * 0.98)], fill=sun_c, width=max(2, int(s * 0.12)))
        d.ellipse([x - s * 0.52, y - s * 0.52, x + s * 0.52, y + s * 0.52], fill=sun_c)

    if kind == "sun":
        sun(cx, cy, r)
    elif kind == "partly":
        sun(cx - r * 0.3, cy - r * 0.3, r * 0.7)
        cloud(cx + r * 0.15, cy + r * 0.15, r * 0.75)
    elif kind == "cloud":
        cloud(cx - r * 0.25, cy - r * 0.25, r * 0.55, (150, 160, 175))
        cloud(cx + r * 0.1, cy + r * 0.05, r * 0.8)
    elif kind in ("rain", "snow", "thunder"):
        cloud(cx, cy - r * 0.3, r * 0.8)
        if kind == "rain":
            for i in (-1, 0, 1):
                x = cx + i * r * 0.42
                d.line([(x, cy + r * 0.38), (x - r * 0.12, cy + r * 0.78)], fill=rain_c, width=max(2, int(r * 0.12)))
        elif kind == "snow":
            for i in (-1, 0, 1):
                x, y = cx + i * r * 0.42, cy + r * 0.6
                d.ellipse([x - r * 0.1, y - r * 0.1, x + r * 0.1, y + r * 0.1], fill=(240, 248, 255))
        else:
            pts = [(cx + r * 0.05, cy + r * 0.2), (cx - r * 0.25, cy + r * 0.62), (cx - r * 0.02, cy + r * 0.62),
                   (cx - r * 0.15, cy + r * 0.98), (cx + r * 0.28, cy + r * 0.48), (cx + r * 0.04, cy + r * 0.48)]
            d.polygon(pts, fill=sun_c)
    else:                                                     # fog
        for i, k in enumerate((-0.45, -0.1, 0.25, 0.6)):
            o = r * (0.15 if i % 2 else 0)
            d.rounded_rectangle([cx - r * 0.8 + o, cy + k * r, cx + r * 0.8 - o, cy + k * r + r * 0.16],
                                radius=r * 0.08, fill=(180, 190, 205))


def text_fit(d, s, size, width):
    """幅に収まるように後ろを「…」にする"""
    f = font(size)
    if d.textlength(s, font=f) <= width:
        return s
    while s and d.textlength(s + "…", font=f) > width:
        s = s[:-1]
    return s + "…"


def wrap(d, s, size, width, max_lines):
    f, lines, cur = font(size), [], ""
    for ch in s:
        if d.textlength(cur + ch, font=f) > width:
            lines.append(cur)
            cur = ch
            if len(lines) == max_lines:
                break
        else:
            cur += ch
    else:
        if cur:
            lines.append(cur)
    if len(lines) == max_lines and "".join(lines) != s:
        lines[-1] = text_fit(d, lines[-1] + "…", size, width) if not lines[-1].endswith("…") else lines[-1]
    return lines[:max_lines]


GRAY, LIGHT, ACCENT = (140, 148, 162), (235, 240, 248), (90, 200, 255)


def weather_face(card):
    """天気のウィジェットの右上に出す小さな顔の表情"""
    now, days = card.get("now") or {}, card.get("days") or [{}]
    icon, t0 = now.get("icon", ""), days[0]
    if icon == "thunder":
        return "scared"
    if (t0.get("max") or 0) >= 30:
        return "dazed"
    if icon == "snow":
        return "happy"
    if icon == "rain" or (t0.get("pop") or 0) >= 60:
        return "sad"
    if not now.get("is_day", True):
        return "sleepy"
    if icon == "sun":
        return "happy"
    return "normal"


def draw_weather(card):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    now = card.get("now") or {}
    days = card.get("days") or []
    title = f"{card.get('place', '')}の天気"
    d.text((18, 12), title, font=font(20), fill=LIGHT)
    d.text((26 + d.textlength(title, font=font(20)), 16), card.get("time", datetime.now().strftime("%H:%M")),
           font=font(16), fill=GRAY)                                  # 右上は小さな顔を出すので、時刻は見出しの横に
    # いま
    wx_icon(d, now.get("icon", "cloud"), 92, 112, 58, night=not now.get("is_day", True))
    if "temp" in now:
        d.text((178, 70), f"{round(now['temp'])}°", font=font(64), fill=LIGHT)
    d.text((182, 150), text_fit(d, now.get("text", ""), 20, 140), font=font(20), fill=LIGHT)
    if days:
        t0 = days[0]
        d.text((344, 98), f"最高 {round(t0['max'])}°", font=font(20), fill=(255, 150, 120))
        d.text((344, 124), f"最低 {round(t0['min'])}°", font=font(20), fill=(130, 190, 255))
        if t0.get("pop") is not None:
            d.text((344, 150), f"雨 {t0['pop']}%", font=font(20), fill=LIGHT)
    # この先
    d.line([(16, 192), (W - 16, 192)], fill=(40, 46, 56), width=2)
    cols = days[:3]
    cw = (W - 32) / max(1, len(cols))
    for i, x in enumerate(cols):
        x0 = 16 + i * cw
        mid = x0 + cw / 2
        d.text((mid, 202), x.get("label", ""), font=font(17), fill=GRAY, anchor="ma")
        wx_icon(d, x.get("icon", "cloud"), mid - 38, 256, 22)
        d.text((mid - 8, 236), f"{round(x['max'])}° / {round(x['min'])}°", font=font(18), fill=LIGHT)
        if x.get("pop") is not None:
            d.text((mid - 8, 262), f"雨 {x['pop']}%", font=font(16), fill=ACCENT)
    d.text((W - 16, H - 8), card.get("source", ""), font=font(12), fill=(90, 96, 108), anchor="rd")
    return img


WEATHER_REACT_SEC = 2.5    # 天気を出した直後に、天気に合わせた表情（雨→かなしい など）をする秒数
NEWS_PAGE_SEC = 10         # ニュースが1画面に収まらないとき、1ページを出しておく最短の秒数（6秒は速すぎた。10/27）
NEWS_READ_CPS = 7          # 読む速さ（1秒に何文字）。文字の多いページはそのぶん長く出す


def wrap_words(d, s, size, width, max_lines):
    """見出し用の折り返し。区切りの空白があれば、そこで折り返す（「顧客端末預|かり」にならないように）"""
    f, lines, rest = font(size), [], s.strip()
    while rest and len(lines) < max_lines:
        if d.textlength(rest, font=f) <= width:
            lines.append(rest)
            rest = ""
            break
        n = 1
        while n < len(rest) and d.textlength(rest[:n + 1], font=f) <= width:
            n += 1
        cut = rest.rfind(" ", 0, n + 1)
        if cut < n * 0.55:
            cut = n
        lines.append(rest[:cut].rstrip())
        rest = rest[cut:].lstrip()
    if rest and lines:
        lines[-1] = text_fit(d, lines[-1] + "…", size, width)
    return lines


def news_pages(card):
    """ニュースのウィジェット。見出しは切らずに全部出す（10/27）。入りきらなければ数ページに分けて、順番に替える"""
    items = (card.get("items") or [])[:5]
    probe = ImageDraw.Draw(Image.new("RGB", (W, H)))
    size, lh, top, bottom = 21, 29, 50, H - 22
    blocks = [wrap_words(probe, s, size, W - 56, 3) for s in items]
    pages, cur, y = [], [], top
    for i, lines in enumerate(blocks):
        need = len(lines) * lh + 12
        if cur and y + need > bottom:
            pages.append(cur)
            cur, y = [], top
        cur.append((i, lines))
        y += need
    if cur:
        pages.append(cur)
    out = []
    for pi, page in enumerate(pages or [[]]):
        img = Image.new("RGB", (W, H), BG)
        d = ImageDraw.Draw(img)
        d.text((18, 12), "ニュース", font=font(20), fill=LIGHT)
        d.text((W - 18, 14), f"{card.get('source', '')}  {card.get('time', '')}", font=font(16), fill=GRAY, anchor="ra")
        y = top
        for k, (i, lines) in enumerate(page):
            d.ellipse([20, y + 10, 28, y + 18], fill=ACCENT if i == 0 else GRAY)
            for ln in lines:
                d.text((38, y), ln, font=font(size), fill=LIGHT if i == 0 else (215, 221, 232))
                y += lh
            y += 12
            if k < len(page) - 1:
                d.line([(38, y - 7), (W - 18, y - 7)], fill=(32, 37, 46), width=1)
        if len(pages) > 1:                                   # ページの印（●○）
            for n in range(len(pages)):
                cx = W // 2 + (n - (len(pages) - 1) / 2) * 16
                d.ellipse([cx - 4, H - 15, cx + 4, H - 7], fill=ACCENT if n == pi else (60, 66, 78))
        img.info["sec"] = max(NEWS_PAGE_SEC, sum(len(ln) for _, lines in page for ln in lines) / NEWS_READ_CPS)
        out.append(img)
    return out


def draw_news(card):
    return news_pages(card)[0]


def draw_todo(card):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    items = card.get("items") or []
    d.text((18, 12), "やることリスト", font=font(20), fill=LIGHT)
    d.text((W - 18, 16), f"{len(items)}件", font=font(16), fill=GRAY, anchor="ra")
    if not items:
        d.text((W // 2, H // 2), "空っぽだよ", font=font(22), fill=GRAY, anchor="mm")
        return img
    y, row_h, shown = 50, 42, items[:6]
    for it in shown:
        hl = it.get("hl")
        if hl:
            d.rounded_rectangle([12, y - 4, W - 12, y + row_h - 8], radius=10, fill=(20, 52, 66))
        d.rounded_rectangle([22, y + 5, 42, y + 25], radius=4, outline=ACCENT if hl else GRAY, width=2)
        due = it.get("due", "")
        dw = d.textlength(due, font=font(16)) + 10 if due else 0
        d.text((54, y + 2), text_fit(d, it["text"], 20, W - 76 - dw), font=font(20), fill=LIGHT)
        if due:
            d.text((W - 22, y + 6), due, font=font(16), fill=ACCENT if hl else (255, 196, 61), anchor="ra")
        y += row_h
    if len(items) > len(shown):
        d.text((W // 2, H - 14), f"ほかに {len(items) - len(shown)} 件", font=font(14), fill=GRAY, anchor="mm")
    return img


def _fmt_left(sec):
    sec = max(0, int(math.ceil(sec)))
    h, r = divmod(sec, 3600)
    m, x = divmod(r, 60)
    return f"{h}:{m:02d}:{x:02d}" if h else f"{m}:{x:02d}"


def draw_bell(d, cx, cy, r, color):
    """ベル（フォントを使わずに描く）"""
    d.pieslice([cx - r, cy - r * 1.1, cx + r, cy + r * 0.9], 180, 360, fill=color)
    d.rectangle([cx - r, cy - r * 0.1, cx + r, cy + r * 0.55], fill=color)
    d.polygon([(cx - r * 1.25, cy + r * 0.75), (cx + r * 1.25, cy + r * 0.75), (cx + r, cy + r * 0.45), (cx - r, cy + r * 0.45)], fill=color)
    d.ellipse([cx - r * 0.28, cy + r * 0.72, cx + r * 0.28, cy + r * 1.15], fill=color)
    d.ellipse([cx - r * 0.18, cy - r * 1.32, cx + r * 0.18, cy - r * 0.98], fill=color)


def draw_timer(card, now=None):
    """タイマー・アラーム。鳴っている間は画面いっぱいに知らせる（1秒ごとに描き直す）"""
    now = now or time.time()
    for it in card.get("items") or []:                                  # 見本（face/sample_cards.json）は、いまからの時間にする
        if it.get("due", 0) < 0:
            if "left" in it:
                it["due"] = now + it["left"]
            else:
                hh, mm = (int(v) for v in it.get("at", "7:00").split(":"))
                t = datetime.now().replace(hour=hh, minute=mm, second=0, microsecond=0)
                it["due"] = (t.timestamp() + (86400 if it.get("tomorrow") else 0))
    ring = card.get("ringing")
    if ring:
        on = int((now - ring.get("since", now)) * 2) % 2 == 0          # 0.5秒ごとに点滅
        bg = (255, 196, 61) if on else (40, 30, 8)
        fg = (20, 16, 4) if on else (255, 196, 61)
        img = Image.new("RGB", (W, H), bg)
        d = ImageDraw.Draw(img)
        sway = math.sin(now * 14) * 6
        draw_bell(d, W // 2 + sway, 78, 40, fg)
        d.text((W // 2, 168), "時間だよ！" if ring.get("kind") == "timer" else ring.get("clock", "アラーム"),
               font=font(48), fill=fg, anchor="mm")
        d.text((W // 2, 222), text_fit(d, ring.get("label", ""), 24, W - 40), font=font(24), fill=fg, anchor="mm")
        d.text((W // 2, 286), "「ストップ」と言うか、画面にタッチで止まるよ", font=font(17), fill=fg, anchor="mm")
        return img
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    items = card.get("items") or []
    d.text((18, 12), "タイマー・アラーム", font=font(20), fill=LIGHT)
    d.text((W - 18, 16), datetime.now().strftime("%H:%M"), font=font(16), fill=GRAY, anchor="ra")
    if not items:
        d.text((W // 2, H // 2), "セットしていないよ", font=font(22), fill=GRAY, anchor="mm")
        return img
    top = items[0]
    if top["kind"] == "timer":                                          # いちばん早いタイマーを大きく
        left = top["due"] - now
        frac = max(0.0, min(1.0, left / max(1, top.get("total", left))))
        cx, cy, r = 112, 150, 78
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(40, 46, 56), width=12)
        if frac > 0:
            d.arc([cx - r, cy - r, cx + r, cy + r], -90, -90 + 360 * frac, fill=ACCENT, width=12)
        d.text((cx, cy), _fmt_left(left), font=font(40 if left < 3600 else 30), fill=LIGHT, anchor="mm")
        d.text((214, 104), text_fit(d, top["label"], 24, W - 230), font=font(24), fill=LIGHT)
        end = datetime.fromtimestamp(top["due"])
        d.text((214, 140), f"{end.hour}:{end.minute:02d} に鳴るよ", font=font(20), fill=GRAY)
        rest, y, x0 = items[1:4], 182, 214
    else:
        rest, y, x0 = items[:5], 52, 12
    for it in rest:
        if it["kind"] == "timer":
            right, label = _fmt_left(it["due"] - now), it["label"]
        else:
            t = datetime.fromtimestamp(it["due"])
            dd = (t.date() - datetime.now().date()).days
            right = ("明日 " if dd == 1 else "" if dd == 0 else f"{t.month}/{t.day} ") + f"{t.hour}:{t.minute:02d}"
            label = it["label"]
        col = (255, 196, 61) if it["kind"] == "alarm" else ACCENT
        if it.get("hl"):
            d.rounded_rectangle([x0, y - 4, W - 12, y + 32], radius=10, fill=(20, 52, 66))
        rw = d.textlength(right, font=font(19))
        draw_bell(d, x0 + 18, y + 13, 8, col)
        d.text((x0 + 36, y + 3), text_fit(d, label, 18, W - x0 - 66 - rw), font=font(18), fill=LIGHT)
        d.text((W - 22, y + 2), right, font=font(19), fill=col, anchor="ra")
        y += 40
    hidden = len(items) - len(rest) - (1 if top["kind"] == "timer" else 0)
    if hidden > 0:
        d.text((W // 2, H - 12), f"ほかに {hidden} 件", font=font(14), fill=GRAY, anchor="mm")
    return img


def draw_calendar(card):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    rows = card.get("rows") or []
    d.text((18, 12), text_fit(d, card.get("title", "予定"), 20, W - 140), font=font(20), fill=LIGHT)
    d.text((W - 18, 16), "Google カレンダー", font=font(14), fill=GRAY, anchor="ra")
    if not rows:
        d.text((W // 2, H // 2), "予定は入っていないよ", font=font(22), fill=GRAY, anchor="mm")
        return img
    y, shown = 50, 0
    for r in rows:
        if y > H - 40:
            break
        if r.get("head"):
            d.text((18, y + 4), r["head"], font=font(16), fill=(255, 196, 61))
            d.line([(84 + d.textlength(r["head"], font=font(16)) - 60, y + 14), (W - 18, y + 14)], fill=(40, 46, 56), width=1)
            y += 28
            continue
        if r.get("hl"):
            d.rounded_rectangle([12, y - 4, W - 12, y + 34], radius=10, fill=(20, 52, 66))
        d.text((24, y + 3), r["time"], font=font(18), fill=ACCENT)
        d.text((96, y + 1), text_fit(d, r["title"], 21, W - 116), font=font(21), fill=LIGHT)
        y += 40
        shown += 1
    total = sum(1 for r in rows if not r.get("head"))
    if total > shown:
        d.text((W // 2, H - 12), f"ほかに {total - shown} 件", font=font(14), fill=GRAY, anchor="mm")
    return img


def draw_timer_badge(d, x, y, badge, now):
    """顔の右上：いちばん早いタイマーの残り時間（タイマーがなければ次のアラームの時刻）"""
    tm = [b for b in badge if b["kind"] == "timer"]
    if tm:
        b = min(tm, key=lambda v: v["due"])
        txt, col = _fmt_left(b["due"] - now), ACCENT
    else:
        b = min(badge, key=lambda v: v["due"])
        t = datetime.fromtimestamp(b["due"])
        txt, col = f"{t.hour}:{t.minute:02d}", (255, 196, 61)
    f = font(18)
    w = d.textlength(txt, font=f)
    d.rounded_rectangle([x - w - 38, y - 15, x, y + 15], radius=15, outline=col, width=2)
    draw_bell(d, x - w - 22, y - 1, 7, col)
    d.text((x - 10, y), txt, font=f, fill=col, anchor="rm")


# ====================================================================== 全体
class Renderer:
    def __init__(self, blink=None):
        self.blink_range = blink or BLINK_DEFAULT
        self.name = "normal"
        self.cur = dict(BASE)
        self.target = dict(BASE)
        self.look_x = self.look_y = 0.0        # 指示された目線
        self.gx = self.gy = 0.0                # なめらかにした目線
        self.look_t = 0.0                      # 最後に目線の指示が来た時刻
        self.glance = (0.0, 0.0, 0.0)          # 自分できょろっと見る（x, y, 終わる時刻）
        self.next_glance = time.time() + 6
        self.level = 0.0
        self.lv = 0.0
        self.screen = "face"
        self.cards = {}
        self.widget_img = None
        self.charging = False
        self.busy = False                      # 考え中（左上のくるくる）
        self.badge = []                        # タイマー・アラームの小さな表示（右上）
        self.t_face = time.time()
        self.next_blink = time.time() + 3
        self.blink_t = -1.0
        self.double = False
        self.last_sig = None
        self.last_wsig = None
        self.face_img = None
        self.face_before = "normal"
        self.react_until = 0.0

    # ---------------------------------------------------------------- 指示
    def command(self, line):
        w = line.split(maxsplit=1)
        if not w:
            return
        c, arg = w[0].lower(), (w[1] if len(w) > 1 else "")
        if c == "face" or c in FACES or c in ALIAS:
            self.set_face(arg if c == "face" else c)
        elif c == "look":
            v = arg.split()
            self.look_x = float(v[0])
            self.look_y = float(v[1]) if len(v) > 1 else 0.0
            self.look_t = time.time()
        elif c == "level":
            self.level = max(0.0, min(1.0, float(arg or 0)))
        elif c == "screen":
            self.set_screen(arg.strip() or "face")
        elif c == "card":
            card = json.loads(arg)
            self.cards[card.get("kind", "weather")] = card
            if self.screen == card.get("kind"):
                self.set_screen(self.screen)
        elif c == "charge":
            self.charging = arg.strip() in ("on", "1", "true")
        elif c == "busy":
            self.busy = arg.strip() in ("on", "1", "true")
        elif c == "timers":                    # タイマー・アラームの小さな表示（JSON の配列。[] で消す）
            self.badge = json.loads(arg or "[]")
            self.last_sig = None
        else:
            raise ValueError(f"分からないコマンド: {line}")

    def set_face(self, name):
        name = name.strip()
        if name == "thinking":                 # 考え中は表情を変えず、左上にくるくるを出す（次の表情で消える）
            self.busy = True
            return
        self.busy = False
        name = ALIAS.get(name, name)
        if name not in FACES:
            raise ValueError(f"表情 {name} はありません")
        if self.screen != "face":              # ウィジェットを出している間は画面はそのまま（戻すのは screen face）
            self.face_before = name
            if self.screen == "weather" and time.time() >= self.react_until:
                self._set_shape(name)          # 天気の小さな顔は、ふだんの顔と同じ表情をする（話す・聞く・笑う）
            return
        self._set_shape(name)

    def _set_shape(self, name):
        if name != self.name:
            self.name, self.t_face = name, time.time()
            self.target = dict(BASE, **FACES[name].get("p", {}))

    def set_screen(self, name):
        if name == "face":
            if self.screen != "face":
                self.screen = "face"
                self._set_shape(self.face_before)
                self.last_sig = None
            return
        card = self.cards.get(name)
        if not card:
            raise ValueError(f"{name} の中身（card）がまだ来ていません")
        self.busy = False
        if name == "news":
            self.pages, self.page_i, self.page_t = news_pages(card), 0, time.time()
            self.widget_img = self.pages[0]
        else:
            self.pages = None
            self.widget_img = {"weather": draw_weather, "todo": draw_todo, "timer": draw_timer,
                               "calendar": draw_calendar}[name](card)
        if self.screen == "face":
            self.face_before = self.name
        self.screen = name
        if name == "weather":                  # 出した直後だけ天気に合わせた表情をして、あとはふだんの顔に戻す（10/27）
            self._set_shape(weather_face(card))
            self.react_until = time.time() + WEATHER_REACT_SEC
        self.last_wsig = None

    # ---------------------------------------------------------------- 描く
    def _blink_k(self, now):
        rng = FACES[self.name].get("blink", self.blink_range)
        if rng is None:
            return 1.0
        if self.blink_t < 0 and now >= self.next_blink:
            self.blink_t = now
            self.double = random.random() < 0.15
        if self.blink_t >= 0:
            x = (now - self.blink_t) / 0.16
            if x >= 1:
                if self.double:
                    self.double, self.blink_t = False, now + 0.12
                else:
                    self.blink_t = -1.0
                    self.next_blink = now + random.uniform(*rng)
                return 1.0
            if x < 0:
                return 1.0
            return max(0.08, abs(1 - 2 * x))
        return 1.0

    def frame(self, now=None, force=False):
        """いまの画面を返す。前の画面と変わらなければ None（LCD に送らずに済む）"""
        now = now or time.time()
        if self.screen == "face":
            changed = self._face_frame(now)
            if not changed and not force:
                return None
            return self.face_img
        # ウィジェット。天気のときは右上に小さく目を出す（チームの版と同じ配置）
        if self.screen == "weather" and self.react_until and now >= self.react_until:
            self.react_until = 0.0
            self._set_shape(self.face_before)
        face_changed = self._face_frame(now) if self.screen == "weather" else False
        if getattr(self, "pages", None) and len(self.pages) > 1 and \
                now - self.page_t >= self.pages[self.page_i].info.get("sec", NEWS_PAGE_SEC):
            self.page_i, self.page_t = (self.page_i + 1) % len(self.pages), now
            self.widget_img = self.pages[self.page_i]
        if self.screen == "timer":            # タイマーは残り時間・点滅があるので、0.5秒ごとに描き直す
            tick = int(now * 2)
            if tick != getattr(self, "timer_tick", None):
                self.timer_tick = tick
                self.widget_img = draw_timer(self.cards["timer"], now)
        sig = ("w", id(self.widget_img), self.charging)
        if sig == self.last_wsig and not face_changed and not force:
            return None
        self.last_wsig = sig
        img = self.widget_img.copy()
        if self.screen == "weather" and self.face_img is not None:
            img.paste(self.face_img.resize((120, 80), Image.LANCZOS), (344, 8))
        if self.charging:
            draw_battery(ImageDraw.Draw(img), W - 46, 22, (91, 227, 160), 2, 0.5, bolt=True)
        return img

    def _face_frame(self, now):
        """顔を描いて self.face_img に入れる。前と変わらなければ False"""
        fx = FACES[self.name].get("fx")
        # 形をなめらかに目標へ近づける
        moving = False
        for k, v in self.target.items():
            c = self.cur[k]
            nv = lerp(c, v, 0.35)
            if abs(nv - v) < 0.3 if k in ("w", "h", "dx", "dy", "tilt") else abs(nv - v) < 0.01:
                nv = v
            moving |= nv != c
            self.cur[k] = nv
        p = dict(self.cur)
        # 目線：指示があればそれ、なければときどき自分で少しだけ見る
        if now - self.look_t > 3 and (self.name == "normal" or self.screen == "weather"):   # 天気の小さな顔もきょろきょろ
            if now > self.next_glance:
                self.glance = (random.choice([-16, 16, 0]), 0.0, now + 1.2)
                self.next_glance = now + random.uniform(5, 10)
            tx, ty = (self.glance[0], self.glance[1]) if now < self.glance[2] else (0.0, 0.0)
        elif now - self.look_t > 3:
            tx = ty = 0.0
        else:
            tx, ty = self.look_x, self.look_y
        ngx, ngy = lerp(self.gx, tx, 0.3), lerp(self.gy, ty, 0.3)
        if abs(ngx - tx) < 0.5:
            ngx = tx
        if abs(ngy - ty) < 0.5:
            ngy = ty
        moving |= (round(ngx), round(ngy)) != (round(self.gx), round(self.gy))
        self.gx, self.gy = ngx, ngy
        self.lv = lerp(self.lv, self.level, 0.5)

        # 動き
        ox = oy = 0.0
        tf = now - self.t_face
        if fx == "shake":
            ox, oy = random.uniform(-2, 2), random.uniform(-1.5, 1.5)
        elif fx == "drift":
            ox, oy = math.sin(now * 0.7) * 12, math.sin(now * 0.45) * 6
        elif fx == "bounce" and tf < 0.6:
            oy = -math.sin(tf / 0.6 * math.pi) * 8
        elif fx == "bounce2" and tf < 1.0:                          # ぴょんぴょんと2回
            oy = -abs(math.sin(tf / 0.5 * math.pi)) * 9
        if self.screen == "weather":                                # 天気の小さな顔：話している間は声に合わせて弾む（10/27）
            p["h"] *= 1 + 0.15 * self.lv
            oy -= 6 * self.lv
        elif fx == "level":
            p["h"] *= 1 + 0.06 * self.lv                            # 声に合わせて少しだけ（チームの版より控えめ）
        bk = self._blink_k(now)
        p["h"] *= bk

        sig = (self.name, round(p["w"], 1), round(p["h"], 1), round(p["dy"] + oy, 1), round(ox + self.gx, 1),
               round(self.gy, 1), round(p["top"], 3), round(p["tilt"], 1), round(p["smile"], 3), round(p["low"], 3),
               round(p["tint"], 2), round(p["glow"], 2), self.charging, self.busy,
               int(now * 12) if fx in ("zz", "battlow") or self.busy else 0,
               int(now) if self.badge else 0, len(self.badge))
        if sig == self.last_sig and self.face_img is not None:
            return False
        self.last_sig = sig

        img = Image.new("RGB", (W, H), BG)
        mask = Image.new("L", (W, H), 0)
        bx, by = ox + self.gx + p["dx"], oy + self.gy * 0.8 + p["dy"]
        for side, ex in enumerate(EYE_X):
            eye, (cw, ch) = draw_eye(p, side)
            mask.paste(eye, (int(ex + bx - cw / 2), int(EYE_Y + by - ch / 2)), eye)
        # ぼんやり光らせる（小さくしてぼかし、戻す）
        g = p["glow"]
        if g > 0.01:
            glow = mask.resize((W // 4, H // 4), Image.BILINEAR).filter(ImageFilter.GaussianBlur(3)).resize((W, H), Image.BILINEAR)
            img.paste(GLOW_COLOR, (0, 0), glow.point(lambda v: min(255, int(v * g))))
        col = tuple(int((a + (b - a) * p["tint"]) * (1 - p["dim"])) for a, b in zip(EYE_COLOR, CYAN))
        img.paste(col, (0, 0), mask)
        d = ImageDraw.Draw(img)
        if fx == "zz":
            for i in range(3):
                ph = (now / 2.4 + i / 3) % 1
                c = int(200 * (1 - ph))
                draw_z(d, 372 + i * 16 + ph * 10, 118 - i * 22 - ph * 26, 14 + i * 4, (c, c, c))
        if fx == "battlow" and int(now * 1.6) % 2 == 0:
            draw_battery(d, 240, 272, (255, 91, 91), 1, 0.9)
        if self.charging and self.screen == "face":
            draw_battery(d, W - 46, 22, (91, 227, 160), 2, 0.5, bolt=True)
        if self.badge and self.screen == "face":                  # タイマー・アラーム：右上（充電中のアイコンがあればその左）
            draw_timer_badge(d, W - (96 if self.charging else 12), 24, self.badge, now)
        if self.busy:                                              # 考え中：左上のくるくる
            sp = draw_spinner(now, 40)
            img.paste(CYAN, (14, 6), sp)
        self.face_img = img
        return True

    def animating(self):
        return self.screen == "face"


def contact_sheet(path, cards=None):
    """全部の表情とウィジェットを1枚に並べる（確認用）"""
    names = list(FACES)
    r = Renderer()
    tiles = []
    for n in names:
        r.set_face(n)
        for _ in range(25):
            r.frame(time.time(), force=True)
        r.next_blink = time.time() + 99
        r.blink_t = -1
        img = r.frame(time.time() + 0.3, force=True)
        tiles.append((FACES[n]["label"] + f"（{n}）", img))
    r.set_face("normal")
    r.charging = True
    tiles.append(("充電中（右上のアイコン）", r.frame(force=True)))
    r.charging = False
    r.busy = True
    tiles.append(("考え中（左上のくるくる）", r.frame(time.time() + 0.2, force=True)))
    r.busy = False
    for kind, card in (cards or {}).items():
        r.cards[kind] = card
        r.set_screen(kind)
        for _ in range(25):
            r.frame(time.time(), force=True)
        tiles.append(({"weather": "天気ウィジェット", "news": "ニュースウィジェット", "todo": "やることウィジェット",
                       "timer": "タイマーウィジェット", "calendar": "カレンダーウィジェット"}.get(kind, kind),
                      r.frame(force=True)))
    cols = 3
    tw, th = W // 2, H // 2 + 26
    sheet = Image.new("RGB", (cols * (tw + 12) + 12, ((len(tiles) + cols - 1) // cols) * (th + 12) + 12), (24, 26, 32))
    d = ImageDraw.Draw(sheet)
    for i, (label, img) in enumerate(tiles):
        x, y = 12 + (i % cols) * (tw + 12), 12 + (i // cols) * (th + 12)
        sheet.paste(img.resize((tw, H // 2), Image.LANCZOS), (x, y + 26))
        d.text((x, y + 2), label, font=font(16), fill=(220, 225, 235))
    sheet.save(path)
    return path
