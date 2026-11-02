using System;
using System.Collections.Generic;

namespace Aidy
{
    /// <summary>見つかったマーカー。Corners は画像の画素座標 (x, y 下向き、画素の中心が整数) で、マーカーの左上・右上・右下・左下の順。</summary>
    public class ArucoMarker
    {
        public int Id;
        public double[] Corners = new double[8];
    }

    /// <summary>
    /// ArUco マーカー (OpenCV の DICT_4X4_50 と同じ) の検出。UnityEngine を使わない純 C# なので別スレッドで回せる。
    /// 適応的二値化 → 暗い領域の連結成分 → 凸包から四角形 → 6x6 マスを読んで辞書と照合 → 辺の直線当てはめで角をサブピクセルに。
    /// </summary>
    public class ArucoDetector
    {
        /// <summary>DICT_4X4_50。内側 4x4 ビットを左上から行ごとに上位ビットから並べた値 (1 = 白)。</summary>
        public static readonly ushort[] Dict4x4_50 =
        {
            0xB532, 0x0F9A, 0x332D, 0x9946, 0x549E, 0x79CD, 0x9E2E, 0xC4F2, 0xFEDA, 0xCF56, 0xF991, 0x11A7, 0x0EB7, 0x2A0F, 0x24B1, 0x263E, 0x4665,
            0x6600, 0x6C5E, 0x76AF, 0x868B, 0xB02B, 0xCCD5, 0xDD82, 0xFE47, 0x9471, 0xACE4, 0xA554, 0x2123, 0x346F, 0x4415, 0x57B2, 0x9ECF, 0xF0CB,
            0x08AE, 0x0929, 0x1875, 0x04FF, 0x0DF6, 0x1C5A, 0x1718, 0x2A28, 0x328C, 0x38B2, 0x24E8, 0x2EEB, 0x2D3F, 0x4B64, 0x502E, 0x5013
        };

        const int MaxCorrectionBits = 1; // DICT_4X4_50 の訂正可能ビット数
        const int ThresholdC = 7;        // 周囲の平均よりこれだけ暗ければ黒
        const int MinSidePx = 14;        // これより小さい四角形は読まない

        byte[] gray;
        int w, h;
        int[] integral, labels, parent;
        bool[] dark;

        /// <summary>gray: 1 画素 1 バイトの輝度 (上の行から)。acceptIds が null なら辞書の全 ID を返す。</summary>
        public List<ArucoMarker> Detect(byte[] gray, int width, int height, ICollection<int> acceptIds = null)
        {
            this.gray = gray;
            w = width;
            h = height;
            var found = new List<ArucoMarker>();
            if (w < 16 || h < 16) return found;
            // 二値化の窓はマーカーのマスより大きくないと、大きく写ったマーカーの黒枠が途切れる。
            // 大きすぎると周りの余白とつながる。小さめの窓 (画像の短辺の 1/16) から始め、何も見つからなければ窓を 2 倍にしていく
            int r = Math.Max(5, Math.Min(w, h) / 32);
            foreach (int scale in new[] { 1, 2, 4 })
            {
                DetectPass(r * scale, acceptIds, found);
                if (found.Count > 0) break;
            }
            return found;
        }

        void DetectPass(int r, ICollection<int> acceptIds, List<ArucoMarker> found)
        {
            Threshold(r);
            int count = Label(out int[] minX, out int[] minY, out int[] maxX, out int[] maxY, out int[] area);
            int maxSide = (int)(Math.Min(w, h) * 0.98);
            for (int l = 1; l < count; l++)
            {
                int bw = maxX[l] - minX[l] + 1, bh = maxY[l] - minY[l] + 1;
                if (bw < MinSidePx || bh < MinSidePx || bw > maxSide && bh > maxSide) continue;
                if (area[l] < MinSidePx * 4) continue;
                if (minX[l] == 0 || minY[l] == 0 || maxX[l] == w - 1 || maxY[l] == h - 1) continue; // 画面の端で切れている
                double[] quad = Quad(l, minX[l], minY[l], maxX[l], maxY[l]);
                if (quad == null) continue;
                if (!Decode(quad, out int id, out int rotation)) continue;
                if (acceptIds != null && !acceptIds.Contains(id)) continue;
                var m = new ArucoMarker { Id = id };
                // 読み取ったときの向きから、マーカー本来の左上を先頭にする
                for (int i = 0; i < 4; i++)
                {
                    int src = (i + rotation) % 4;
                    m.Corners[i * 2] = quad[src * 2];
                    m.Corners[i * 2 + 1] = quad[src * 2 + 1];
                }
                Refine(m.Corners);
                for (int i = 0; i < 8; i++) m.Corners[i] -= 0.5; // OpenCV / カメラ内部パラメータと同じく画素の中心を整数に
                // 同じ ID が重なって見つかったら大きい方を残す
                int dup = found.FindIndex(o => o.Id == id && Overlaps(o.Corners, m.Corners));
                if (dup < 0) found.Add(m);
                else if (Perimeter(m.Corners) > Perimeter(found[dup].Corners)) found[dup] = m;
            }
        }

