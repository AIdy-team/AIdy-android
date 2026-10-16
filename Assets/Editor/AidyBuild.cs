using System;
using System.IO;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEditor.SceneManagement;
using UnityEditor.XR.ARCore;
using UnityEditor.XR.Management;
using UnityEditor.XR.Management.Metadata;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.SceneManagement;
using UnityEngine.XR.Management;

namespace Aidy.EditorTools
{
    /// <summary>
    /// AIDY の Android 向けプロジェクト設定・シーン生成・ビルド。
    /// メニュー「AIDY」から実行するか、バッチモードで -executeMethod Aidy.EditorTools.AidyBuild.BatchBuildAndroid。
    /// </summary>
    public static class AidyBuild
    {
        public const string ScenePath = "Assets/Scenes/AIDY.unity";
        const string IconPath = "Assets/AIDY_Generated/AppIcon.png";
        const string AndroidApkPath = "Builds/Android/AIDY.apk";
        const string WindowsExePath = "Builds/Windows/AIDY.exe";

        [MenuItem("AIDY/1. プロジェクト設定 + AIDYシーン生成", priority = 0)]
        public static void Setup()
        {
            RestoreXRSimulationTemp();
            EnsureTMPEssentials();
            ConfigurePlayer();
            ConfigureXR();
            ConfigureLayers();
            CreateIcon();
            CreateScene();
            AssetDatabase.SaveAssets();
            Debug.Log("[AIDY] セットアップ完了: " + ScenePath);
        }

        [MenuItem("AIDY/2. Android APK をビルド", priority = 1)]
        public static void BuildAndroidMenu()
        {
            if (BuildAndroid()) EditorUtility.RevealInFinder(AndroidApkPath);
        }

        [MenuItem("AIDY/Windows プレビューをビルド", priority = 20)]
        public static void BuildWindowsMenu()
        {
            if (BuildWindows()) EditorUtility.RevealInFinder(WindowsExePath);
        }

        // ------------------------------------------------------------ バッチモード用

        public static void BatchSetup() { Run(() => { Setup(); return true; }); }
        public static void BatchBuildAndroid() { Run(() => { Setup(); return BuildAndroid(); }); }
        public static void BatchBuildWindows() { Run(() => { Setup(); return BuildWindows(); }); }

        static void Run(Func<bool> action)
        {
            bool ok;
            try { ok = action(); }
            catch (Exception e)
            {
                Debug.LogException(e);
                ok = false;
            }
            EditorApplication.Exit(ok ? 0 : 1);
        }

        // ------------------------------------------------------------ 設定

        /// <summary>
        /// AR Foundation の XR Simulation ビルド処理はビルド中に設定アセットを Assets/XR/Temp へ退避し、
        /// ビルド失敗時にそのまま残すことがある (次回ビルドが "Failed to move the asset" で失敗する)。
        /// 退避されたままのアセットを元の場所へ戻す。
        /// </summary>
        static void RestoreXRSimulationTemp()
        {
            const string temp = "Assets/XR/Temp";
            if (!Directory.Exists(temp)) return;
            var destinations = new System.Collections.Generic.Dictionary<string, string>
            {
                { "XRSimulationRuntimeSettings.asset", "Assets/XR/Resources" },
                { "XRSimulationPreferences.asset", "Assets/XR/UserSimulationSettings/Resources" }
            };
            foreach (var kv in destinations)
            {
                string src = Path.Combine(temp, kv.Key);
                if (!File.Exists(src)) continue;
                Directory.CreateDirectory(kv.Value);
                foreach (string ext in new[] { "", ".meta" })
                {
                    string dst = Path.Combine(kv.Value, kv.Key + ext);
                    if (File.Exists(src + ext))
                    {
                        File.Copy(src + ext, dst, true);
                        File.Delete(src + ext);
                    }
                }
            }
            if (Directory.GetFileSystemEntries(temp).Length == 0)
            {
                Directory.Delete(temp);
                if (File.Exists(temp + ".meta")) File.Delete(temp + ".meta");
            }
            AssetDatabase.Refresh();
        }

        /// <summary>TextMeshPro の SDF シェーダーと TMP Settings (文字描画に必須) をインポートする。</summary>
        static void EnsureTMPEssentials()
        {
            if (AssetDatabase.IsValidFolder("Assets/TextMesh Pro")) return;
            string pkg = Path.GetFullPath("Packages/com.unity.textmeshpro/Package Resources/TMP Essential Resources.unitypackage");
            AssetDatabase.ImportPackage(pkg, false);
            AssetDatabase.Refresh();
        }

