using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// 各種パーツモジュール（3Dモデル）をロボット本体へ動的に合体・着脱接続する
/// モジュールアセンブリ管理クラス
/// </summary>
public class ModularRobotAssembly : MonoBehaviour
{
    [System.Serializable]
    public class ModuleSocket
    {
        public string socketName;           // ソケット名 (例: CameraSocket, SensorSocket)
        public Transform attachTransform;   // 合体ポイントの Transform
        public GameObject currentModule;    // 現在合体されているモジュールオブジェクト
    }

    [Header("本体ベースモデル")]
    [SerializeField] private GameObject baseBodyModel;

    [Header("モジュール合体ソケット一覧")]
    [SerializeField] private List<ModuleSocket> sockets = new List<ModuleSocket>();

    /// <summary>
    /// 指定のソケットに新しいモジュール3Dモデルを合体・配置します
    /// </summary>
    public bool AttachModule(string socketName, GameObject modulePrefab)
    {
        ModuleSocket socket = sockets.Find(s => s.socketName == socketName);
        if (socket == null || socket.attachTransform == null)
        {
            Debug.LogWarning($"ソケット '{socketName}' が見つかりません。");
            return false;
        }

        // 既存モジュールの取り外し
        if (socket.currentModule != null)
        {
            Destroy(socket.currentModule);
        }

        // 新しいモジュールの生成と合体
        if (modulePrefab != null)
        {
            GameObject newModule = Instantiate(modulePrefab, socket.attachTransform);
            newModule.transform.localPosition = Vector3.zero;
            newModule.transform.localRotation = Quaternion.identity;
            newModule.transform.localScale = Vector3.one;

            socket.currentModule = newModule;
            Debug.Log($"モジュール '{modulePrefab.name}' を '{socketName}' に合体しました。");
            return true;
        }

        return false;
    }

    /// <summary>
    /// 指定ソケットからモジュールを取り外します
    /// </summary>
    public void DetachModule(string socketName)
    {
        ModuleSocket socket = sockets.Find(s => s.socketName == socketName);
        if (socket != null && socket.currentModule != null)
        {
            Destroy(socket.currentModule);
            socket.currentModule = null;
        }
    }
}
