using System.Collections.Generic;
using UnityEngine;

namespace Aidy
{
    /// <summary>
    /// 本体 (顔あり/顔なし) + ModuleConfig のモジュールを 3D で組み立てる。ホーム・付け替えプレビュー・AR で共通。
    /// 寸法は docs/本体モデルv1解析.txt: 本体 150mm 角 = size (シーン単位)。
    /// editable のときは、面の選択ハイライトとスロットの目印 (ドロップ先) も作る。
    /// </summary>
    public class AidyModuleRig
    {
        const string EarModel = "AIDY/耳_stl";
        const string HandModel = "AIDY/手_短い_3mf";
        const string HandLightModel = "AIDY/手_短い_懐中電灯_3mf";

        public readonly Transform Body;
        public readonly AidyModel.Variants Bodies;
        readonly float size, mm, k;
        readonly int layer;
        readonly bool editable;
        readonly Material satin;

        class Mount
        {
            public ModuleKind Kind;
            public GameObject Root;
            public readonly List<Transform> Swing = new List<Transform>();
        }

        public class SlotFrame
        {
            public BodySlot Slot;
            public Transform T;
            public GameObject Marker;
            public Material MarkerMat;
        }

        readonly Dictionary<string, Mount> mounts = new Dictionary<string, Mount>();
        readonly Dictionary<string, SlotFrame> frames = new Dictionary<string, SlotFrame>();
        GameObject highlight;
        string selectedFace;
        ModuleKind? dropKind;
        string dropHover;

        public AidyModuleRig(Transform body, float size, int layer, bool editable)
        {
            Body = body;
            this.size = size;
            this.layer = layer;
            this.editable = editable;
            mm = size / 150f;
            k = size / 1.1f; // 足・尻尾はホームの本体 (1.1) を基準にした寸法
            satin = AidyMaterials.Standard(new Color(0.627451f, 0.627451f, 0.627451f), 0.55f, 0.35f);
            Bodies = new AidyModel.Variants(body, size, layer);
            BuildFrames();
            Sync();
        }

        public bool HasModel => Bodies.Any;
        public bool FaceOn { get; private set; }
        public IEnumerable<SlotFrame> Frames => frames.Values;

        /// <summary>足の先が本体の底面からどれだけ下に出ているか。</summary>
        public float LegReach => HasLegs ? 0.24f * k : 0f;

        public bool HasLegs
        {
            get
            {
                foreach (Mount m in mounts.Values) if (m.Kind == ModuleKind.Legs) return true;
                return false;
            }
        }

        /// <summary>ModuleConfig に合わせてモジュールを作り直す。</summary>
        public void Sync()
        {
            IReadOnlyDictionary<string, ModuleKind> want = ModuleConfig.Slots;
            foreach (string id in new List<string>(mounts.Keys))
            {
                if (want.TryGetValue(id, out ModuleKind kind) && kind == mounts[id].Kind) continue;
                Object.Destroy(mounts[id].Root);
                mounts.Remove(id);
            }
            foreach (var kv in want)
            {
                if (mounts.ContainsKey(kv.Key) || !frames.TryGetValue(kv.Key, out SlotFrame f)) continue;
                var root = new GameObject(kv.Value.ToString());
                root.transform.SetParent(f.T, false);
                var m = new Mount { Kind = kv.Value, Root = root };
                BuildModule(m);
                Three.SetLayer(root, layer);
                mounts[kv.Key] = m;
            }
            FaceOn = ModuleConfig.FaceOn;
            Bodies.SetFace(FaceOn);
            UpdateMarkers();
        }

        // ------------------------------------------------------------ モジュールの形 (取り付け面ローカル: +Y=外向き, +Z=正面 or 上)

