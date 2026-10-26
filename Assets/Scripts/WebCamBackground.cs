using UnityEngine;
using UnityEngine.UI;

/// <summary>
/// Webカメラ（PC内蔵カメラやスマホの背面/前面カメラ）のリアルタイム映像を
/// UI (RawImage) または Quad/Plane 背景に表示するデモ用スクリプト
/// </summary>
public class WebCamBackground : MonoBehaviour
{
    [Header("表示ターゲット")]
    [SerializeField] private RawImage targetRawImage; // UI RawImage に表示する場合
    [SerializeField] private Renderer targetRenderer; // 3D Plane/Quad に表示する場合

    [Header("カメラ設定")]
    [SerializeField] private bool useFrontFacing = false; // 前面カメラを使用するか
    [SerializeField] private int requestedWidth = 1280;
    [SerializeField] private int requestedHeight = 720;
    [SerializeField] private int requestedFPS = 30;

    private WebCamTexture webCamTexture;

    private void Start()
    {
        InitializeCamera();
    }

    /// <summary>
    /// 利用可能なWebカメラを初期化して再生を開始します
    /// </summary>
    public void InitializeCamera()
    {
        WebCamDevice[] devices = WebCamTexture.devices;
        if (devices.Length == 0)
        {
            Debug.LogWarning("Webカメラが見つかりませんでした。");
            return;
        }

        // カメラデバイスの選択
        string selectedCamName = devices[0].name;
        foreach (var device in devices)
        {
            if (device.isFrontFacing == useFrontFacing)
            {
                selectedCamName = device.name;
                break;
            }
        }

        webCamTexture = new WebCamTexture(selectedCamName, requestedWidth, requestedHeight, requestedFPS);

        // RawImageへの割り当て
        if (targetRawImage != null)
        {
            targetRawImage.texture = webCamTexture;
            targetRawImage.material.mainTexture = webCamTexture;
        }

        // 3D Renderer (Plane/Quad) への割り当て
        if (targetRenderer != null)
        {
            targetRenderer.material.mainTexture = webCamTexture;
        }

        webCamTexture.Play();
    }

    private void OnDestroy()
    {
        if (webCamTexture != null && webCamTexture.isPlaying)
        {
            webCamTexture.Stop();
        }
    }
}