        // ------------------------------------------------------------ 二値化 (積分画像で窓 (半径 r) の平均と比べる)

        void Threshold(int r)
        {
            int n = w * h;
            if (integral == null || integral.Length < (w + 1) * (h + 1)) integral = new int[(w + 1) * (h + 1)];
            if (dark == null || dark.Length < n) dark = new bool[n];
            int W = w + 1;
            for (int y = 0; y < h; y++)
            {
                int row = 0;
                for (int x = 0; x < w; x++)
                {
                    row += gray[y * w + x];
                    integral[(y + 1) * W + x + 1] = integral[y * W + x + 1] + row;
                }
            }
            for (int y = 0; y < h; y++)
            {
                int y0 = Math.Max(0, y - r), y1 = Math.Min(h, y + r + 1);
                for (int x = 0; x < w; x++)
                {
                    int x0 = Math.Max(0, x - r), x1 = Math.Min(w, x + r + 1);
                    int sum = integral[y1 * W + x1] - integral[y0 * W + x1] - integral[y1 * W + x0] + integral[y0 * W + x0];
                    int cnt = (x1 - x0) * (y1 - y0);
                    dark[y * w + x] = gray[y * w + x] * cnt < sum - ThresholdC * cnt;
                }
            }
        }

        // ------------------------------------------------------------ 連結成分 (8 近傍、Union-Find)

        int Find(int a)
        {
            while (parent[a] != a) a = parent[a] = parent[parent[a]];
            return a;
        }

        int Label(out int[] minX, out int[] minY, out int[] maxX, out int[] maxY, out int[] area)
        {
            int n = w * h;
            if (labels == null || labels.Length < n) labels = new int[n];
            if (parent == null || parent.Length < n / 2 + 2) parent = new int[n / 2 + 2];
            int next = 1;
            parent[0] = 0;
            for (int y = 0; y < h; y++)
            {
                for (int x = 0; x < w; x++)
                {
                    int i = y * w + x;
                    if (!dark[i])
                    {
                        labels[i] = 0;
                        continue;
                    }
                    int best = 0;
                    // 左・左上・上・右上
                    int a = x > 0 ? labels[i - 1] : 0;
                    int b = y > 0 && x > 0 ? labels[i - w - 1] : 0;
                    int c = y > 0 ? labels[i - w] : 0;
                    int d = y > 0 && x < w - 1 ? labels[i - w + 1] : 0;
                    foreach (int v in new[] { a, b, c, d })
                    {
                        if (v == 0) continue;
                        if (best == 0) best = Find(v);
                        else
                        {
                            int rv = Find(v);
                            if (rv != best)
                            {
                                if (rv < best) { parent[best] = rv; best = rv; }
                                else parent[rv] = best;
                            }
                        }
                    }
                    if (best == 0)
                    {
                        if (next >= parent.Length) Array.Resize(ref parent, parent.Length * 2);
                        parent[next] = next;
                        best = next++;
                    }
                    labels[i] = best;
                }
            }
            // ラベルを代表に詰め直し、外接矩形と画素数を集計
            var remap = new int[next];
            int count = 1;
            for (int l = 1; l < next; l++)
            {
                int root = Find(l);
                if (root == l) remap[l] = count++;
            }
            for (int l = 1; l < next; l++) remap[l] = remap[Find(l)];
            minX = new int[count];
            minY = new int[count];
            maxX = new int[count];
            maxY = new int[count];
            area = new int[count];
            for (int l = 0; l < count; l++)
            {
                minX[l] = minY[l] = int.MaxValue;
                maxX[l] = maxY[l] = -1;
            }
            for (int y = 0; y < h; y++)
            {
                for (int x = 0; x < w; x++)
                {
                    int i = y * w + x;
                    int l = labels[i];
                    if (l == 0) continue;
                    l = labels[i] = remap[l];
                    area[l]++;
                    if (x < minX[l]) minX[l] = x;
                    if (x > maxX[l]) maxX[l] = x;
                    if (y < minY[l]) minY[l] = y;
                    if (y > maxY[l]) maxY[l] = y;
                }
            }
            return count;
        }

