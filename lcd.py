#!/usr/bin/env python3
"""
Aidy の顔：表情と天気・ニュースのウィジェットを、Python（faces.py）で直接描いて LCD（ST7796S・横長 480x320）に映す。
Chromium は使わない（10/26 に切り替え。CPU とメモリを LLM に回すため）。

映し先（チームの aidy_lcd.py の方式も残している）
  fb  … /dev/fb*（mipi-dbi-spi ドライバ。1号機はこちら）。縦長 320x480 の画面に、横長の絵を回して書く
  spi … spidev を直接使う（ドライバを入れていない機体。チームの元の方式）
  png … LCD なしで PNG に書く（PC・確認用）
  --out auto（既定）なら、320x480 の fb があれば fb、なければ spi。

  python3 lcd.py                    # 「ふつう」の顔を映す
  python3 lcd.py --face happy
  python3 lcd.py --demo 3           # 全表情・考え中・充電中・ウィジェット（見本）を3秒ごとに順番に映す
  python3 lcd.py --sheet faces.png  # 全表情とウィジェットを1枚の画像にする（LCD は使わない）
  python3 lcd.py --rot 1            # 上下逆さのとき（既定は config.py の LCD_ROT）

動いている間は、次のコマンドで切り替えられる（このターミナルに入力、または別の端末から）。
  happy                 表情の名前だけで切り替え（normal happy sad angry sleepy sleep dazed lowbatt thinking scared listen speak）
  face happy            同じ
  look -60 0            その方向を見る（x, y）
  level 0.7             声の大きさ（0〜1）
  screen weather        ウィジェットにする（weather / news。中身は card で送る）。screen face で顔に戻る
  card {...}            ウィジェットの中身（JSON。brain.py が lookup.py の結果を送る）
  charge on / off       右上に充電中のアイコン
  list / quit
  別の端末から： echo happy > /tmp/aidy_cmd   （brain.py はここに送る）

必要なもの： sudo apt install -y python3-pil python3-numpy fonts-noto-cjk
             spi のときだけ python3-spidev python3-lgpio（配線 LCD_DC=GPIO14、LCD_RST=GPIO15、LCD_CS=CE0）
"""
import argparse, glob, os, queue, sys, threading, time
import numpy as np
from PIL import Image

W, H = 480, 320            # 横向きの画面
FIFO = "/tmp/aidy_cmd"
try:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import config
    FIFO = getattr(config, "FACE_FIFO", FIFO)
except ImportError:
    pass

# ------------------------------------------------------------ fb（ドライバ経由） ----
def find_fb():
    """320x480（または 480x320）の framebuffer を探す。HDMI の fb は大きさが違うので選ばない"""
    for d in sorted(glob.glob("/sys/class/graphics/fb[0-9]*")):
        try:
            size = tuple(int(v) for v in open(d + "/virtual_size").read().strip().split(","))
            bpp = int(open(d + "/bits_per_pixel").read())
        except (OSError, ValueError):
            continue
        if sorted(size) == [320, 480] and bpp == 16:
            return "/dev/" + os.path.basename(d), size, d
    return None

