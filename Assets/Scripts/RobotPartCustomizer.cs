using UnityEngine;

/// <summary>
/// AIペットロボ (Cube) の耳、尻尾、足、顔などのパーツを
/// デバッグ・カスタマイズ用に動的着脱（ON/OFF）するスクリプト
/// </summary>
public class RobotPartCustomizer : MonoBehaviour
{
    [Header("カスタマイズパーツ (GameObject)")]
    [SerializeField] private GameObject earsObject;   // 耳パーツ
    [SerializeField] private GameObject tailObject;   // 尻尾パーツ
    [SerializeField] private GameObject legsObject;   // 足パーツ
    [SerializeField] private GameObject faceObject;   // 顔/目パーツ

    /// <summary>
    /// 耳パーツのON/OFF切り替え
    /// </summary>
    public void ToggleEars(bool isOn)
    {
        if (earsObject != null) earsObject.SetActive(isOn);
    }

    /// <summary>
    /// 尻尾パーツのON/OFF切り替え
    /// </summary>
    public void ToggleTail(bool isOn)
    {
        if (tailObject != null) tailObject.SetActive(isOn);
    }

    /// <summary>
    /// 足パーツのON/OFF切り替え
    /// </summary>
    public void ToggleLegs(bool isOn)
    {
        if (legsObject != null) legsObject.SetActive(isOn);
    }

    /// <summary>
    /// 顔パーツのON/OFF切り替え
    /// </summary>
    public void ToggleFace(bool isOn)
    {
        if (faceObject != null) faceObject.SetActive(isOn);
    }
}