        // ------------------------------------------------------------ 四角形 (凸包 → 4 頂点)

        double[] Quad(int l, int x0, int y0, int x1, int y1)
        {
            // 凸包は各行の左端・右端だけから作れる
            var pts = new List<(double x, double y)>();
            for (int y = y0; y <= y1; y++)
            {
                int lo = -1, hi = -1;
                for (int x = x0; x <= x1; x++)
                {
                    if (labels[y * w + x] != l) continue;
                    if (lo < 0) lo = x;
                    hi = x;
                }
                if (lo < 0) continue;
                pts.Add((lo + 0.5, y + 0.5));
                if (hi != lo) pts.Add((hi + 0.5, y + 0.5));
            }
            List<(double x, double y)> hull = Hull(pts);
            if (hull.Count < 4) return null;
            double hullArea = Math.Abs(Area(hull));

            // 最も離れた 2 点 (対角) と、その対角線から両側に最も離れた点
            int ia = 0, ic = 0;
            double best = -1;
            for (int i = 0; i < hull.Count; i++)
                for (int j = i + 1; j < hull.Count; j++)
                {
                    double d = Sq(hull[i].x - hull[j].x) + Sq(hull[i].y - hull[j].y);
                    if (d > best) { best = d; ia = i; ic = j; }
                }
            int ib = -1, id = -1;
            double bestB = 0, bestD = 0;
            for (int i = 0; i < hull.Count; i++)
            {
                double s = Cross(hull[ia], hull[ic], hull[i]);
                if (s > bestB) { bestB = s; ib = i; }
                if (s < bestD) { bestD = s; id = i; }
            }
            if (ib < 0 || id < 0) return null;
            var q = new List<(double x, double y)> { hull[ia], hull[ib], hull[ic], hull[id] };
            double quadArea = Area(q);
            if (quadArea < 0) // 画像座標 (y 下向き) で時計回りにそろえる
            {
                q.Reverse();
                quadArea = -quadArea;
            }
            if (quadArea < hullArea * 0.88) return null; // 四角形らしくない
            var res = new double[8];
            for (int i = 0; i < 4; i++)
            {
                res[i * 2] = q[i].x;
                res[i * 2 + 1] = q[i].y;
                double ex = q[(i + 1) % 4].x - q[i].x, ey = q[(i + 1) % 4].y - q[i].y;
                if (ex * ex + ey * ey < MinSidePx * MinSidePx * 0.5) return null;
            }
            return res;
        }

        static List<(double x, double y)> Hull(List<(double x, double y)> p)
        {
            p.Sort((a, b) => a.x != b.x ? a.x.CompareTo(b.x) : a.y.CompareTo(b.y));
            var hull = new List<(double x, double y)>();
            for (int pass = 0; pass < 2; pass++)
            {
                int start = hull.Count;
                for (int k = 0; k < p.Count; k++)
                {
                    var pt = pass == 0 ? p[k] : p[p.Count - 1 - k];
                    while (hull.Count >= start + 2 && Cross(hull[hull.Count - 2], hull[hull.Count - 1], pt) <= 0) hull.RemoveAt(hull.Count - 1);
                    hull.Add(pt);
                }
                hull.RemoveAt(hull.Count - 1);
            }
            return hull;
        }

        // ------------------------------------------------------------ 読み取り

