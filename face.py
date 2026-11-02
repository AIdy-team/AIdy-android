"""顔（LCD）への指示。lcd.py が作る FIFO（/tmp/aidy_cmd）に1行ずつコマンドを書く。

  face happy      表情を変える（名前は faces.py の FACES）
  look -60 0      その方向を見る
  level 0.7       声の大きさ（話している・聞いているときに目が少し動く）
  card {...}      ウィジェットの中身（lookup.py の card）
  screen weather  ウィジェットにする（face で顔に戻る）
  charge on/off   右上の充電中アイコン

lcd.py が動いていなければ何もしない（会話は止まらない）。チームの aidy_talk.py の Face をもとにしている。
ウィジェットを出している間も表情は送る。lcd.py はウィジェットのまま、天気の小さな顔だけその表情にする（10/27）。
"""
import json
import os

import config

FIFO = getattr(config, "FACE_FIFO", "/tmp/aidy_cmd")

# 返事の内容から、話し終わりの表情を決める（チームの aidy_talk.py の mood_of と同じ考え方）
MOOD_RULES = [("happy", ["うれしい", "嬉しい", "楽しい", "たのしい", "ありがとう", "やった", "よろしく", "大好き", "だいすき"]),
              ("sad", ["かなしい", "悲しい", "さみしい", "寂しい", "残念", "つらかった"]),
              ("sleepy", ["ねむい", "眠い", "おやすみ"])]


def mood_of(text):
    for face, words in MOOD_RULES:
        if any(w in text for w in words):
            return face
    return "normal"


class Face:
    def __init__(self, off=False, echo=False):
        self.fd, self.off, self.echo = None, off, echo
        self.current = "normal"
        self.widget = None              # いま出しているウィジェット（weather / news）

    def send(self, cmd):
        if self.echo and not cmd.startswith("level"):
            print(f"   (顔: {cmd})", flush=True)
        if self.off:
            return
        try:
            if self.fd is None:
                self.fd = os.open(FIFO, os.O_WRONLY | os.O_NONBLOCK)
            os.write(self.fd, (cmd + "\n").encode())
        except OSError:
            self.fd = None          # lcd.py がまだ／もういない

    def face(self, name):
        self.current = name
        self.send("face " + name)       # ウィジェット中は lcd.py が画面を替えずに小さな顔だけ替える

    def show_widget(self, card):
        """天気・ニュースのウィジェットを出す（戻すのは hide_widget）"""
        if not card or card.get("kind") not in ("weather", "news", "todo", "timer", "calendar"):
            return False
        self.send("card " + json.dumps(card, ensure_ascii=False))
        self.send("screen " + card["kind"])
        self.widget = card["kind"]
        return True

    def hide_widget(self):
        if self.widget:
            self.widget = None
            self.send("screen face")
            self.send("face " + (self.current if self.current != "thinking" else "normal"))

    def level(self, v):
        self.send("level %.2f" % v)

    def look(self, x, y=0):
        self.send("look %.0f %.0f" % (x, y))

    def screen(self, name):
        self.send("screen " + name)

    def timers(self, badge):
        """顔の右上に、タイマーの残り時間・次のアラームを出す（[] で消す）"""
        self.send("timers " + json.dumps(badge, ensure_ascii=False))