        static void ConfigurePlayer()
        {
            PlayerSettings.companyName = "AIDY";
            PlayerSettings.productName = "AIDY";
            PlayerSettings.bundleVersion = "1.0.0";
            PlayerSettings.SetApplicationIdentifier(NamedBuildTarget.Android, "com.aidy.petrobot");
            PlayerSettings.defaultInterfaceOrientation = UIOrientation.Portrait;
            PlayerSettings.allowedAutorotateToPortrait = true;
            PlayerSettings.allowedAutorotateToPortraitUpsideDown = false;
            PlayerSettings.allowedAutorotateToLandscapeLeft = false;
            PlayerSettings.allowedAutorotateToLandscapeRight = false;

            // three.js r128 (出力エンコーディング Linear = sRGB 変換なし) と同じ色計算にする
            PlayerSettings.colorSpace = ColorSpace.Gamma;

            // Android: ARCore の要件 (IL2CPP / ARM64 / OpenGL ES3 / API 24+)
            PlayerSettings.SetScriptingBackend(NamedBuildTarget.Android, ScriptingImplementation.IL2CPP);
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
            PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel24;
            PlayerSettings.Android.targetSdkVersion = AndroidSdkVersions.AndroidApiLevelAuto;
            PlayerSettings.Android.bundleVersionCode = 1;
            PlayerSettings.SetUseDefaultGraphicsAPIs(BuildTarget.Android, false);
            PlayerSettings.SetGraphicsAPIs(BuildTarget.Android, new[] { GraphicsDeviceType.OpenGLES3 });
            PlayerSettings.Android.startInFullscreen = false; // ステータスバーは端末のものを表示
            PlayerSettings.Android.renderOutsideSafeArea = false;

            // Windows プレビュー (縦長ウィンドウ)
            PlayerSettings.fullScreenMode = FullScreenMode.Windowed;
            PlayerSettings.defaultScreenWidth = 412;
            PlayerSettings.defaultScreenHeight = 892;
            PlayerSettings.resizableWindow = true;
            PlayerSettings.runInBackground = true;
        }

        static void ConfigureXR()
        {
            // ARCore は「任意」にして、非対応端末でもインストール・起動できるようにする (その場合はカメラ映像モード)
            ARCoreSettings arcore = null;
            foreach (string guid in AssetDatabase.FindAssets("t:ARCoreSettings"))
            {
                arcore = AssetDatabase.LoadAssetAtPath<ARCoreSettings>(AssetDatabase.GUIDToAssetPath(guid));
                if (arcore != null) break;
            }
            if (arcore == null)
            {
                arcore = ScriptableObject.CreateInstance<ARCoreSettings>();
                Directory.CreateDirectory("Assets/XR/Settings");
                AssetDatabase.CreateAsset(arcore, "Assets/XR/Settings/ARCoreSettings.asset");
            }
            arcore.requirement = ARCoreSettings.Requirement.Optional;
            EditorUtility.SetDirty(arcore);
            ARCoreSettings.currentSettings = arcore;

            if (!EditorBuildSettings.TryGetConfigObject(XRGeneralSettings.k_SettingsKey, out XRGeneralSettingsPerBuildTarget perTarget) || perTarget == null)
            {
                perTarget = ScriptableObject.CreateInstance<XRGeneralSettingsPerBuildTarget>();
                Directory.CreateDirectory("Assets/XR");
                AssetDatabase.CreateAsset(perTarget, "Assets/XR/XRGeneralSettingsPerBuildTarget.asset");
                EditorBuildSettings.AddConfigObject(XRGeneralSettings.k_SettingsKey, perTarget, true);
            }
            if (!perTarget.HasSettingsForBuildTarget(BuildTargetGroup.Android)) perTarget.CreateDefaultSettingsForBuildTarget(BuildTargetGroup.Android);
            if (!perTarget.HasManagerSettingsForBuildTarget(BuildTargetGroup.Android)) perTarget.CreateDefaultManagerSettingsForBuildTarget(BuildTargetGroup.Android);

            XRGeneralSettings android = perTarget.SettingsForBuildTarget(BuildTargetGroup.Android);
            android.InitManagerOnStart = true;
            XRPackageMetadataStore.AssignLoader(android.Manager, "UnityEngine.XR.ARCore.ARCoreLoader", BuildTargetGroup.Android);
            EditorUtility.SetDirty(android);
            EditorUtility.SetDirty(perTarget);
        }

        static void ConfigureLayers()
        {
            var tagManager = new SerializedObject(AssetDatabase.LoadAllAssetsAtPath("ProjectSettings/TagManager.asset")[0]);
            SerializedProperty layers = tagManager.FindProperty("layers");
            layers.GetArrayElementAtIndex(Aidy.AidyHomeRoom.Layer).stringValue = "AidyHome3D";
            layers.GetArrayElementAtIndex(Aidy.AidyARView.FallbackLayer).stringValue = "AidyARPreview";
            tagManager.ApplyModifiedProperties();
        }