        /// <summary>
        /// 四角形 (時計回り、q[0] を仮の左上) の 6x6 マスを読む。外周が黒で、内側 4x4 が辞書と一致すれば ID と、
        /// 本来の左上が q の何番目か (rotation) を返す。
        /// </summary>
        bool Decode(double[] q, out int id, out int rotation)
        {
            id = -1;
            rotation = 0;
            double[] hm = Homography(new double[] { 0, 0, 6, 0, 6, 6, 0, 6 }, q);
            if (hm == null) return false;
            var cell = new double[36];
            double lo = 255, hi = 0;
            for (int cy = 0; cy < 6; cy++)
            {
                for (int cx = 0; cx < 6; cx++)
                {
                    double sum = 0;
                    for (int sy = 0; sy < 3; sy++)
                        for (int sx = 0; sx < 3; sx++)
                        {
                            Apply(hm, cx + 0.3 + sx * 0.2, cy + 0.3 + sy * 0.2, out double px, out double py);
                            sum += Sample(px, py);
                        }
                    double v = sum / 9.0;
                    cell[cy * 6 + cx] = v;
                    lo = Math.Min(lo, v);
                    hi = Math.Max(hi, v);
                }
            }
            if (hi - lo < 25) return false;
            double thr = (lo + hi) * 0.5;
            int borderWhite = 0;
            for (int i = 0; i < 6; i++)
            {
                if (cell[i] > thr) borderWhite++;
                if (cell[30 + i] > thr) borderWhite++;
                if (i > 0 && i < 5)
                {
                    if (cell[i * 6] > thr) borderWhite++;
                    if (cell[i * 6 + 5] > thr) borderWhite++;
                }
            }
            if (borderWhite > 1) return false;

            var bits = new int[16];
            for (int r = 0; r < 4; r++)
                for (int c = 0; c < 4; c++)
                    bits[r * 4 + c] = cell[(r + 1) * 6 + c + 1] > thr ? 1 : 0;

            // k 回 時計回りに回した読みが辞書と一致したら、本来の左上は q[k] … を下の対応で求める
            for (int k = 0; k < 4; k++)
            {
                int code = 0;
                for (int i = 0; i < 16; i++) code = (code << 1) | bits[i];
                for (int d = 0; d < Dict4x4_50.Length; d++)
                {
                    if (PopCount(code ^ Dict4x4_50[d]) > MaxCorrectionBits) continue;
                    id = d;
                    rotation = (4 - k) % 4;
                    return true;
                }
                bits = RotateCW(bits);
            }
            return false;
        }

        static int[] RotateCW(int[] b)
        {
            var o = new int[16];
            for (int r = 0; r < 4; r++)
                for (int c = 0; c < 4; c++)
                    o[r * 4 + c] = b[(3 - c) * 4 + r];
            return o;
        }

        static int PopCount(int v)
        {
            int n = 0;
            for (; v != 0; v &= v - 1) n++;
            return n;
        }

        // ------------------------------------------------------------ 角のサブピクセル化 (各辺の明暗の境目に直線を当てはめて交点)