        void BuildModule(Mount m)
        {
            Transform f = m.Root.transform;
            switch (m.Kind)
            {
                case ModuleKind.Hand:
                case ModuleKind.HandLight:
                {
                    // 手: マグネットのくぼみを本体に当て、本体の側面に沿って正面へ伸びる。くぼみを軸に上下に振る
                    var pivot = new GameObject("HandPivot").transform;
                    pivot.SetParent(f, false);
                    Mesh hand = AidyModelLoader.Load(m.Kind == ModuleKind.Hand ? HandModel : HandLightModel, MountAxis.MagnetPlusX);
                    if (hand != null)
                    {
                        var go = AidyMeshes.Create("Hand", pivot, hand, satin, true, true);
                        go.transform.localScale = Vector3.one * mm;
                        if (m.Kind == ModuleKind.HandLight)
                        {
                            // 懐中電灯のレンズ (長手方向の後ろ端) を光らせる
                            Bounds b = hand.bounds;
                            var lens = AidyMeshes.Create("Lens", pivot, AidyMeshes.Circle(5.5f * mm, 24), AidyMaterials.Basic(Theme.Hex(0xfef9c3)));
                            lens.transform.localPosition = new Vector3(b.center.x, b.center.y, b.min.z - 0.3f) * mm;
                            lens.transform.localRotation = Quaternion.AngleAxis(180f, Vector3.up);
                        }
                    }
                    else
                    {
                        var go = AidyMeshes.Create("Hand", pivot, AidyMeshes.Cylinder(15f * mm, 15f * mm, 80f * mm, 16), satin, true, true);
                        go.transform.localPosition = new Vector3(0, 15f * mm, 25f * mm);
                        go.transform.localRotation = Quaternion.AngleAxis(90f, Vector3.right);
                    }
                    m.Swing.Add(pivot);
                    break;
                }
                case ModuleKind.Ear:
                {
                    Mesh ear = AidyModelLoader.Load(EarModel, MountAxis.ZUpFrontMinusY);
                    if (ear != null)
                    {
                        var go = AidyMeshes.Create("Ear", f, ear, satin, true, true);
                        go.transform.localScale = Vector3.one * mm;
                    }
                    else
                    {
                        var go = AidyMeshes.Create("Ear", f, AidyMeshes.Cylinder(0.08f * k, 0.14f * k, 0.3f * k, 16), satin, true, true);
                        go.transform.localPosition = new Vector3(0, 0.15f * k, 0);
                    }
                    break;
                }
                case ModuleKind.Tail:
                {
                    // 根元で左右に振る (wag) → 少し持ち上げる (tilt) → 棒
                    var wag = new GameObject("TailWag").transform;
                    wag.SetParent(f, false);
                    var tilt = new GameObject("TailTilt").transform;
                    tilt.SetParent(wag, false);
                    tilt.localRotation = Quaternion.AngleAxis(35f, Vector3.right);
                    var go = AidyMeshes.Create("Tail", tilt, AidyMeshes.Cylinder(0.03f * k, 0.04f * k, 0.45f * k, 16), satin, true, true);
                    go.transform.localPosition = new Vector3(0, 0.2f * k, 0);
                    m.Swing.Add(wag);
                    break;
                }
                case ModuleKind.Legs:
                {
                    // 1 スロットに前後 2 本。根元を pivot にして前後に振る
                    var mesh = AidyMeshes.Cylinder(0.08f * k, 0.1f * k, 0.28f * k, 16);
                    foreach (float z in new[] { 0.35f, -0.35f })
                    {
                        var pivot = new GameObject("LegPivot").transform;
                        pivot.SetParent(f, false);
                        pivot.localPosition = new Vector3(0, 0, z * k);
                        var go = AidyMeshes.Create("Leg", pivot, mesh, satin, true, true);
                        go.transform.localPosition = new Vector3(0, 0.1f * k, 0);
                        m.Swing.Add(pivot);
                    }
                    break;
                }
            }
        }

        // ------------------------------------------------------------ アニメーション

        public void SwingLegs(float amount, float phase)
        {
            int i = 0;
            foreach (Mount m in mounts.Values)
            {
                if (m.Kind != ModuleKind.Legs) continue;
                foreach (Transform leg in m.Swing)
                {
                    float a = Mathf.Sin(phase + (i % 2 == 0 ? 0 : Mathf.PI)) * amount;
                    leg.localRotation = Quaternion.AngleAxis(a * Mathf.Rad2Deg, Vector3.right);
                    i++;
                }
                i++; // 隣のスロットとは逆位相
            }
        }