class FbLcd:
    """/dev/fb* に RGB565 で書く。縦長の fb なら、横長の絵を 90 度回して書く（config.txt は変えない）"""
    def __init__(self, a, found):
        self.dev, (self.fw, self.fh), sysd = found
        try:
            self.stride = int(open(sysd + "/stride").read())
        except (OSError, ValueError):
            self.stride = self.fw * 2
        self.k = 0 if self.fw == W else (1 if a.rot == 1 else 3)     # 縦長 fb：rot 1 なら左に、3 なら右に 90 度
        if self.fw == W and a.rot == 3:
            self.k = 2
        self.fd = os.open(self.dev, os.O_RDWR)
        self.prev = None
        print("LCD: %s（%dx%d、%d 度回して書く）" % (self.dev, self.fw, self.fh, self.k * 90))
    def init(self): pass
    def show(self, v, rects):
        p = np.ascontiguousarray(np.rot90(v, self.k)) if self.k else v
        if self.prev is None or rects is None:
            rows = np.arange(p.shape[0])
        else:
            rows = np.where((p != self.prev).any(axis=1))[0]
        self.prev = p
        if rows.size == 0:
            return 0
        y0, y1 = int(rows[0]), int(rows[-1])
        block = p[y0:y1 + 1].astype("<u2")
        if self.stride != self.fw * 2:                               # 行の終わりに余白がある fb
            pad = np.zeros((block.shape[0], self.stride // 2), "<u2"); pad[:, :self.fw] = block; block = pad
        data = block.tobytes()
        os.pwrite(self.fd, data, y0 * self.stride)
        return len(data)
    def close(self):
        try: os.close(self.fd)
        except OSError: pass

def warn_desktop():
    """デスクトップ（画面の管理役）や文字の画面が同じ LCD を使っていると、顔が映らない／上書きされる"""
    import subprocess
    try:
        if subprocess.run(["systemctl", "is-active", "--quiet", "display-manager"]).returncode == 0:
            print("※ デスクトップが動いているので、LCD にはデスクトップが映り、顔は映りません。\n"
                  "   試すとき:  sudo systemctl stop display-manager   （戻すときは start か再起動）")
    except FileNotFoundError:
        pass
    for d in glob.glob("/sys/class/vtconsole/vtcon*"):
        try:
            if "frame buffer" in open(d + "/name").read() and open(d + "/bind").read().strip() == "1":
                print("※ 文字の画面（コンソール）も同じ LCD を使っています。顔の上に文字やカーソルが出るときは:\n"
                      "   echo 0 | sudo tee %s/bind   （再起動で元に戻る）" % d)
        except OSError:
            pass

class PngLcd:
    """LCD なしで確認する（PC など）。数フレームごとに PNG に書く"""
    def __init__(self, a):
        self.path, self.n = a.png, 0
        print("LCD なし：%s に書きます" % self.path)
    def init(self): pass
    def show(self, v, rects):
        self.n += 1
        if True:                     # 変わったときだけ呼ばれるので毎回書く（確認用）
            r = ((v >> 11) & 31) * 255 // 31; g = ((v >> 5) & 63) * 255 // 63; b = (v & 31) * 255 // 31
            Image.fromarray(np.dstack([r, g, b]).astype(np.uint8)).save(self.path)
        return 0
    def close(self): pass

# ---------------------------------------------------------------- LCD ----
class Lcd:
    def __init__(self, a):
        import spidev, lgpio
        self.lgpio = lgpio
        self.h = None
        for chip in (4, 0):
            try:
                self.h = lgpio.gpiochip_open(chip); break
            except Exception:
                continue
        if self.h is None:
            raise RuntimeError("GPIOを開けません（lgpio）")
        self.a = a
        lgpio.gpio_claim_output(self.h, a.dc, 0)
        lgpio.gpio_claim_output(self.h, a.rst, 1)
        self.spi = spidev.SpiDev()
        self.spi.open(0, 1 if a.swap_cs else 0)
        self.spi.max_speed_hz = a.speed
        self.spi.mode = 0

    def cmd(self, c, data=b""):
        g = self.lgpio
        g.gpio_write(self.h, self.a.dc, 0)
        self.spi.writebytes2([c])
        if len(data):
            g.gpio_write(self.h, self.a.dc, 1)
            self.spi.writebytes2(data)

    def init(self):
        g, a = self.lgpio, self.a
        g.gpio_write(self.h, a.rst, 1); time.sleep(0.05)
        g.gpio_write(self.h, a.rst, 0); time.sleep(0.05)
        g.gpio_write(self.h, a.rst, 1); time.sleep(0.15)
        self.cmd(0x01); time.sleep(0.15)
        self.cmd(0x11); time.sleep(0.15)
        self.cmd(0xF0, b"\xC3"); self.cmd(0xF0, b"\x96")
        madctl = {1: 0x28, 3: 0xE8}[a.rot]            # 横向き（1=標準、3=180度回転）
        if a.rgb: madctl ^= 0x08
        self.cmd(0x36, bytes([madctl]))
        self.cmd(0x3A, b"\x55")
        self.cmd(0xB4, b"\x01")
        self.cmd(0xB6, b"\x80\x02\x3B")
        self.cmd(0xE8, b"\x40\x8A\x00\x00\x29\x19\xA5\x33")
        self.cmd(0xC1, b"\x06"); self.cmd(0xC2, b"\xA7"); self.cmd(0xC5, b"\x18")
        self.cmd(0xE0, b"\xF0\x09\x0B\x06\x04\x15\x2F\x54\x42\x3C\x17\x14\x18\x1B")
        self.cmd(0xE1, b"\xE0\x09\x0B\x06\x04\x03\x2B\x43\x42\x3B\x16\x14\x17\x1B")
        self.cmd(0xF0, b"\x3C"); self.cmd(0xF0, b"\x69")
        time.sleep(0.12)
        self.cmd(0x21 if a.invert else 0x20)
        self.cmd(0x29); time.sleep(0.05)

    def blit(self, x0, y0, x1, y1, buf):
        self.cmd(0x2A, bytes([x0 >> 8, x0 & 255, x1 >> 8, x1 & 255]))
        self.cmd(0x2B, bytes([y0 >> 8, y0 & 255, y1 >> 8, y1 & 255]))
        self.cmd(0x2C)
        self.lgpio.gpio_write(self.h, self.a.dc, 1)
        self.spi.writebytes2(buf)

    def show(self, v, rects):
        sent = 0
        for x0, y0, x1, y1 in (rects or [(0, 0, W - 1, H - 1)]):
            block = np.ascontiguousarray(v[y0:y1 + 1, x0:x1 + 1]).astype(">u2").tobytes()
            self.blit(x0, y0, x1, y1, block)
            sent += len(block)
        return sent

    def close(self):
        try:
            self.spi.close(); self.lgpio.gpiochip_close(self.h)
        except Exception:
            pass

def to565(rgb):
    r = rgb[..., 0].astype(np.uint16); g = rgb[..., 1].astype(np.uint16); b = rgb[..., 2].astype(np.uint16)
    return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3)

# ----------------------------------------------------------- コマンド入力 ----
def start_input(q):
    def from_stdin():
        for line in sys.stdin:
            q.put(line.strip())
        q.put("")                      # EOF（バックグラウンド実行のとき）は無視
    def from_fifo():
        try:
            if os.path.exists(FIFO): os.remove(FIFO)
            os.mkfifo(FIFO, 0o666)
        except OSError:
            return
        while True:
            with open(FIFO) as f:
                for line in f:
                    q.put(line.strip())
    if sys.stdin and sys.stdin.isatty():
        threading.Thread(target=from_stdin, daemon=True).start()
    threading.Thread(target=from_fifo, daemon=True).start()

# ---------------------------------------------------------------- タッチ ----
TOUCH_FILE = getattr(globals().get("config"), "TOUCH_FILE", "/tmp/aidy_touch")


class Touch:
    """画面のタッチを読み、触ったら TOUCH_FILE に「時刻 x y」を書く（司令塔が見て、アラームを止める・ウィジェットを閉じる）
      fb の機体：ドライバ（ads7846）の /dev/input/event* を読む
      spi の機体：XPT2046 を CE1（--swap-cs なら CE0）から直接読む（TP_IRQ はつながなくてよい）"""
    def __init__(self, a, mode, log=print):
        self.a, self.mode, self.log = a, mode, log
        self.down = False
        self.n = 0
        threading.Thread(target=self.run, daemon=True).start()

    def hit(self, x=-1, y=-1):
        self.n += 1
        try:
            with open(TOUCH_FILE + ".tmp", "w") as f:
                f.write("%.2f %d %d\n" % (time.time(), x, y))
            os.replace(TOUCH_FILE + ".tmp", TOUCH_FILE)
        except OSError:
            pass
        if self.a.stats or self.n <= 3:
            print("タッチ (%d, %d)" % (x, y), flush=True)

    def run(self):
        try:
            self.run_evdev() if self.mode == "fb" else self.run_xpt()
        except Exception as e:
            print("※ タッチを読めません（%s）。タッチなしで続けます" % e, flush=True)

    @staticmethod
    def find_event():
        try:
            text = open("/proc/bus/input/devices").read()
        except OSError:
            return None
        for block in text.split("\n\n"):
            name = next((ln for ln in block.splitlines() if ln.startswith("N:")), "")
            if any(k in name.lower() for k in ("ads7846", "xpt2046", "touch")):
                for ln in block.splitlines():
                    if ln.startswith("H:"):
                        for w in ln.split():
                            if w.startswith("event"):
                                return "/dev/input/" + w
        return None

    def run_evdev(self):
        import struct
        dev = self.find_event()
        if not dev:
            print("※ タッチのデバイス（/dev/input/event*）が見つかりません。タッチなしで続けます", flush=True)
            return
        print("タッチ:", dev, flush=True)
        fmt = "llHHi"
        size = struct.calcsize(fmt)
        x = y = -1
        with open(dev, "rb") as f:
            while True:
                data = f.read(size)
                if len(data) < size:
                    return
                _, _, typ, code, val = struct.unpack(fmt, data)
                if typ == 3 and code == 0:
                    x = val
                elif typ == 3 and code == 1:
                    y = val
                elif typ == 1 and code == 330:                  # BTN_TOUCH
                    if val == 1 and not self.down:
                        self.hit(x, y)
                    self.down = val == 1

    def run_xpt(self):
        import spidev
        spi = spidev.SpiDev()
        spi.open(0, 0 if self.a.swap_cs else 1)
        spi.max_speed_hz = 1000000
        spi.mode = 0
        rd = lambda c: (lambda r: ((r[1] << 8) | r[2]) >> 3)(spi.xfer2([c, 0, 0]))
        print("タッチ: XPT2046（SPI CE%d）" % (0 if self.a.swap_cs else 1), flush=True)
        thr = getattr(globals().get("config"), "TOUCH_Z", 400)
        hits = 0
        while True:
            z1, z2 = rd(0xB0), rd(0xC0)
            z = z1 + 4095 - z2
            if z > thr and 20 < z1 < 4080:
                hits += 1
                if hits == 2 and not self.down:                 # 2回続けて押されていたら（雑音で反応しないように）
                    self.down = True
                    xr, yr = rd(0xD0), rd(0x90)
                    self.hit(int(xr * W / 4096), int(yr * H / 4096))
            elif z < thr * 0.5:
                hits, self.down = 0, False
            time.sleep(0.04)


def changed_rects(d, row_gap=6, col_gap=24):
    """変わった画素を、少数の長方形にまとめる（目が2つ離れているとき、間の黒い部分を送らない）"""
    rows = np.where(d.any(axis=1))[0]
    if rows.size == 0:
        return []
    bands, start, prev = [], rows[0], rows[0]
    for r in rows[1:]:
        if r - prev > row_gap:
            bands.append((start, prev)); start = r
        prev = r
    bands.append((start, prev))
    rects = []
    for y0, y1 in bands:
        cols = np.where(d[y0:y1 + 1].any(axis=0))[0]
        cs, cp = cols[0], cols[0]
        for c in cols[1:]:
            if c - cp > col_gap:
                rects.append((int(cs), int(y0), int(cp), int(y1))); cs = c
            cp = c
        rects.append((int(cs), int(y0), int(cp), int(y1)))
    return rects


def run(a):
    import faces
    if not faces.FONT_PATH:
        print("※ 日本語のフォントが見つかりません。ウィジェットの文字が化けます:  sudo apt install -y fonts-noto-cjk")
    else:
        print("フォント:", faces.FONT_PATH)
    found = find_fb() if a.out in ("auto", "fb") else None
    if a.out == "fb" and not found:
        print("320x480 の framebuffer（/dev/fb*）が見つかりません。ls /sys/class/graphics を確認してください。"); return 2
    if a.rot is None:          # 向きの既定は映し先ごと（1号機＝fb は 3、チームの機体＝spi は 1 で確認済み）
        cfg = globals().get("config")
        a.rot = getattr(cfg, "LCD_ROT", 3) if (found and a.out != "png") else getattr(cfg, "LCD_ROT_SPI", 1)
    try:
        if a.out == "png":
            lcd = PngLcd(a)
        elif found:
            lcd = FbLcd(a, found)
        else:
            lcd = Lcd(a)
    except PermissionError as e:
        print("LCD を開く権限がありません（%s）。sudo usermod -aG video,spi,gpio $USER のあとログインし直す" % e); return 2
    except ImportError as e:
        print("必要なモジュールがありません（%s）。 sudo apt install -y python3-spidev python3-lgpio" % e); return 2
    except FileNotFoundError:
        print("/dev/spidev0.0 がありません。LCD のドライバ（fb）も見つかりません。配線テストの会話の LCD 設定を確認してください。"); return 2
    lcd.init()
    if not a.no_touch and not isinstance(lcd, PngLcd):
        Touch(a, "fb" if isinstance(lcd, FbLcd) else "spi")
    if isinstance(lcd, FbLcd):
        warn_desktop()
    blink = getattr(globals().get("config"), "FACE_BLINK_SEC", None)
    r = faces.Renderer(blink=blink)
    try:
        r.set_face(a.face)
    except ValueError as e:
        print(e)
    cq = queue.Queue()
    start_input(cq)
    print("表情 %d 種類：%s" % (len(faces.FACES), " ".join(faces.FACES)))
    print("コマンド例：happy / look -60 0 / level 0.7 / screen weather / charge on / list / quit")
    names = list(faces.FACES)
    # デモ：表情に加えて、表情にしない表示（考え中・充電中）とウィジェットも順番に見せる
    demo_steps = [("face", n, faces.FACES[n]["label"]) for n in names] + \
        [("busy", "normal", "考え中（左上のくるくる）"), ("charge", "normal", "充電中（右上のアイコン）")]
    sample = os.path.join(os.path.dirname(os.path.abspath(__file__)), "face", "sample_cards.json")
    if os.path.exists(sample):
        import json
        for kind, card in json.load(open(sample, encoding="utf-8")).items():
            r.cards.setdefault(kind, card)
            demo_steps.append(("screen", kind, {"weather": "天気ウィジェット（見本）", "news": "ニュースウィジェット（見本）",
                                                "todo": "やることウィジェット（見本）", "timer": "タイマーウィジェット（見本）",
                                                "calendar": "カレンダーウィジェット（見本）"}.get(kind, kind)))
    demo_i, demo_t, demo_level = 0, time.time(), False
    period = 1.0 / a.fps if a.fps > 0 else 0
    prev, full_t = None, 0.0
    t_stat, n_draw, n_send, t_draw, sent = time.time(), 0, 0, 0.0, 0
    running = True
    try:
        while running:
            t0 = time.time()
            while not cq.empty():
                line = cq.get().strip()
                if not line:
                    continue
                c = line.split()[0].lower()
                if c in ("quit", "exit", "q"):
                    running = False
                elif c == "list":
                    print(" ".join("%s(%s)" % (n, faces.FACES[n]["label"]) for n in names))
                elif c == "demo":
                    a.demo = float(line.split()[1]) if len(line.split()) > 1 else 3.0
                else:
                    try:
                        r.command(line)
                    except Exception as e:
                        print("コマンドを実行できません：%s（%s）" % (line[:80], e))
            if a.demo and time.time() - demo_t >= a.demo:
                demo_t = time.time()
                kind, name, label = demo_steps[demo_i % len(demo_steps)]
                demo_i += 1
                r.charging = False
                if kind != "screen":
                    r.set_screen("face")
                    r.set_face(name)
                if kind == "busy":
                    r.busy = True
                elif kind == "charge":
                    r.charging = True
                elif kind == "screen":
                    r.set_screen(name)
                demo_level = name in ("listen", "speak")
                if not demo_level:
                    r.level = 0.0
                print("デモ %d/%d：%s" % ((demo_i - 1) % len(demo_steps) + 1, len(demo_steps), label), flush=True)
            if a.demo and demo_level:                    # 聞いている・話しているは、声が出ているつもりで動かす
                import math
                r.level = 0.5 + 0.5 * math.sin(time.time() * 9)
            td = time.time()
            img = r.frame(force=prev is None or time.time() - full_t > 10)
            if img is not None:
                n_draw += 1
                v = to565(np.asarray(img))
                if prev is None or time.time() - full_t > 10:    # 10秒に1回は全画面を送り直す
                    full_t = time.time()
                    rects = None
                else:
                    rects = changed_rects(v != prev)
                if rects is None or rects:
                    sent += lcd.show(v, rects) or 0
                    n_send += 1
                prev = v
            t_draw += time.time() - td
            if a.stats and time.time() - t_stat > 5:
                dt = time.time() - t_stat
                print("描画 %.1f 回/秒・送信 %.1f 回/秒・1回 %.1f ms（描画と送信）・%.0f KB/秒"
                      % (n_draw / dt, n_send / dt, t_draw / max(1, n_draw) * 1000, sent / 1024 / dt), flush=True)
                t_stat, n_draw, n_send, t_draw, sent = time.time(), 0, 0, 0.0, 0
            if a.seconds and time.time() - a.start_t > a.seconds:
                break
            wait = period - (time.time() - t0)
            if wait > 0:
                time.sleep(wait)
    except KeyboardInterrupt:
        pass
    finally:
        lcd.close()
        try:
            os.remove(FIFO)
        except OSError:
            pass
    return 0


def main():
    import signal
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))   # start.py から止めるときも後片付けする
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="auto", choices=["auto", "fb", "spi", "png"], help="映し先（既定 auto）")
    ap.add_argument("--png", default="/tmp/aidy_face.png", help="--out png のときの書き出し先")
    ap.add_argument("--sheet", default="", help="全表情とウィジェットを並べた画像を書いて終わる（確認用）")
    ap.add_argument("--face", default="normal")
    ap.add_argument("--demo", type=float, default=0, help="全表情をこの秒数ごとに順番に映す")
    ap.add_argument("--rot", type=int, default=None, choices=[1, 3],
                    help="画面の向き。上下逆さなら 1 と 3 を入れ替える（既定は config.py の LCD_ROT＝fb / LCD_ROT_SPI＝spi）")
    ap.add_argument("--fps", type=float, default=20, help="描く回数の上限。変化がないときは描かない")
    ap.add_argument("--speed", type=int, default=32000000, help="SPI速度（spi のとき。チームの機体で 32MHz 確認済み）")
    ap.add_argument("--dc", type=int, default=14)
    ap.add_argument("--rst", type=int, default=15)
    ap.add_argument("--no-invert", dest="invert", action="store_false", help="spi のとき白黒反転をやめる（既定は反転＝チームの機体）")
    ap.add_argument("--rgb", action="store_true")
    ap.add_argument("--swap-cs", action="store_true")
    ap.add_argument("--no-touch", action="store_true", help="画面のタッチを読まない")
    ap.add_argument("--stats", action="store_true", help="描く回数と時間を表示")
    ap.add_argument("--seconds", type=float, default=0, help="この秒数で終了（テスト用）")
    a = ap.parse_args()
    a.start_t = time.time()
    if a.sheet:
        import faces, json
        here = os.path.dirname(os.path.abspath(__file__))
        sample = os.path.join(here, "face", "sample_cards.json")
        cards = json.load(open(sample, encoding="utf-8")) if os.path.exists(sample) else {}
        print("書きました:", faces.contact_sheet(a.sheet, cards))
        return 0
    return run(a)


if __name__ == "__main__":
    sys.exit(main())