        static void CreateScene()
        {
            var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
            new GameObject("AIDY App").AddComponent<Aidy.AidyApp>();

            RenderSettings.ambientMode = AmbientMode.Trilight;
            RenderSettings.skybox = null;
            RenderSettings.reflectionIntensity = 0f;

            Directory.CreateDirectory(Path.GetDirectoryName(ScenePath));
            EditorSceneManager.SaveScene(scene, ScenePath);
            EditorBuildSettings.scenes = new[] { new EditorBuildSettingsScene(ScenePath, true) };
        }

        /// <summary>アプリアイコン (青グラデーション + 本体v1ロボの顔) を生成。</summary>
        static void CreateIcon()
        {
            const int n = 512;
            var tex = new Texture2D(n, n, TextureFormat.RGBA32, false);
            var px = new Color32[n * n];
            Color top = new Color32(0x3b, 0x82, 0xf6, 255), bottom = new Color32(0x63, 0x66, 0xf1, 255);
            Color body = new Color32(0xe2, 0xe8, 0xf0, 255), face = new Color32(0x02, 0x06, 0x17, 255), eye = new Color32(0x38, 0xbd, 0xf8, 255);
            for (int y = 0; y < n; y++)
            {
                for (int x = 0; x < n; x++)
                {
                    float u = x + 0.5f - n / 2f, v = y + 0.5f - n / 2f;
                    Color c = Color.Lerp(bottom, top, y / (float)n);
                    c = Over(c, body, Cover(Box(u - 150, v - 150, 26, 40, 14)));  // 右耳
                    c = Over(c, body, Cover(Box(u + 150, v - 150, 26, 40, 14)));  // 左耳
                    c = Over(c, body, Cover(Box(u, v + 10, 170, 160, 48)));       // 本体
                    c = Over(c, face, Cover(Box(u, v + 10, 120, 78, 22)));        // ディスプレイ
                    c = Over(c, eye, Cover(Box(u - 50, v + 10, 14, 30, 7)));
                    c = Over(c, eye, Cover(Box(u + 50, v + 10, 14, 30, 7)));
                    px[y * n + x] = c;
                }
            }
            tex.SetPixels32(px);
            Directory.CreateDirectory(Path.GetDirectoryName(IconPath));
            File.WriteAllBytes(IconPath, tex.EncodeToPNG());
            UnityEngine.Object.DestroyImmediate(tex);
            AssetDatabase.ImportAsset(IconPath);

            var icon = AssetDatabase.LoadAssetAtPath<Texture2D>(IconPath);
            PlayerSettings.SetIcons(NamedBuildTarget.Unknown, new[] { icon }, IconKind.Any);
        }

        static float Box(float x, float y, float hx, float hy, float r)
        {
            float qx = Mathf.Abs(x) - hx + r, qy = Mathf.Abs(y) - hy + r;
            return new Vector2(Mathf.Max(qx, 0), Mathf.Max(qy, 0)).magnitude + Mathf.Min(Mathf.Max(qx, qy), 0) - r;
        }

        static float Cover(float d) => Mathf.Clamp01(0.5f - d);
        static Color Over(Color under, Color over, float a) => Color.Lerp(under, over, a);

        // ------------------------------------------------------------ ビルド

        static bool BuildAndroid()
        {
            EditorUserBuildSettings.buildAppBundle = false;
            return Build(new BuildPlayerOptions
            {
                scenes = new[] { ScenePath },
                locationPathName = AndroidApkPath,
                target = BuildTarget.Android,
                targetGroup = BuildTargetGroup.Android,
                options = BuildOptions.None
            });
        }

        static bool BuildWindows()
        {
            return Build(new BuildPlayerOptions
            {
                scenes = new[] { ScenePath },
                locationPathName = WindowsExePath,
                target = BuildTarget.StandaloneWindows64,
                targetGroup = BuildTargetGroup.Standalone,
                options = BuildOptions.None
            });
        }

        static bool Build(BuildPlayerOptions options)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(options.locationPathName));
            BuildReport report = BuildPipeline.BuildPlayer(options);
            BuildSummary s = report.summary;
            Debug.Log($"[AIDY] Build {s.result}: {s.outputPath} ({s.totalSize / (1024f * 1024f):F1} MB, {s.totalErrors} errors, {s.totalTime})");
            foreach (BuildStep step in report.steps)
                foreach (BuildStepMessage m in step.messages)
                    if (m.type == LogType.Error || m.type == LogType.Exception)
                        Debug.Log($"[AIDY] BuildError ({step.name}): {m.content}");
            RestoreXRSimulationTemp();
            return s.result == BuildResult.Succeeded;
        }
    }
}
