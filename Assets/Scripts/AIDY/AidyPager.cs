using System;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace Aidy
{
    /// <summary>
    /// WebDemo の .scroll-viewport / .scroll-content の横スワイプ。
    /// ドラッグ追従 → 60px 以上でページ送り、端は 0.3 倍のラバーバンド、
    /// スナップは 0.35s cubic-bezier(0.2, 0.9, 0.3, 1)。
    /// </summary>
    public class AidyPager : MonoBehaviour, IInitializePotentialDragHandler, IBeginDragHandler, IDragHandler, IEndDragHandler
    {
        const float Threshold = 60f;
        const float SnapDuration = 0.35f;

        public RectTransform Viewport;
        public RectTransform Content;
        public RectTransform[] Pages;

        public int Current { get; private set; }
        public int PageCount => Pages.Length;
        public event Action<int> PageChanged;

        Canvas canvas;
        bool dragging;
        float dragStartX, dragDelta;
        float animFrom, animTo, animT = 1f;
        float lastWidth = -1f, lastHeight = -1f;

        float Width => Viewport.rect.width;
        float Scale => canvas != null ? canvas.scaleFactor : 1f;

        void Awake()
        {
            canvas = GetComponentInParent<Canvas>();
        }

        public void GoTo(int index)
        {
            Current = Mathf.Clamp(index, 0, PageCount - 1);
            dragging = false;
            StartSnap();
            PageChanged?.Invoke(Current);
        }

        public void Next() { if (Current < PageCount - 1) GoTo(Current + 1); }
        public void Prev() { if (Current > 0) GoTo(Current - 1); }

        /// <summary>ページが viewport 内に少しでも見えているか。</summary>
        public bool IsVisible(int page)
        {
            float x = Content.anchoredPosition.x + page * Width;
            return x < Width - 0.5f && x + Width > 0.5f;
        }

        void StartSnap()
        {
            animFrom = Content.anchoredPosition.x;
            animTo = -Current * Width;
            animT = 0f;
        }

        public void OnInitializePotentialDrag(PointerEventData e) { e.useDragThreshold = true; }

        public void OnBeginDrag(PointerEventData e)
        {
            dragging = true;
            animT = 1f; // .dragging { transition: none }
            dragStartX = e.pressPosition.x;
            OnDrag(e);
        }

        public void OnDrag(PointerEventData e)
        {
            if (!dragging) return;
            dragDelta = (e.position.x - dragStartX) / Scale;
            float w = Width;
            float x = -Current * w + dragDelta;
            if (x > 0) x *= 0.3f;
            float min = -(PageCount - 1) * w;
            if (x < min) x = min + (x - min) * 0.3f;
            SetX(x);
        }

        public void OnEndDrag(PointerEventData e)
        {
            if (!dragging) return;
            dragging = false;
            if (dragDelta < -Threshold && Current < PageCount - 1) Current++;
            else if (dragDelta > Threshold && Current > 0) Current--;
            StartSnap();
            PageChanged?.Invoke(Current);
        }

        void SetX(float x)
        {
            var p = Content.anchoredPosition;
            p.x = x;
            Content.anchoredPosition = p;
        }

        void LateUpdate()
        {
            float w = Width, h = Viewport.rect.height;
            if (!Mathf.Approximately(w, lastWidth) || !Mathf.Approximately(h, lastHeight))
            {
                lastWidth = w;
                lastHeight = h;
                Content.anchorMin = new Vector2(0, 0);
                Content.anchorMax = new Vector2(0, 1);
                Content.pivot = new Vector2(0, 0.5f);
                Content.sizeDelta = new Vector2(w * PageCount, 0);
                for (int i = 0; i < Pages.Length; i++)
                {
                    var rt = Pages[i];
                    rt.anchorMin = new Vector2(0, 0);
                    rt.anchorMax = new Vector2(0, 1);
                    rt.pivot = new Vector2(0, 0.5f);
                    rt.sizeDelta = new Vector2(w, 0);
                    rt.anchoredPosition = new Vector2(i * w, 0);
                }
                if (!dragging && animT >= 1f) SetX(-Current * w);
            }

            if (animT < 1f)
            {
                animT = Mathf.Min(1f, animT + Time.unscaledDeltaTime / SnapDuration);
                animTo = -Current * w;
                SetX(Mathf.LerpUnclamped(animFrom, animTo, CubicBezier.PageSnap.Evaluate(animT)));
            }
        }
    }

    /// <summary>
    /// ページ内の縦スクロール (overflow-y: auto)。横方向のドラッグは親のページャーへ渡す。
    /// </summary>
    public class NestedScrollRect : ScrollRect
    {
        AidyPager pager;
        bool routeToPager;

        AidyPager Pager
        {
            get
            {
                if (pager == null && transform.parent != null) pager = transform.parent.GetComponentInParent<AidyPager>();
                return pager;
            }
        }

        public override void OnInitializePotentialDrag(PointerEventData e)
        {
            if (Pager != null) Pager.OnInitializePotentialDrag(e);
            base.OnInitializePotentialDrag(e);
        }

        public override void OnBeginDrag(PointerEventData e)
        {
            Vector2 d = e.position - e.pressPosition;
            routeToPager = Mathf.Abs(d.x) > Mathf.Abs(d.y);
            if (routeToPager && Pager != null) Pager.OnBeginDrag(e);
            else base.OnBeginDrag(e);
        }

        public override void OnDrag(PointerEventData e)
        {
            if (routeToPager && Pager != null) Pager.OnDrag(e);
            else base.OnDrag(e);
        }

        public override void OnEndDrag(PointerEventData e)
        {
            if (routeToPager && Pager != null) Pager.OnEndDrag(e);
            else base.OnEndDrag(e);
            routeToPager = false;
        }
    }
}
