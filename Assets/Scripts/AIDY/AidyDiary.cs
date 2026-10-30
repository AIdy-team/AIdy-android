using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using UnityEngine;
using UnityEngine.Networking;

namespace Aidy
{
    /// <summary>Aidy 本体に残る会話ログの 1 行。time は "HH:mm"、speaker は "user" / "aidy"。</summary>
    [Serializable]
    public class ChatLine
    {
        public string time;
        public string speaker;
        public string text;

        public bool IsUser => speaker == "user";
    }

    /// <summary>1 日分の日記。date は "yyyy-MM-dd"。source は "device" (本体から同期) / "demo" (サンプル)。</summary>
    [Serializable]
    public class DiaryEntry
    {
        public string date;
        public string title;
        public string summary;
        public string mood;
        public string source;
        public string syncedAt;
        public List<ChatLine> logs = new List<ChatLine>();

        public int UserCount
        {
            get
            {
                int n = 0;
                foreach (ChatLine l in logs) if (l.IsUser) n++;
                return n;
            }
        }
    }

    /// <summary>日記の保存先 (persistentDataPath/diary/yyyy-MM-dd.json)。起動後の初回アクセスで全件読み込む。</summary>
    public static class DiaryStore
    {
        static Dictionary<string, DiaryEntry> entries;

        static string Dir => Path.Combine(Application.persistentDataPath, "diary");

        public static string Key(DateTime d) => d.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);

        public static DiaryEntry Get(DateTime d)
        {
            Load();
            return entries.TryGetValue(Key(d), out DiaryEntry e) ? e : null;
        }

        /// <summary>日付の新しい順。</summary>
        public static List<DiaryEntry> All()
        {
            Load();
            var list = new List<DiaryEntry>(entries.Values);
            list.Sort((a, b) => string.CompareOrdinal(b.date, a.date));
            return list;
        }

        public static void Save(DiaryEntry e)
        {
            Load();
            entries[e.date] = e;
            try
            {
                Directory.CreateDirectory(Dir);
                File.WriteAllText(Path.Combine(Dir, e.date + ".json"), JsonUtility.ToJson(e, true), Encoding.UTF8);
            }
            catch (Exception ex)
            {
                Debug.LogWarning($"[AIDY] 日記の保存に失敗 {e.date}: {ex.Message}");
            }
        }