        /// <summary>手を上下に振る (取り付け面の法線まわり = 腕の先が上下に動く)。</summary>
        public void WaveHands(float angle)
        {
            foreach (Mount m in mounts.Values)
                if (m.Kind == ModuleKind.Hand || m.Kind == ModuleKind.HandLight)
                    foreach (Transform h in m.Swing) h.localRotation = Quaternion.AngleAxis(angle * Mathf.Rad2Deg, Vector3.up);
        }

        public void WagTails(float angle)
        {
            foreach (Mount m in mounts.Values)
                if (m.Kind == ModuleKind.Tail)
                    foreach (Transform w in m.Swing) w.localRotation = Quaternion.AngleAxis(angle * Mathf.Rad2Deg, Vector3.forward);
        }

        // ------------------------------------------------------------ スロット (取り付け面の座標系) と目印

        void BuildFrames()
        {
            Mesh disc = editable ? AidyMeshes.Circle(0.065f * k, 32) : null;
            foreach (BodySlot s in BodyLayout.Slots)
            {
                var t = new GameObject("Slot_" + s.Id).transform;
                t.gameObject.layer = layer;
                t.SetParent(Body, false);
                t.localPosition = s.Position * size;
                t.localRotation = s.MountRotation;
                var fr = new SlotFrame { Slot = s, T = t };
                if (editable)
                {
                    fr.MarkerMat = AidyMaterials.Basic(Color.white);
                    fr.Marker = AidyMeshes.Create("Marker", t, disc, fr.MarkerMat);
                    fr.Marker.transform.localPosition = new Vector3(0, (s.IsMonitor ? 0.012f : 0.006f) * k, 0);
                    fr.Marker.transform.localRotation = Quaternion.AngleAxis(-90f, Vector3.right); // 円 (+Z 向き) を外向き (+Y) に
                    fr.Marker.SetActive(false);
                }
                frames[s.Id] = fr;
            }
            if (editable)
            {
                highlight = AidyMeshes.Create("FaceHighlight", Body, AidyMeshes.Plane(size * 0.97f, size * 0.97f), AidyMaterials.Basic(Theme.Hex(0x3b82f6, 0.22f)));
                highlight.SetActive(false);
            }
        }

        public void SetSelectedFace(string face)
        {
            selectedFace = face;
            if (highlight != null)
            {
                highlight.SetActive(face != null);
                if (face != null)
                {
                    Vector3 n = BodyLayout.FaceNormal(face);
                    highlight.transform.localPosition = n * (size * 0.5f + 0.004f * k);
                    highlight.transform.localRotation = Quaternion.LookRotation(n, Mathf.Abs(n.y) > 0.5f ? Vector3.forward : Vector3.up);
                }
            }
            UpdateMarkers();
        }

        public void SetDrop(ModuleKind? kind, string hover)
        {
            dropKind = kind;
            dropHover = hover;
            UpdateMarkers();
        }

        void UpdateMarkers()
        {
            if (!editable) return;
            foreach (SlotFrame fr in frames.Values)
            {
                bool onFace = selectedFace != null && fr.Slot.Face == selectedFace;
                bool occupied = mounts.ContainsKey(fr.Slot.Id);
                bool candidate = dropKind.HasValue && ModuleCatalog.Fits(dropKind.Value, fr.Slot);
                bool hovered = candidate && fr.Slot.Id == dropHover;
                // 空きスロットは常に、取り付け済みは差し替え候補のときだけ目印を出す
                bool show = onFace && (!occupied || candidate);
                fr.Marker.SetActive(show);
                if (!show) continue;
                fr.MarkerMat.SetColor("_Color", hovered ? Theme.Hex(0xec4899) : candidate ? Theme.Primary : Color.white);
                fr.Marker.transform.localScale = Vector3.one * (hovered ? 1.5f : candidate ? 1.15f : 0.8f);
            }
        }
    }
}
