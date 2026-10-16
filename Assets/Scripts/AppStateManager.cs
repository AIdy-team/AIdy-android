using UnityEngine;
using UnityEngine.UI;

#if ENABLE_AR_FOUNDATION || UNITY_XR_ARFOUNDATION
using UnityEngine.XR.ARFoundation;
#endif

/// <summary>
/// スマホ向け（Android）アプリとして、スワイプページに応じた
/// カメラ（ホーム3D用 vs AR用）の切り替えや状態管理を行う統合マネージャー
/// </summary>
public class AppStateManager : MonoBehaviour
{
    [Header("UI ページ管理")]
    [SerializeField] private PageSwipeManager swipeManager;

    [Header("ホーム画面用 (Page 0)")]
    [SerializeField] private Camera home3DCamera;
    [SerializeField] private GameObject home3DEnvironment;

    [Header("AR画面用 (Page 2)")]
    [SerializeField] private Camera arCamera;
    [SerializeField] private GameObject arSessionGroup; // ARSession と ARSessionOrigin をまとめた親オブジェクト

    private void Start()
    {
        // 初期状態はホーム画面 (Page 0)
        OnPageChanged(0);
    }

    private void Update()
    {
        // スワイプマネージャーの現在のページを監視して切り替え（イベント駆動にしてもOK）
        // ※ PageSwipeManager側でcurrentPageIndexを公開するか、イベントを飛ばすのが理想ですが
        // 今回は簡易的に ScrollRect の位置から現在ページを推測します
        if (swipeManager != null)
        {
            // ScrollRect の horizontalNormalizedPosition を元に 0〜3 のページを判定
            float scrollPos = swipeManager.GetComponent<ScrollRect>().horizontalNormalizedPosition;
            int pageIndex = Mathf.RoundToInt(scrollPos * 3f);
            pageIndex = Mathf.Clamp(pageIndex, 0, 3);
            
            // 状態が変わった時だけ切り替え処理を実行
            if (pageIndex != lastPageIndex)
            {
                OnPageChanged(pageIndex);
                lastPageIndex = pageIndex;
            }
        }
    }

    private int lastPageIndex = -1;

    private void OnPageChanged(int pageIndex)
    {
        Debug.Log("Current Page: " + pageIndex);

        if (pageIndex == 0) // ホーム画面 (3Dペットルーム)
        {
            if (home3DCamera != null) home3DCamera.gameObject.SetActive(true);
            if (home3DEnvironment != null) home3DEnvironment.SetActive(true);

            if (arCamera != null) arCamera.gameObject.SetActive(false);
            if (arSessionGroup != null) arSessionGroup.SetActive(false);
        }
        else if (pageIndex == 2) // AR画面
        {
            if (home3DCamera != null) home3DCamera.gameObject.SetActive(false);
            if (home3DEnvironment != null) home3DEnvironment.SetActive(false); // パフォーマンスのためホーム3Dを非表示

            if (arSessionGroup != null) arSessionGroup.SetActive(true);
            if (arCamera != null) arCamera.gameObject.SetActive(true);
        }
        else // リモコン(1) や 設定(3) の画面
        {
            // 背景としてホーム画面をぼかして表示するか、単色のUI背景にするかはお好みです
            // ここではホーム3Dを表示したままにします
            if (home3DCamera != null) home3DCamera.gameObject.SetActive(true);
            if (home3DEnvironment != null) home3DEnvironment.SetActive(true);

            if (arCamera != null) arCamera.gameObject.SetActive(false);
            if (arSessionGroup != null) arSessionGroup.SetActive(false);
        }
    }
}