        void Refine(double[] c)
        {
            var lines = new double[4][];
            for (int e = 0; e < 4; e++)
            {
                double ax = c[e * 2], ay = c[e * 2 + 1], bx = c[(e + 1) % 4 * 2], by = c[(e + 1) % 4 * 2 + 1];
                double dx = bx - ax, dy = by - ay, len = Math.Sqrt(dx * dx + dy * dy);
                if (len < 1) return;
                dx /= len;
                dy /= len;
                // 時計回りなので (dy, -dx) が外向き
                double nx = dy, ny = -dx;
                double reach = Math.Max(2.0, Math.Min(5.0, len / 12.0));
                var px = new List<double>();
                var py = new List<double>();
                for (int s = 1; s <= 11; s++)
                {
                    double t = 0.1 + 0.8 * s / 12.0;
                    double mx = ax + (bx - ax) * t, my = ay + (by - ay) * t;
                    // 内側 (黒) → 外側 (白) へ輝度を調べ、中間値を横切る位置
                    const int Steps = 16;
                    var prof = new double[Steps + 1];
                    double pmin = 255, pmax = 0;
                    for (int k = 0; k <= Steps; k++)
                    {
                        double o = -reach + 2 * reach * k / Steps;
                        prof[k] = Sample(mx + nx * o, my + ny * o);
                        pmin = Math.Min(pmin, prof[k]);
                        pmax = Math.Max(pmax, prof[k]);
                    }
                    if (pmax - pmin < 20) continue;
                    double mid = (pmin + pmax) * 0.5;
                    for (int k = 0; k < Steps; k++)
                    {
                        if (prof[k] > mid || prof[k + 1] <= mid) continue;
                        double f = (mid - prof[k]) / (prof[k + 1] - prof[k]);
                        double o = -reach + 2 * reach * (k + f) / Steps;
                        px.Add(mx + nx * o);
                        py.Add(my + ny * o);
                        break;
                    }
                }
                if (px.Count < 4) return; // 当てはめられない辺があればそのまま
                lines[e] = FitLine(px, py);
            }
            var outC = new double[8];
            for (int i = 0; i < 4; i++)
            {
                // 角 i は 辺 (i-1) と 辺 i の交点
                double[] l1 = lines[(i + 3) % 4], l2 = lines[i];
                double det = l1[0] * l2[1] - l1[1] * l2[0];
                if (Math.Abs(det) < 1e-9) return;
                double x = (l1[2] * l2[1] - l1[1] * l2[2]) / det, y = (l1[0] * l2[2] - l1[2] * l2[0]) / det;
                if (Sq(x - c[i * 2]) + Sq(y - c[i * 2 + 1]) > 9.0) return; // 大きく動くなら当てはめ失敗
                outC[i * 2] = x;
                outC[i * 2 + 1] = y;
            }
            Array.Copy(outC, c, 8);
        }

        /// <summary>主成分で直線 a x + b y = c を当てはめる。</summary>
        static double[] FitLine(List<double> xs, List<double> ys)
        {
            double mx = 0, my = 0;
            for (int i = 0; i < xs.Count; i++)
            {
                mx += xs[i];
                my += ys[i];
            }
            mx /= xs.Count;
            my /= xs.Count;
            double sxx = 0, sxy = 0, syy = 0;
            for (int i = 0; i < xs.Count; i++)
            {
                double dx = xs[i] - mx, dy = ys[i] - my;
                sxx += dx * dx;
                sxy += dx * dy;
                syy += dy * dy;
            }
            double angle = 0.5 * Math.Atan2(2 * sxy, sxx - syy); // 直線の向き
            double a = -Math.Sin(angle), b = Math.Cos(angle);
            return new[] { a, b, a * mx + b * my };
        }

        // ------------------------------------------------------------ 小道具

        double Sample(double x, double y)
        {
            x -= 0.5;
            y -= 0.5;
            if (x < 0) x = 0;
            if (y < 0) y = 0;
            if (x > w - 1.001) x = w - 1.001;
            if (y > h - 1.001) y = h - 1.001;
            int ix = (int)x, iy = (int)y;
            double fx = x - ix, fy = y - iy;
            int i = iy * w + ix;
            double top = gray[i] + (gray[i + 1] - gray[i]) * fx;
            double bottom = gray[i + w] + (gray[i + w + 1] - gray[i + w]) * fx;
            return top + (bottom - top) * fy;
        }

        static double Sq(double v) => v * v;

        static double Cross((double x, double y) o, (double x, double y) a, (double x, double y) b) => (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);

        static double Area(List<(double x, double y)> p)
        {
            double s = 0;
            for (int i = 0; i < p.Count; i++)
            {
                var a = p[i];
                var b = p[(i + 1) % p.Count];
                s += a.x * b.y - b.x * a.y;
            }
            return s * 0.5;
        }

        static double Perimeter(double[] c)
        {
            double s = 0;
            for (int i = 0; i < 4; i++) s += Math.Sqrt(Sq(c[(i + 1) % 4 * 2] - c[i * 2]) + Sq(c[(i + 1) % 4 * 2 + 1] - c[i * 2 + 1]));
            return s;
        }

        static bool Overlaps(double[] a, double[] b)
        {
            double ax = (a[0] + a[2] + a[4] + a[6]) / 4, ay = (a[1] + a[3] + a[5] + a[7]) / 4;
            double bx = (b[0] + b[2] + b[4] + b[6]) / 4, by = (b[1] + b[3] + b[5] + b[7]) / 4;
            return Sq(ax - bx) + Sq(ay - by) < Sq(Math.Max(Perimeter(a), Perimeter(b)) / 8);
        }

