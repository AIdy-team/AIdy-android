using System.Collections.Generic;
using UnityEngine;
using UnityEngine.EventSystems;

// AR Foundation パッケージがインポートされている場合に使用する条件判定ディレクティブ
#if ENABLE_AR_FOUNDATION || UNITY_XR_ARFOUNDATION
using UnityEngine.XR.ARFoundation;
using UnityEngine.XR.ARSubsystems;
#endif

/// <summary>
/// AR Foundationを用いた平面検知・オブジェクト配置・回転制御の基礎実装。
/// パッケージ未導入時でもコンパイルエラーを防ぐ安全設計となっています。
/// </summary>
public class ARPlacementController : MonoBehaviour
{
#if ENABLE_AR_FOUNDATION || UNITY_XR_ARFOUNDATION
    [Header("AR Foundation コンポーネント")]
    [SerializeField] private ARRaycastManager raycastManager;
    [SerializeField] private ARPlaneManager planeManager;
#endif

    [Header("配置オブジェクト")]
    [SerializeField] private GameObject placedPrefab; // 配置用の Cube プレハブ
    [SerializeField] private Camera fallbackCamera;    // AR未サポート時のテスト用カメラ

    private GameObject spawnedObject;

#if ENABLE_AR_FOUNDATION || UNITY_XR_ARFOUNDATION
    private static List<ARRaycastHit> hits = new List<ARRaycastHit>();
#endif

    private void Update()
    {
        if (Input.touchCount == 0 && !Input.GetMouseButtonDown(0)) return;

        // タッチ位置の取得
        Vector2 touchPosition = Vector2.zero;
        bool isBegan = false;

        if (Input.touchCount > 0)
        {
            Touch touch = Input.GetTouch(0);
            touchPosition = touch.position;
            isBegan = (touch.phase == TouchPhase.Began);
        }
        else if (Input.GetMouseButtonDown(0))
        {
            touchPosition = Input.mousePosition;
            isBegan = true;
        }

        if (!isBegan) return;

        // UI上のタップであれば処理しない（操作競合の回避）
        if (EventSystem.current != null && EventSystem.current.IsPointerOverGameObject())
        {
            return;
        }

#if ENABLE_AR_FOUNDATION || UNITY_XR_ARFOUNDATION
        // --- AR Foundation による平面判定配置 ---
        if (raycastManager != null && raycastManager.Raycast(touchPosition, hits, TrackableType.PlaneWithinPolygon))
        {
            Pose hitPose = hits[0].pose;
            SpawnOrMoveObject(hitPose.position, hitPose.rotation);
        }
#else
        // --- AR Foundation 未導入時のエディタ/テスト用フォールバック配置 ---
        Camera cam = fallbackCamera != null ? fallbackCamera : Camera.main;
        if (cam != null)
        {
            Ray ray = cam.ScreenPointToRay(touchPosition);
            if (Physics.Raycast(ray, out RaycastHit hit))
            {
                SpawnOrMoveObject(hit.point, Quaternion.identity);
            }
            else
            {
                // カメラ前方2mの位置に配置
                Vector3 spawnPos = cam.transform.position + cam.transform.forward * 2.0f;
                SpawnOrMoveObject(spawnPos, Quaternion.identity);
            }
        }
#endif
    }

    private void SpawnOrMoveObject(Vector3 position, Quaternion rotation)
    {
        if (spawnedObject == null)
        {
            if (placedPrefab != null)
            {
                spawnedObject = Instantiate(placedPrefab, position, rotation);
            }
        }
        else
        {
            spawnedObject.transform.position = position;
            spawnedObject.transform.rotation = rotation;
        }
    }
}