        static void Load()
        {
            if (entries != null) return;
            entries = new Dictionary<string, DiaryEntry>();
            if (!Directory.Exists(Dir)) return;
            foreach (string path in Directory.GetFiles(Dir, "*.json"))
            {
                try
                {
                    var e = JsonUtility.FromJson<DiaryEntry>(File.ReadAllText(path, Encoding.UTF8));
                    if (e != null && !string.IsNullOrEmpty(e.date)) entries[e.date] = e;
                }
                catch (Exception ex)
                {
                    Debug.LogWarning($"[AIDY] 日記の読込に失敗 {path}: {ex.Message}");
                }
            }
        }
    }

    /// <summary>
    /// 会話ログから「Aidy が書いた一日日記」を組み立てる。
    /// 本体側でタイトル/本文/気分を生成して送ってきた場合はそちらを優先する。
    /// </summary>
    public static class DiaryComposer
    {
        static readonly string[] HappyWords = { "楽しい", "たのしい", "楽しかった", "うれしい", "嬉しい", "ありがとう", "好き", "すき", "おいしい", "美味しい", "やった", "最高", "面白", "おもしろ" };
        static readonly string[] TiredWords = { "疲れ", "つかれ", "眠い", "ねむい", "しんどい", "だるい", "つらい", "大変" };

        public static DiaryEntry Compose(string date, List<ChatLine> logs, string title = null, string summary = null, string mood = null)
        {
            var sorted = new List<ChatLine>(logs ?? new List<ChatLine>());
            sorted.RemoveAll(l => l == null || string.IsNullOrEmpty(l.text));
            foreach (ChatLine l in sorted) l.time = NormalizeTime(l.time);
            sorted.Sort((a, b) => string.CompareOrdinal(a.time, b.time));

            var user = sorted.FindAll(l => l.IsUser);
            if (string.IsNullOrEmpty(mood)) mood = DetectMood(user);
            if (string.IsNullOrEmpty(title)) title = TitleFor(mood, user.Count);
            if (string.IsNullOrEmpty(summary)) summary = Summarize(sorted, user, mood);

            return new DiaryEntry
            {
                date = date,
                title = title,
                summary = summary,
                mood = mood,
                logs = sorted,
                syncedAt = DateTime.Now.ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture)
            };
        }

        public static AidyFace FaceFor(string mood)
        {
            switch (mood)
            {
                case "happy": return AidyFace.Happy;
                case "tired":
                case "sleepy": return AidyFace.Sleepy;
                case "surprised": return AidyFace.Surprised;
                default: return AidyFace.Normal;
            }
        }

        static string DetectMood(List<ChatLine> user)
        {
            int happy = 0, tired = 0;
            foreach (ChatLine l in user)
            {
                foreach (string w in HappyWords) if (l.text.Contains(w)) happy++;
                foreach (string w in TiredWords) if (l.text.Contains(w)) tired++;
            }
            if (happy == 0 && tired == 0) return "normal";
            return happy >= tired ? "happy" : "tired";
        }

        static string TitleFor(string mood, int count)
        {
            if (count == 0) return "しずかな一日";
            switch (mood)
            {
                case "happy": return "たのしい一日";
                case "tired": return "ちょっとおつかれの一日";
                default: return count >= 8 ? "たくさんおしゃべりした一日" : "いつもの一日";
            }
        }

        static string Summarize(List<ChatLine> all, List<ChatLine> user, string mood)
        {
            if (user.Count == 0) return "今日はあまりおしゃべりできなかったね。明日はいっぱい話そうね。";

            var sb = new StringBuilder();
            sb.Append($"今日は{Clock(user[0].time)}から{Clock(user[user.Count - 1].time)}まで、{user.Count}回おしゃべりしたよ。");

            string[] labels = { "朝", "昼", "夜" };
            for (int b = 0; b < 3; b++)
            {
                ChatLine best = null;
                foreach (ChatLine l in user)
                    if (Bucket(l.time) == b && (best == null || l.text.Length > best.text.Length)) best = l;
                if (best != null) sb.Append($"\n{labels[b]}は「{Trim(best.text, 24)}」って話してくれたね。");
            }

            switch (mood)
            {
                case "happy": sb.Append("\nたくさん笑ってくれて、ぼくもうれしかった！"); break;
                case "tired": sb.Append("\nおつかれさま。今日はゆっくり休んでね。"); break;
                default: sb.Append("\n明日もいっぱいお話ししようね。"); break;
            }
            return sb.ToString();
        }

        static int Bucket(string time)
        {
            int h = int.TryParse(time.Split(':')[0], out int v) ? v : 12;
            return h < 11 ? 0 : h < 17 ? 1 : 2;
        }

        static string NormalizeTime(string t)
        {
            if (string.IsNullOrEmpty(t)) return "00:00";
            string[] parts = t.Split(':');
            int h = 0, m = 0;
            if (parts.Length > 0) int.TryParse(parts[0], out h);
            if (parts.Length > 1) int.TryParse(parts[1], out m);
            return $"{h:00}:{m:00}";
        }

        static string Clock(string time) => time.StartsWith("0") ? time.Substring(1) : time; // "07:42" → "7:42"

        static string Trim(string s, int max) => s.Length <= max ? s : s.Substring(0, max) + "…";
    }

    /// <summary>
    /// Wi-Fi 経由で Aidy 本体から会話ログを取得する。
    /// 本体側 API (予定): GET http://{Host}/api/logs?date=yyyy-MM-dd
    ///   200 → {"date":"2026-10-23","logs":[{"time":"08:12","speaker":"user","text":"おはよう"}, ...],
    ///          "title":"(任意)","summary":"(任意)","mood":"(任意: happy/normal/tired)"}
    ///   404 → その日のログなし
    /// </summary>
    public static class AidyDeviceLink
    {
        public const string DefaultHost = "aidy.local";
        const string HostKey = "aidy.device.host";
        const string LastSyncKey = "aidy.device.lastSync";
        const int TimeoutSeconds = 4;

        [Serializable]
        class LogResponse
        {
            public string date;
            public string title;
            public string summary;
            public string mood;
            public List<ChatLine> logs;
        }

        public static string Host
        {
            get => PlayerPrefs.GetString(HostKey, DefaultHost);
            set
            {
                PlayerPrefs.SetString(HostKey, value);
                PlayerPrefs.Save();
            }
        }

        public static string LastSyncLabel
        {
            get
            {
                string t = PlayerPrefs.GetString(LastSyncKey, "");
                return string.IsNullOrEmpty(t) ? "まだ本体と同期していません" : "最終同期: " + t;
            }
        }

        /// <summary>今日から days 日分さかのぼってログを取得し、日記を作り直して保存する。done(成功, 表示メッセージ, 更新件数)。</summary>
        public static IEnumerator SyncRecent(int days, Action<bool, string, int> done)
        {
            int updated = 0;
            for (int i = 0; i < days; i++)
            {
                string date = DiaryStore.Key(DateTime.Today.AddDays(-i));
                using (var req = UnityWebRequest.Get($"http://{Host}/api/logs?date={date}"))
                {
                    req.timeout = TimeoutSeconds;
                    yield return req.SendWebRequest();

                    if (req.result == UnityWebRequest.Result.ConnectionError)
                    {
                        done(false, $"Aidy本体に接続できませんでした ({Host})", updated);
                        yield break;
                    }
                    if (req.result != UnityWebRequest.Result.Success) continue; // 404 等 = その日のログなし

                    LogResponse res = null;
                    try { res = JsonUtility.FromJson<LogResponse>(req.downloadHandler.text); }
                    catch (Exception e) { Debug.LogWarning($"[AIDY] ログの解析に失敗 {date}: {e.Message}"); }
                    if (res == null || res.logs == null || res.logs.Count == 0) continue;

                    DiaryEntry entry = DiaryComposer.Compose(date, res.logs, res.title, res.summary, res.mood);
                    entry.source = "device";
                    DiaryStore.Save(entry);
                    updated++;
                }
            }

            PlayerPrefs.SetString(LastSyncKey, DateTime.Now.ToString("M/d HH:mm", CultureInfo.InvariantCulture));
            PlayerPrefs.Save();
            done(true, updated > 0 ? $"{updated}日分の日記を更新しました" : "新しい会話ログはありませんでした", updated);
        }
    }

    /// <summary>日記カレンダーの表示設定 (月表示 / 週表示)。設定画面から切り替える。</summary>
    public static class CalendarSettings
    {
        const string WeekViewKey = "aidy.calendar.weekView";

        public static bool WeekView
        {
            get => PlayerPrefs.GetInt(WeekViewKey, 0) == 1;
            set
            {
                PlayerPrefs.SetInt(WeekViewKey, value ? 1 : 0);
                PlayerPrefs.Save();
            }
        }
    }

    /// <summary>カレンダーに登録する予定 1 件。date は "yyyy-MM-dd"、time は "HH:mm" (空なら終日)。</summary>
    [Serializable]
    public class ScheduleItem
    {
        public string id;
        public string date;
        public string time;
        public string title;
        public string memo;

        public bool AllDay => string.IsNullOrEmpty(time);

        /// <summary>時刻つきの予定で、その時刻をもう過ぎているか (終日の予定は過ぎない扱い)。</summary>
        public bool IsPast(DateTime now) => !AllDay && string.CompareOrdinal(date + " " + time, now.ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture)) < 0;
    }

    /// <summary>予定の保存先 (persistentDataPath/schedule.json)。過去・未来どの日にも登録できる。</summary>
    public static class ScheduleStore
    {
        [Serializable]
        class FileData { public List<ScheduleItem> items = new List<ScheduleItem>(); }

        static List<ScheduleItem> items;

        static string FilePath => Path.Combine(Application.persistentDataPath, "schedule.json");

        /// <summary>その日の予定 (終日 → 時刻順)。時刻を過ぎた予定は後ろに回し、次の予定が先頭に来るようにする。</summary>
        public static List<ScheduleItem> For(DateTime d)
        {
            Load();
            string key = DiaryStore.Key(d);
            DateTime now = DateTime.Now;
            var list = items.FindAll(i => i.date == key);
            list.Sort((a, b) =>
            {
                bool pa = a.IsPast(now), pb = b.IsPast(now);
                if (pa != pb) return pa ? 1 : -1;
                if (a.AllDay != b.AllDay) return a.AllDay ? -1 : 1;
                return string.CompareOrdinal(a.time ?? "", b.time ?? "");
            });
            return list;
        }

        /// <summary>id が空なら新規追加、既存 id なら上書き。</summary>
        public static void Save(ScheduleItem item)
        {
            Load();
            if (string.IsNullOrEmpty(item.id)) item.id = Guid.NewGuid().ToString("N");
            int idx = items.FindIndex(i => i.id == item.id);
            if (idx >= 0) items[idx] = item;
            else items.Add(item);
            Write();
        }

        public static void Delete(string id)
        {
            Load();
            if (items.RemoveAll(i => i.id == id) > 0) Write();
        }

        /// <summary>"9:5" "0930" "9" などを "HH:mm" に揃える。空なら ""(終日)、読めなければ null。</summary>
        public static string ParseTime(string s)
        {
            s = (s ?? "").Trim().Replace("：", ":");
            if (s.Length == 0) return "";
            int h, m = 0;
            if (s.Contains(":"))
            {
                string[] p = s.Split(':');
                if (p.Length != 2 || !int.TryParse(p[0], out h) || (p[1].Length > 0 && !int.TryParse(p[1], out m))) return null;
            }
            else if (!int.TryParse(s, out h)) return null;
            else if (s.Length >= 3) { m = h % 100; h /= 100; }
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return $"{h:00}:{m:00}";
        }

        static void Load()
        {
            if (items != null) return;
            items = new List<ScheduleItem>();
            if (!File.Exists(FilePath)) return;
            try
            {
                var data = JsonUtility.FromJson<FileData>(File.ReadAllText(FilePath, Encoding.UTF8));
                if (data?.items != null) items = data.items.FindAll(i => i != null && !string.IsNullOrEmpty(i.date));
            }
            catch (Exception ex)
            {
                Debug.LogWarning($"[AIDY] 予定の読込に失敗: {ex.Message}");
            }
        }

        static void Write()
        {
            try
            {
                File.WriteAllText(FilePath, JsonUtility.ToJson(new FileData { items = items }, true), Encoding.UTF8);
            }
            catch (Exception ex)
            {
                Debug.LogWarning($"[AIDY] 予定の保存に失敗: {ex.Message}");
            }
        }
    }

    /// <summary>本体連携が完成するまでカレンダーの見た目を確認するためのサンプル日記 (「サンプル」表示付き)。</summary>
    public static class DiaryDemo
    {
        /// <summary>本体からの同期が動くようになったら false にする。</summary>
        public static bool Enabled = true;
        const string SeededKey = "aidy.diary.demoSeeded";

        static readonly string[][][] Conversations =
        {
            new[]
            {
                new[] { "07:42", "user", "おはよう、Aidy" },
                new[] { "07:42", "aidy", "おはよう！今日はいい天気だよ" },
                new[] { "12:15", "user", "お昼のパスタがすごくおいしかった" },
                new[] { "12:16", "aidy", "いいなあ、何味だったの？" },
                new[] { "12:16", "user", "トマトとバジル。また作りたい" },
                new[] { "21:30", "user", "今日は楽しかった、ありがとう" },
                new[] { "21:30", "aidy", "ぼくも楽しかったよ。おやすみ！" },
            },
            new[]
            {
                new[] { "08:05", "user", "今日は朝から会議が3つある" },
                new[] { "08:05", "aidy", "がんばってね！応援してるよ" },
                new[] { "19:48", "user", "ただいま。さすがに疲れた" },
                new[] { "19:48", "aidy", "おかえり！おつかれさま" },
                new[] { "22:10", "user", "もう眠いから寝るね" },
                new[] { "22:10", "aidy", "ゆっくり休んでね。おやすみ" },
            },
            new[]
            {
                new[] { "10:20", "user", "Aidy、今日の予定を教えて" },
                new[] { "10:20", "aidy", "15時に歯医者さんの予約があるよ" },
                new[] { "16:02", "user", "歯医者おわった、意外と平気だった" },
                new[] { "16:02", "aidy", "えらい！ちゃんと行けたね" },
                new[] { "20:40", "user", "明日は友だちと出かける予定" },
                new[] { "20:41", "aidy", "どこに行くの？楽しみだね" },
            },
            new[]
            {
                new[] { "09:12", "user", "週末だからのんびりする" },
                new[] { "09:12", "aidy", "いいね、ぼくもいっしょにのんびりする" },
                new[] { "14:30", "user", "新しい本を読み始めた。面白い" },
                new[] { "14:31", "aidy", "どんなお話？あとで教えてね" },
                new[] { "18:55", "user", "夕焼けがきれいだった" },
                new[] { "18:55", "aidy", "見たかったなあ。写真ある？" },
            },
        };

        static readonly int[] DaysAgo = { 1, 2, 4, 5, 8, 11, 12, 15, 19, 23, 27, 33, 38 };

        public static void SeedIfNeeded()
        {
            if (!Enabled || PlayerPrefs.GetInt(SeededKey, 0) == 1) return;
            for (int i = 0; i < DaysAgo.Length; i++)
            {
                var logs = new List<ChatLine>();
                foreach (string[] l in Conversations[i % Conversations.Length])
                    logs.Add(new ChatLine { time = l[0], speaker = l[1], text = l[2] });
                DiaryEntry e = DiaryComposer.Compose(DiaryStore.Key(DateTime.Today.AddDays(-DaysAgo[i])), logs);
                e.source = "demo";
                DiaryStore.Save(e);
            }
            PlayerPrefs.SetInt(SeededKey, 1);
            PlayerPrefs.Save();
        }
    }
}
