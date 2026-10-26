using UnityEngine;
using UnityEditor;
using UnityEngine.UI;

#if ENABLE_AR_FOUNDATION || UNITY_XR_ARFOUNDATION
using UnityEngine.XR.ARFoundation;
#endif

public class AIDYProjectSetup : EditorWindow
{
    [MenuItem("AIDY / 全自動プロジェクトセットアップ (Android & シーン構築)")]
    public static void SetupProjectAndFixes()
    {
        Debug.Log("=== AIDY プロジェクト自動セットアップ開始 ===");

        // 1. Androidビルドへ切り替え
        if (EditorUserBuildSettings.activeBuildTarget != BuildTarget.Android)
        {
            EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.Android, BuildTarget.Android);
            Debug.Log("✅ ビルドターゲットを Android に変更しました。");
        }
        else
        {
            Debug.Log("✅ ビルドターゲットは既に Android です。");
        }

        // 2. マテリアル・モデル設定の修正 (透明化防止)
        FixMaterials();

        // 3. シーンの自動構築（Canvas, Manager, Camera）
        BuildSceneHierarchy();

        Debug.Log("=== AIDY プロジェクト自動セットアップ完了！ ===");
        EditorUtility.DisplayDialog("セットアップ完了", "Android向けの設定、マテリアルの修正、およびシーン構造の構築が完了しました。\n\n※ AR Foundationのカメラ設定などはHierarchyから調整してください。", "OK");
    }

    private static void FixMaterials()
    {
        string[] guids = AssetDatabase.FindAssets("t:Material");
        int count = 0;
        foreach (string guid in guids)
        {
            string path = AssetDatabase.GUIDToAssetPath(guid);
            if (path.Contains("本体v1_モデル"))
            {
                Material mat = AssetDatabase.LoadAssetAtPath<Material>(path);
                if (mat != null && mat.GetFloat("_Mode") != 0) // Opaqueでない場合
                {
                    mat.SetFloat("_Mode", 0); // Opaque
                    mat.SetInt("_SrcBlend", (int)UnityEngine.Rendering.BlendMode.One);
                    mat.SetInt("_DstBlend", (int)UnityEngine.Rendering.BlendMode.Zero);
                    mat.SetInt("_ZWrite", 1);
                    mat.DisableKeyword("_ALPHATEST_ON");
                    mat.DisableKeyword("_ALPHABLEND_ON");
                    mat.DisableKeyword("_ALPHAPREMULTIPLY_ON");
                    mat.renderQueue = -1;
                    EditorUtility.SetDirty(mat);
                    count++;
                }
            }
        }
        AssetDatabase.SaveAssets();
        Debug.Log($"✅ モデルのマテリアルを {count} 件、不透明(Opaque)に修正しました。");
    }

    private static void BuildSceneHierarchy()
    {
        if (GameObject.Find("AppManager") != null)
        {
            Debug.Log("⚠️ AppManagerは既に存在するため、スキップします。");
            return;
        }

        GameObject appManager = new GameObject("AppManager");
        var stateManager = appManager.AddComponent<AppStateManager>();
        var uiController = appManager.AddComponent<DemoUIController>();

        // === 1. Canvas Setup ===
        GameObject canvasObj = new GameObject("UI_Canvas");
        Canvas canvas = canvasObj.AddComponent<Canvas>();
        canvas.renderMode = RenderMode.ScreenSpaceOverlay;
        var scaler = canvasObj.AddComponent<CanvasScaler>();
        scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
        scaler.referenceResolution = new Vector2(1080, 1920);
        canvasObj.AddComponent<GraphicRaycaster>();

        // === 2. Top Header ===
        GameObject header = new GameObject("TopHeader");
        header.transform.SetParent(canvasObj.transform, false);
        var rtHeader = header.AddComponent<RectTransform>();
        rtHeader.anchorMin = new Vector2(0, 1); rtHeader.anchorMax = new Vector2(1, 1);
        rtHeader.sizeDelta = new Vector2(0, 120); rtHeader.anchoredPosition = new Vector2(0, -60);
        var bgHeader = header.AddComponent<Image>(); bgHeader.color = Color.white;
        
        GameObject titleObj = new GameObject("Title");
        titleObj.transform.SetParent(header.transform, false);
        var txtTitle = titleObj.AddComponent<Text>();
        txtTitle.text = "AIDY - AIペットロボ"; txtTitle.color = new Color(0.1f, 0.15f, 0.2f);
        txtTitle.fontSize = 48; txtTitle.fontStyle = FontStyle.Bold;
        txtTitle.alignment = TextAnchor.MiddleCenter;
        txtTitle.font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
        var rtTitle = titleObj.GetComponent<RectTransform>();
        rtTitle.anchorMin = Vector2.zero; rtTitle.anchorMax = Vector2.one; rtTitle.sizeDelta = Vector2.zero;

        // === 3. Bottom Navigation ===
        GameObject bottomNav = new GameObject("BottomNav");
        bottomNav.transform.SetParent(canvasObj.transform, false);
        var rtBottom = bottomNav.AddComponent<RectTransform>();
        rtBottom.anchorMin = new Vector2(0, 0); rtBottom.anchorMax = new Vector2(1, 0);
        rtBottom.sizeDelta = new Vector2(0, 160); rtBottom.anchoredPosition = new Vector2(0, 80);
        var bgBottom = bottomNav.AddComponent<Image>(); bgBottom.color = new Color(0.95f, 0.95f, 0.95f);
        var hlgNav = bottomNav.AddComponent<HorizontalLayoutGroup>();
        hlgNav.childControlWidth = true; hlgNav.childControlHeight = true; hlgNav.spacing = 10;

        Button btnHome = CreateButton("Btn_Home", "🏠 ホーム", bottomNav.transform);
        Button btnRemote = CreateButton("Btn_Remote", "🎮 リモコン", bottomNav.transform);
        Button btnAR = CreateButton("Btn_AR", "📷 AR", bottomNav.transform);
        Button btnSettings = CreateButton("Btn_Settings", "⚙ 設定", bottomNav.transform);

        // === 4. ScrollView (Pages) ===
        GameObject scrollView = new GameObject("PageScrollView");
        scrollView.transform.SetParent(canvasObj.transform, false);
        var scrollRect = scrollView.AddComponent<ScrollRect>();
        scrollRect.horizontal = true; scrollRect.vertical = false;
        var rtScroll = scrollView.GetComponent<RectTransform>();
        rtScroll.anchorMin = new Vector2(0, 0); rtScroll.anchorMax = new Vector2(1, 1);
        rtScroll.offsetMin = new Vector2(0, 160); rtScroll.offsetMax = new Vector2(0, -120);

        GameObject viewport = new GameObject("Viewport");
        viewport.transform.SetParent(scrollView.transform, false);
        viewport.AddComponent<RectMask2D>();
        var rtView = viewport.GetComponent<RectTransform>();
        rtView.anchorMin = Vector2.zero; rtView.anchorMax = Vector2.one; rtView.sizeDelta = Vector2.zero;
        scrollRect.viewport = rtView;

        GameObject content = new GameObject("Content");
        content.transform.SetParent(viewport.transform, false);
        var rtContent = content.AddComponent<RectTransform>();
        rtContent.anchorMin = new Vector2(0, 0); rtContent.anchorMax = new Vector2(1, 1);
        rtContent.sizeDelta = new Vector2(4320, 0); // 1080 * 4 pages
        var hlg = content.AddComponent<HorizontalLayoutGroup>();
        hlg.childControlWidth = true; hlg.childControlHeight = true;
        scrollRect.content = rtContent;
        var swipeManager = scrollView.AddComponent<PageSwipeManager>();

        GameObject[] pages = new GameObject[4];
        for (int i = 0; i < 4; i++)
        {
            pages[i] = new GameObject($"Page{i}");
            pages[i].transform.SetParent(content.transform, false);
            pages[i].AddComponent<RectTransform>();
        }

        // --- Page 0 (Home) UI ---
        // AI Status text
        GameObject statusObj = new GameObject("StatusBubble");
        statusObj.transform.SetParent(pages[0].transform, false);
        var rtStatus = statusObj.AddComponent<RectTransform>();
        rtStatus.anchorMin = new Vector2(0, 1); rtStatus.anchorMax = new Vector2(1, 1);
        rtStatus.sizeDelta = new Vector2(-100, 80); rtStatus.anchoredPosition = new Vector2(0, -60);
        var bgStatus = statusObj.AddComponent<Image>(); bgStatus.color = new Color(1, 1, 1, 0.8f);
        
        GameObject txtStatusObj = new GameObject("Text");
        txtStatusObj.transform.SetParent(statusObj.transform, false);
        var txtStatus = txtStatusObj.AddComponent<Text>();
        txtStatus.text = "🧠 本体v1モデル 準備完了"; txtStatus.color = new Color(0.2f, 0.5f, 0.9f);
        txtStatus.fontSize = 36; txtStatus.alignment = TextAnchor.MiddleCenter;
        txtStatus.font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
        var rtTxtStatus = txtStatusObj.GetComponent<RectTransform>();
        rtTxtStatus.anchorMin = Vector2.zero; rtTxtStatus.anchorMax = Vector2.one; rtTxtStatus.sizeDelta = Vector2.zero;

        // Action Panel
        GameObject panelPage0 = new GameObject("ActionPanel");
        panelPage0.transform.SetParent(pages[0].transform, false);
        var rtPanel0 = panelPage0.AddComponent<RectTransform>();
        rtPanel0.anchorMin = new Vector2(0, 0); rtPanel0.anchorMax = new Vector2(1, 0);
        rtPanel0.sizeDelta = new Vector2(-80, 550); rtPanel0.anchoredPosition = new Vector2(0, 300);
        var imgPanel0 = panelPage0.AddComponent<Image>(); imgPanel0.color = Color.white;
        var vlg0 = panelPage0.AddComponent<VerticalLayoutGroup>();
        vlg0.childControlWidth = true; vlg0.childControlHeight = true; vlg0.padding = new RectOffset(20, 20, 20, 20); vlg0.spacing = 15;

        GameObject titleParts = new GameObject("Title");
        titleParts.transform.SetParent(panelPage0.transform, false);
        var txtParts = titleParts.AddComponent<Text>();
        txtParts.text = "モジュールモデル合体設計"; txtParts.color = Color.gray; txtParts.fontSize = 32; txtParts.font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");

        Toggle tglEars = CreateToggle("Toggle_Ears", "耳モジュール", panelPage0.transform);
        Toggle tglTail = CreateToggle("Toggle_Tail", "尻尾モジュール", panelPage0.transform);
        Toggle tglLegs = CreateToggle("Toggle_Legs", "足モジュール", panelPage0.transform);
        Toggle tglFace = CreateToggle("Toggle_Face", "カメラディスプレイ", panelPage0.transform);
        
        Button btnPet = CreateButton("Btn_Pet", "💖 本体v1ロボとなでなで遊ぶ", panelPage0.transform);
        btnPet.GetComponent<Image>().color = new Color(1f, 0.4f, 0.5f);
        btnPet.GetComponentInChildren<Text>().color = Color.white;

        // --- Page 2 (AR) UI ---
        GameObject panelPage2 = new GameObject("ARControls");
        panelPage2.transform.SetParent(pages[2].transform, false);
        var rtPanel2 = panelPage2.AddComponent<RectTransform>();
        rtPanel2.anchorMin = new Vector2(0, 0); rtPanel2.anchorMax = new Vector2(1, 0);
        rtPanel2.sizeDelta = new Vector2(-100, 150); rtPanel2.anchoredPosition = new Vector2(0, 100);
        Button btnArReset = CreateButton("Btn_AR_Reset", "🎯 中央に再配置", panelPage2.transform);

        // === 5. 3D Environment (Home) ===
        GameObject homeEnv = new GameObject("Home3D_Environment");
        GameObject homeCamObj = new GameObject("Home_Camera");
        homeCamObj.transform.SetParent(homeEnv.transform, false);
        Camera homeCam = homeCamObj.AddComponent<Camera>();
        homeCam.clearFlags = CameraClearFlags.SolidColor;
        homeCam.backgroundColor = new Color(0.94f, 0.96f, 0.98f);
        // カメラ位置を調整
        homeCam.transform.position = new Vector3(0, 1.0f, -2.5f);
        homeCam.transform.rotation = Quaternion.Euler(10, 0, 0);

        GameObject dirLight = new GameObject("Directional Light");
        dirLight.transform.SetParent(homeEnv.transform, false);
        Light light = dirLight.AddComponent<Light>();
        light.type = LightType.Directional;
        dirLight.transform.rotation = Quaternion.Euler(50, -30, 0);

        // ロボットベースの配置 (スケールと向きを修正)
        GameObject robotBase = new GameObject("RobotBody_SetupPlaceholder");
        robotBase.transform.SetParent(homeEnv.transform, false);
        
        // ★ OBJのスケールが大きすぎる場合を考慮し 0.01 に縮小、底面を前面に向けるため -90 回転
        robotBase.transform.localScale = new Vector3(0.01f, 0.01f, 0.01f);
        robotBase.transform.rotation = Quaternion.Euler(-90, 0, 0);
        
        var aiCtrl = robotBase.AddComponent<PetRobotAIController>();
        var customizer = robotBase.AddComponent<RobotPartCustomizer>();
        
        var col = robotBase.AddComponent<BoxCollider>();
        col.size = new Vector3(150f, 150f, 150f); // 縮小前のローカルスケールに合わせる
        robotBase.AddComponent<Model3DController>();

        // AIステータステキストをUIControllerなどに紐付けるための一時処理
        // (今回はテキストは固定ですが、AIControllerから参照させたい場合はここで設定可能)

        // === 6. AR Environment (Placeholder) ===
        GameObject arEnv = new GameObject("AR_Environment_Placeholder");
        GameObject arCamObj = new GameObject("AR_Camera_Placeholder");
        arCamObj.transform.SetParent(arEnv.transform, false);
        Camera arCam = arCamObj.AddComponent<Camera>();
        arCam.clearFlags = CameraClearFlags.Color;
        arCam.backgroundColor = Color.black;

        // --- AppStateManager の紐付け ---
        SerializedObject so = new SerializedObject(stateManager);
        so.FindProperty("swipeManager").objectReferenceValue = swipeManager;
        so.FindProperty("home3DCamera").objectReferenceValue = homeCam;
        so.FindProperty("home3DEnvironment").objectReferenceValue = homeEnv;
        so.FindProperty("arCamera").objectReferenceValue = arCam;
        so.FindProperty("arSessionGroup").objectReferenceValue = arEnv;
        so.ApplyModifiedProperties();

        // --- DemoUIController の紐付け ---
        SerializedObject soUI = new SerializedObject(uiController);
        soUI.FindProperty("swipeManager").objectReferenceValue = swipeManager;
        soUI.FindProperty("aiController").objectReferenceValue = aiCtrl;
        soUI.FindProperty("partCustomizer").objectReferenceValue = customizer;
        soUI.FindProperty("btnTabHome").objectReferenceValue = btnHome;
        soUI.FindProperty("btnTabRemote").objectReferenceValue = btnRemote;
        soUI.FindProperty("btnTabAR").objectReferenceValue = btnAR;
        soUI.FindProperty("btnTabSettings").objectReferenceValue = btnSettings;
        soUI.FindProperty("btnPetAction").objectReferenceValue = btnPet;
        soUI.FindProperty("tglEars").objectReferenceValue = tglEars;
        soUI.FindProperty("tglTail").objectReferenceValue = tglTail;
        soUI.FindProperty("tglLegs").objectReferenceValue = tglLegs;
        soUI.FindProperty("tglFace").objectReferenceValue = tglFace;
        soUI.FindProperty("btnArReset").objectReferenceValue = btnArReset;
        soUI.ApplyModifiedProperties();

        // PageSwipeManager の紐付け
        SerializedObject soSwipe = new SerializedObject(swipeManager);
        soSwipe.FindProperty("scrollRect").objectReferenceValue = scrollRect;
        soSwipe.FindProperty("content").objectReferenceValue = rtContent;
        soSwipe.ApplyModifiedProperties();

        Debug.Log("✅ UIと機能の連携（デモ版機能）を自動構築しました。");
    }

    private static Button CreateButton(string name, string text, Transform parent)
    {
        GameObject obj = new GameObject(name);
        obj.transform.SetParent(parent, false);
        var img = obj.AddComponent<Image>();
        img.color = new Color(0.9f, 0.9f, 0.95f);
        var btn = obj.AddComponent<Button>();
        
        GameObject txtObj = new GameObject("Text");
        txtObj.transform.SetParent(obj.transform, false);
        var txt = txtObj.AddComponent<Text>();
        txt.text = text;
        txt.color = new Color(0.2f, 0.2f, 0.3f);
        txt.fontSize = 36;
        txt.alignment = TextAnchor.MiddleCenter;
        txt.font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
        var rtTxt = txtObj.GetComponent<RectTransform>();
        rtTxt.anchorMin = Vector2.zero; rtTxt.anchorMax = Vector2.one; rtTxt.sizeDelta = Vector2.zero;

        return btn;
    }

    private static Toggle CreateToggle(string name, string text, Transform parent)
    {
        GameObject obj = new GameObject(name);
        obj.transform.SetParent(parent, false);
        var tgl = obj.AddComponent<Toggle>();
        tgl.isOn = true;

        GameObject bgObj = new GameObject("Background");
        bgObj.transform.SetParent(obj.transform, false);
        var bgImg = bgObj.AddComponent<Image>();
        bgImg.color = new Color(0.9f, 0.9f, 0.95f);
        var rtBg = bgObj.GetComponent<RectTransform>();
        rtBg.anchorMin = new Vector2(0, 0.5f); rtBg.anchorMax = new Vector2(0, 0.5f);
        rtBg.sizeDelta = new Vector2(60, 60); rtBg.anchoredPosition = new Vector2(30, 0);

        GameObject chkObj = new GameObject("Checkmark");
        chkObj.transform.SetParent(bgObj.transform, false);
        var chkImg = chkObj.AddComponent<Image>();
        chkImg.color = new Color(0.2f, 0.5f, 0.9f);
        var rtChk = chkObj.GetComponent<RectTransform>();
        rtChk.anchorMin = new Vector2(0.2f, 0.2f); rtChk.anchorMax = new Vector2(0.8f, 0.8f); rtChk.sizeDelta = Vector2.zero;
        tgl.graphic = chkImg;

        GameObject txtObj = new GameObject("Label");
        txtObj.transform.SetParent(obj.transform, false);
        var txt = txtObj.AddComponent<Text>();
        txt.text = text;
        txt.color = new Color(0.2f, 0.2f, 0.3f);
        txt.fontSize = 36;
        txt.alignment = TextAnchor.MiddleLeft;
        txt.font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
        var rtTxt = txtObj.GetComponent<RectTransform>();
        rtTxt.anchorMin = new Vector2(0, 0); rtTxt.anchorMax = new Vector2(1, 1);
        rtTxt.offsetMin = new Vector2(120, 0); rtTxt.offsetMax = Vector2.zero;

        return tgl;
    }
}
