using TMPro;
using UnityEngine;

namespace Aidy
{
    /// <summary>
    /// デフォルメ Aidy がしゃべる吹き出し型のお知らせ (「準備OK！」「ここに置いたよ！」など)。
    /// ぽよんと出てきて、時間が来たらすっと消える。seconds &lt;= 0 なら出しっぱなし。
    /// </summary>
    public class AidyToast : MonoBehaviour
    {
        RectTransform pill;
        CanvasGroup group;
        AidyMascot mascot;
        TMP_Text label;
        float shownAt, seconds;
        bool visible;

        /// <summary>parent の上端中央 (y = top からの距離) に置く。</summary>
        public static AidyToast Create(RectTransform parent, float top)
        {
            var root = UI.Rect("AidyToast", parent);
            root.Pin(new Vector2(0.5f, 1), new Vector2(0, -top), Vector2.zero);
            var t = root.gameObject.AddComponent<AidyToast>();

            t.pill = UI.Rect("Pill", root);
            t.pill.Pin(new Vector2(0.5f, 1), Vector2.zero, Vector2.zero);
            t.pill.pivot = new Vector2(0.5f, 1);
            UI.Box(t.pill, Color.white, 22, new BoxShadow(0, 6, 18, new Color(0, 0, 0, 0.12f)), Theme.Hex(0xe0e7ff), 1.5f);
            t.pill.HLayout(8, 16, 6, 6, 8);
            t.pill.FitContent();
            t.mascot = AidyMascot.Create(t.pill, 30, AidyFace.Happy);
            t.label = UI.Label(t.pill, "", 13, Theme.TextMain, true);

            t.group = t.pill.gameObject.AddComponent<CanvasGroup>();
            t.group.alpha = 0f;
            t.group.blocksRaycasts = false;
            return t;
        }

        public void Show(AidyFace face, string text, float seconds = 2.6f)
        {
            UI.EnsureChars(text);
            label.text = text;
            mascot.SetFace(face);
            mascot.React(face, 1.2f);
            shownAt = Time.unscaledTime;
            this.seconds = seconds;
            visible = true;
        }

        void Update()
        {
            float t = Time.unscaledTime - shownAt;
            if (visible && seconds > 0f && t > seconds) visible = false;

            // 出るとき: 0.35s でぽよん (1.08 まで膨らんで戻る) / 消えるとき: 0.25s でフェード
            float target = visible ? 1f : 0f;
            group.alpha = Mathf.MoveTowards(group.alpha, target, Time.unscaledDeltaTime / (visible ? 0.2f : 0.25f));
            float s = 1f;
            if (visible && t < 0.35f)
            {
                float p = t / 0.35f;
                s = p < 0.6f ? Mathf.Lerp(0.7f, 1.08f, p / 0.6f) : Mathf.Lerp(1.08f, 1f, (p - 0.6f) / 0.4f);
            }
            pill.localScale = new Vector3(s, s, 1);
        }
    }
}
