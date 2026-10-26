using System.Collections;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.UI;
using UnityEngine.EventSystems;

/// <summary>
/// ScrollRectを用いたページのスナップ遷移・インジケーター更新・ボタン操作を制御するクラス
/// </summary>
[RequireComponent(typeof(ScrollRect))]
public class PageSwipeManager : MonoBehaviour, IBeginDragHandler, IEndDragHandler
{
    [Header("UI 参照")]
    [SerializeField] private ScrollRect scrollRect;
    [SerializeField] private RectTransform content;
    [SerializeField] private List<Image> pageDots; // ページインジケーター（ドット）
    [SerializeField] private Button prevButton;     // 左切り替えボタン「＜」
    [SerializeField] private Button nextButton;     // 右切り替えボタン「＞」

    [Header("スナップ設定")]
    [SerializeField] private float snapSpeed = 10f;  // スナップ補間速度
    [SerializeField] private Color activeDotColor = Color.white;
    [SerializeField] private Color inactiveDotColor = new Color(1f, 1f, 1f, 0.4f);

    private int pageCount;
    private int currentPageIndex = 0;
    private float[] pagePositions;
    private bool isTargetPosSet = false;
    private float targetPosition;
    private bool isDragging = false;

    private void Start()
    {
        if (scrollRect == null) scrollRect = GetComponent<ScrollRect>();
        if (content == null && scrollRect != null) content = scrollRect.content;

        if (content != null)
        {
            pageCount = content.childCount;
            pagePositions = new float[pageCount];

            // 各ページの正規化位置(0.0 ~ 1.0)を計算
            for (int i = 0; i < pageCount; i++)
            {
                pagePositions[i] = (pageCount > 1) ? (float)i / (pageCount - 1) : 0f;
            }
        }

        // ナビゲーションボタンのイベント登録
        if (prevButton != null) prevButton.onClick.AddListener(OnClickPrevious);
        if (nextButton != null) nextButton.onClick.AddListener(OnClickNext);

        UpdateUI();
    }

    private void Update()
    {
        // ドラッグ中でない場合、目標ページへスムースにスナップ移動
        if (!isDragging && isTargetPosSet && scrollRect != null)
        {
            scrollRect.horizontalNormalizedPosition = Mathf.Lerp(
                scrollRect.horizontalNormalizedPosition,
                targetPosition,
                Time.deltaTime * snapSpeed
            );

            if (Mathf.Abs(scrollRect.horizontalNormalizedPosition - targetPosition) < 0.001f)
            {
                scrollRect.horizontalNormalizedPosition = targetPosition;
                isTargetPosSet = false;
            }
        }
    }

    public void OnBeginDrag(PointerEventData eventData)
    {
        isDragging = true;
        isTargetPosSet = false;
    }

    public void OnEndDrag(PointerEventData eventData)
    {
        isDragging = false;

        if (scrollRect == null || pagePositions == null || pagePositions.Length == 0) return;

        // 現在のスクロール位置に最も近いページを算出
        float currentPos = scrollRect.horizontalNormalizedPosition;
        int nearestIndex = 0;
        float minDistance = float.MaxValue;

        for (int i = 0; i < pageCount; i++)
        {
            float distance = Mathf.Abs(currentPos - pagePositions[i]);
            if (distance < minDistance)
            {
                minDistance = distance;
                nearestIndex = i;
            }
        }

        SetCurrentPage(nearestIndex);
    }

    /// <summary>
    /// 指定インデックスのページへ切り替えます
    /// </summary>
    public void SetCurrentPage(int index)
    {
        if (pagePositions == null || pagePositions.Length == 0) return;

        currentPageIndex = Mathf.Clamp(index, 0, pageCount - 1);
        targetPosition = pagePositions[currentPageIndex];
        isTargetPosSet = true;

        UpdateUI();
    }

    public void OnClickNext()
    {
        if (currentPageIndex < pageCount - 1)
        {
            SetCurrentPage(currentPageIndex + 1);
        }
    }

    public void OnClickPrevious()
    {
        if (currentPageIndex > 0)
        {
            SetCurrentPage(currentPageIndex - 1);
        }
    }

    private void UpdateUI()
    {
        // ドットのハイライト更新
        if (pageDots != null)
        {
            for (int i = 0; i < pageDots.Count; i++)
            {
                if (pageDots[i] != null)
                {
                    pageDots[i].color = (i == currentPageIndex) ? activeDotColor : inactiveDotColor;
                }
            }
        }

        // 左右ボタンの活性/非活性切り替え
        if (prevButton != null) prevButton.interactable = (currentPageIndex > 0);
        if (nextButton != null) nextButton.interactable = (currentPageIndex < pageCount - 1);
    }
}
