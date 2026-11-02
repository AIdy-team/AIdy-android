using System;
using System.Reflection;
using UnityEditor;
using UnityEngine;

namespace Aidy.EditorTools
{
    /// <summary>
    /// Unity Remote 5 (iPhone) で動作確認するための設定。
    /// Remote 中はエディタ実行なので XR は使われず、AR 画面は iPhone のカメラ映像 (WebCamTexture) + ArUco マーカー認識で動く。
    /// </summary>
    public static class AidyUnityRemote
    {
        const int Width = 1170, Height = 2532; // iPhone 12〜14 の縦画面
        const string SizeName = "AIDY iPhone (Unity Remote)";

        [MenuItem("AIDY/Unity Remote 5 (iPhone) を設定")]
        public static void Setup()
        {
            EditorSettings.unityRemoteDevice = "Any iOS Device";
            EditorSettings.unityRemoteCompression = "JPEG";
            EditorSettings.unityRemoteResolution = "Normal";
            EditorSettings.unityRemoteJoystickSource = "Remote";
            AssetDatabase.SaveAssets();

            bool size = SelectPortraitGameViewSize();
            bool ios = BuildPipeline.IsBuildTargetSupported(BuildTargetGroup.iOS, BuildTarget.iOS);

            Debug.Log(
                "[AIDY] Unity Remote 5 の設定をしました (Device: Any iOS Device / 圧縮: JPEG / 解像度: Normal)\n" +
                (size ? $"Game ビューを縦画面 {Width}x{Height} にしました。\n" : "Game ビューのサイズは手動で縦長 (例 1170x2532) にしてください。\n") +
                "手順:\n" +
                " 1. Windows では iTunes (または Apple Mobile Device Support) を入れておく\n" +
                " 2. iPhone を USB でつなぎ、「このコンピュータを信頼」を許可\n" +
                " 3. iPhone で Unity Remote 5 を開く\n" +
                " 4. Unity で Play (つながらなければ一度 Play を止め、Remote アプリを開き直してから再度 Play)\n" +
                " 5. AR タブ →「カメラ起動」。iPhone のカメラにならなければ「カメラ切替」を押す\n" +
                (ios ? "" : "※ iOS Build Support が入っていません。つながらない場合は Unity Hub でこのエディタに iOS Build Support を追加してください。\n") +
                "※ 映像は PC 経由なので遅れて見えます。マーカー認識・UI・タッチ操作の確認向け (ARKit の平面認識は Remote では使えません)。");
        }

        /// <summary>Game ビューに縦画面のサイズを追加して選ぶ (内部 API なので失敗しても無視)。</summary>
        static bool SelectPortraitGameViewSize()
        {
            try
            {
                Assembly asm = typeof(Editor).Assembly;
                Type sizesType = asm.GetType("UnityEditor.GameViewSizes");
                Type singleton = typeof(ScriptableSingleton<>).MakeGenericType(sizesType);
                object sizes = singleton.GetProperty("instance").GetValue(null);
                object groupType = sizesType.GetProperty("currentGroupType").GetValue(sizes);
                object group = sizesType.GetMethod("GetGroup").Invoke(sizes, new[] { groupType });
                Type groupT = group.GetType();

                int index = FindSize(group, groupT);
                if (index < 0)
                {
                    Type sizeType = asm.GetType("UnityEditor.GameViewSize");
                    Type kindType = asm.GetType("UnityEditor.GameViewSizeType");
                    object fixedRes = Enum.Parse(kindType, "FixedResolution");
                    object size = Activator.CreateInstance(sizeType, fixedRes, Width, Height, SizeName);
                    groupT.GetMethod("AddCustomSize").Invoke(group, new[] { size });
                    index = FindSize(group, groupT);
                }
                if (index < 0) return false;

                Type gameViewType = asm.GetType("UnityEditor.GameView");
                EditorWindow gameView = EditorWindow.GetWindow(gameViewType);
                PropertyInfo selected = gameViewType.GetProperty("selectedSizeIndex", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
                if (selected != null) selected.SetValue(gameView, index);
                else gameViewType.GetMethod("SizeSelectionCallback", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)
                    .Invoke(gameView, new object[] { index, null });
                gameView.Repaint();
                return true;
            }
            catch (Exception e)
            {
                Debug.LogWarning("[AIDY] Game ビューのサイズを設定できませんでした: " + e.Message);
                return false;
            }
        }

        static int FindSize(object group, Type groupT)
        {
            var texts = (string[])groupT.GetMethod("GetDisplayTexts").Invoke(group, null);
            for (int i = 0; i < texts.Length; i++)
                if (texts[i].Contains(SizeName)) return i;
            return -1;
        }
    }
}