        /// <summary>4 点の対応 (src → dst、各 x,y の 8 要素) から射影変換 (3x3、h33 = 1) を求める。</summary>
        public static double[] Homography(double[] src, double[] dst)
        {
            var a = new double[8, 9];
            for (int i = 0; i < 4; i++)
            {
                double x = src[i * 2], y = src[i * 2 + 1], u = dst[i * 2], v = dst[i * 2 + 1];
                int r = i * 2;
                a[r, 0] = x; a[r, 1] = y; a[r, 2] = 1; a[r, 6] = -u * x; a[r, 7] = -u * y; a[r, 8] = u;
                a[r + 1, 3] = x; a[r + 1, 4] = y; a[r + 1, 5] = 1; a[r + 1, 6] = -v * x; a[r + 1, 7] = -v * y; a[r + 1, 8] = v;
            }
            double[] s = Solve(a, 8);
            if (s == null) return null;
            return new[] { s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], 1.0 };
        }

        public static void Apply(double[] hm, double x, double y, out double u, out double v)
        {
            double z = hm[6] * x + hm[7] * y + hm[8];
            u = (hm[0] * x + hm[1] * y + hm[2]) / z;
            v = (hm[3] * x + hm[4] * y + hm[5]) / z;
        }

        /// <summary>拡大係数行列 (n x n+1) をガウスの消去法で解く。</summary>
        public static double[] Solve(double[,] a, int n)
        {
            for (int col = 0; col < n; col++)
            {
                int piv = col;
                for (int r = col + 1; r < n; r++)
                    if (Math.Abs(a[r, col]) > Math.Abs(a[piv, col])) piv = r;
                if (Math.Abs(a[piv, col]) < 1e-12) return null;
                if (piv != col)
                    for (int k = col; k <= n; k++) (a[col, k], a[piv, k]) = (a[piv, k], a[col, k]);
                for (int r = 0; r < n; r++)
                {
                    if (r == col) continue;
                    double f = a[r, col] / a[col, col];
                    if (f == 0) continue;
                    for (int k = col; k <= n; k++) a[r, k] -= f * a[col, k];
                }
            }
            var x = new double[n];
            for (int i = 0; i < n; i++) x[i] = a[i, n] / a[i, i];
            return x;
        }
    }

    /// <summary>
    /// 正方形マーカーの姿勢 (OpenCV のカメラ座標: x 右, y 下, z 前方)。マーカー座標は中心が原点、x 右・y 上・z 手前 (カメラ側)。
    /// 射影変換の分解で初期値を作り、再投影誤差をガウス・ニュートン法で詰める。
    /// </summary>
    public static class ArucoPose
    {
        /// <summary>R: 3x3 (行優先、列 = マーカーの x,y,z 軸)。t: マーカー中心の位置 (size と同じ単位)。</summary>
        public static bool Solve(double[] corners, double size, double fx, double fy, double cx, double cy, out double[] R, out double[] t)
        {
            R = null;
            t = null;
            double s = size * 0.5;
            var obj = new[] { -s, s, s, s, s, -s, -s, -s };
            var img = new double[8];
            for (int i = 0; i < 4; i++)
            {
                img[i * 2] = (corners[i * 2] - cx) / fx;
                img[i * 2 + 1] = (corners[i * 2 + 1] - cy) / fy;
            }
            double[] hm = ArucoDetector.Homography(obj, img);
            if (hm == null) return false;
            // H = λ [r1 r2 t]
            double n1 = Math.Sqrt(hm[0] * hm[0] + hm[3] * hm[3] + hm[6] * hm[6]);
            double n2 = Math.Sqrt(hm[1] * hm[1] + hm[4] * hm[4] + hm[7] * hm[7]);
            double lambda = 2.0 / (n1 + n2);
            if (hm[8] * lambda < 0) lambda = -lambda; // マーカーはカメラの前 (z > 0)
            double[] r1 = { hm[0] * lambda, hm[3] * lambda, hm[6] * lambda };
            double[] r2 = { hm[1] * lambda, hm[4] * lambda, hm[7] * lambda };
            t = new[] { hm[2] * lambda, hm[5] * lambda, hm[8] * lambda };
            R = Orthonormal(r1, r2);

            // 回転ベクトル + 並進の 6 変数でガウス・ニュートン
            double[] p = { 0, 0, 0, t[0], t[1], t[2] };
            double[] R0 = R;
            for (int iter = 0; iter < 8; iter++)
            {
                double[] res = Residual(p, R0, obj, img);
                var jt = new double[6, 8];
                for (int k = 0; k < 6; k++)
                {
                    var q = (double[])p.Clone();
                    double eps = k < 3 ? 1e-6 : 1e-6 * Math.Max(1e-3, Math.Abs(p[5]));
                    q[k] += eps;
                    double[] r2_ = Residual(q, R0, obj, img);
                    for (int m = 0; m < 8; m++) jt[k, m] = (r2_[m] - res[m]) / eps;
                }
                var a = new double[6, 7];
                for (int i = 0; i < 6; i++)
                {
                    for (int j = 0; j < 6; j++)
                    {
                        double sum = 0;
                        for (int m = 0; m < 8; m++) sum += jt[i, m] * jt[j, m];
                        a[i, j] = sum + (i == j ? 1e-9 : 0);
                    }
                    double g = 0;
                    for (int m = 0; m < 8; m++) g += jt[i, m] * res[m];
                    a[i, 6] = -g;
                }
                double[] step = ArucoDetector.Solve(a, 6);
                if (step == null) break;
                for (int k = 0; k < 6; k++) p[k] += step[k];
                if (Math.Abs(step[0]) + Math.Abs(step[1]) + Math.Abs(step[2]) < 1e-9) break;
            }
            R = Mul(Rodrigues(p[0], p[1], p[2]), R0);
            t = new[] { p[3], p[4], p[5] };
            return t[2] > 0;
        }

        static double[] Residual(double[] p, double[] R0, double[] obj, double[] img)
        {
            double[] R = Mul(Rodrigues(p[0], p[1], p[2]), R0);
            var res = new double[8];
            for (int i = 0; i < 4; i++)
            {
                double X = obj[i * 2], Y = obj[i * 2 + 1];
                double x = R[0] * X + R[1] * Y + p[3];
                double y = R[3] * X + R[4] * Y + p[4];
                double z = R[6] * X + R[7] * Y + p[5];
                res[i * 2] = x / z - img[i * 2];
                res[i * 2 + 1] = y / z - img[i * 2 + 1];
            }
            return res;
        }

        static double[] Orthonormal(double[] a, double[] b)
        {
            // a, b の中間を基準に直交化 (どちらかに偏らないよう対称に)
            double[] x = Norm(a), y = Norm(b);
            double[] sum = Norm(new[] { x[0] + y[0], x[1] + y[1], x[2] + y[2] });
            double[] diff = Norm(new[] { x[0] - y[0], x[1] - y[1], x[2] - y[2] });
            const double k = 0.70710678118654752;
            x = new[] { (sum[0] + diff[0]) * k, (sum[1] + diff[1]) * k, (sum[2] + diff[2]) * k };
            y = new[] { (sum[0] - diff[0]) * k, (sum[1] - diff[1]) * k, (sum[2] - diff[2]) * k };
            double[] z = Cross(x, y);
            return new[] { x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2] };
        }

        static double[] Rodrigues(double rx, double ry, double rz)
        {
            double th = Math.Sqrt(rx * rx + ry * ry + rz * rz);
            if (th < 1e-12) return new double[] { 1, -rz, ry, rz, 1, -rx, -ry, rx, 1 };
            double kx = rx / th, ky = ry / th, kz = rz / th, c = Math.Cos(th), s = Math.Sin(th), v = 1 - c;
            return new[]
            {
                c + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s,
                ky * kx * v + kz * s, c + ky * ky * v, ky * kz * v - kx * s,
                kz * kx * v - ky * s, kz * ky * v + kx * s, c + kz * kz * v
            };
        }

        static double[] Mul(double[] a, double[] b)
        {
            var o = new double[9];
            for (int r = 0; r < 3; r++)
                for (int c = 0; c < 3; c++)
                    o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
            return o;
        }

        static double[] Norm(double[] v)
        {
            double l = Math.Sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            return l > 0 ? new[] { v[0] / l, v[1] / l, v[2] / l } : v;
        }

        static double[] Cross(double[] a, double[] b) => new[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }
}
