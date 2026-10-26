using UnityEngine;
using UnityEngine.UI;

/// <summary>
/// Webデモ版のUI機能（下部ナビゲーション、パーツ着脱、ナデナデボタン、ARリセット等）を
/// Unity上で制御するための統合コントローラー
/// </summary>
public class DemoUIController : MonoBehaviour
{
    [Header("連携マネージャー")]
    [SerializeField] private PageSwipeManager swipeManager;
    [SerializeField] private PetRobotAIController aiController;
    [SerializeField] private RobotPartCustomizer partCustomizer;
#if ENABLE_AR_FOUNDATION || UNITY_XR_ARFOUNDATION
    [SerializeField] private UnityEngine.XR.ARFoundation.ARSessionOrigin arOrigin;
#endif

    [Header("ボトムナビゲーションボタン")]
    [SerializeField] private Button btnTabHome;
    [SerializeField] private Button btnTabRemote;
    [SerializeField] private Button btnTabAR;
    [SerializeField] private Button btnTabSettings;

    [Header("Page0: アクションボタン")]
    [SerializeField] private Button btnPetAction;
    [SerializeField] private Toggle tglEars;
    [SerializeField] private Toggle tglTail;
    [SerializeField] private Toggle tglLegs;
    [SerializeField] private Toggle tglFace;

    [Header("Page2: ARボタン")]
    [SerializeField] private Button btnArReset;

    private void Start()
    {
        // ボトムナビゲーションのリスナー登録
        if (btnTabHome != null) btnTabHome.onClick.AddListener(() => swipeManager.SetCurrentPage(0));
        if (btnTabRemote != null) btnTabRemote.onClick.AddListener(() => swipeManager.SetCurrentPage(1));
        if (btnTabAR != null) btnTabAR.onClick.AddListener(() => swipeManager.SetCurrentPage(2));
        if (btnTabSettings != null) btnTabSettings.onClick.AddListener(() => swipeManager.SetCurrentPage(3));

        // ナデナデアクション
        if (btnPetAction != null && aiController != null)
        {
            btnPetAction.onClick.AddListener(() => aiController.OnPetReaction());
        }

        // パーツ着脱トグル
        if (partCustomizer != null)
        {
            if (tglEars != null) tglEars.onValueChanged.AddListener(isOn => partCustomizer.ToggleEars(isOn));
            if (tglTail != null) tglTail.onValueChanged.AddListener(isOn => partCustomizer.ToggleTail(isOn));
            if (tglLegs != null) tglLegs.onValueChanged.AddListener(isOn => partCustomizer.ToggleLegs(isOn));
            if (tglFace != null) tglFace.onValueChanged.AddListener(isOn => partCustomizer.ToggleFace(isOn));
        }

        // AR位置リセット
        if (btnArReset != null)
        {
            btnArReset.onClick.AddListener(() => {
                // ARモデルを原点に戻す（デモ用簡易実装）
                GameObject arCube = GameObject.Find("AR_Cube_Placed"); // またはARPlacementController経由
                if (arCube != null) {
                    arCube.transform.position = Vector3.zero;
                    arCube.transform.rotation = Quaternion.identity;
                }
            });
        }
    }
}
