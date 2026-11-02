package com.aidy.petrobot.ar

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 見つかったマーカー。corners は画像の画素座標 (x, y 下向き、画素の中心が整数) で、マーカーの左上・右上・右下・左下の順。 */
class ArucoMarker(val id: Int, val corners: DoubleArray = DoubleArray(8))

/**
 * ArUco マーカー (OpenCV の DICT_4X4_50 と同じ) の検出。Unity 版 AidyAruco.cs の移植 (OpenCV 不要)。別スレッドで回せる。
 * 適応的二値化 → 暗い領域の連結成分 → 凸包から四角形 → 6x6 マスを読んで辞書と照合 → 辺の直線当てはめで角をサブピクセルに。
 */
class ArucoDetector {
    companion object {
        /** DICT_4X4_50。内側 4x4 ビットを左上から行ごとに上位ビットから並べた値 (1 = 白)。 */
        val Dict4x4_50 = intArrayOf(
            0xB532, 0x0F9A, 0x332D, 0x9946, 0x549E, 0x79CD, 0x9E2E, 0xC4F2, 0xFEDA, 0xCF56, 0xF991, 0x11A7, 0x0EB7, 0x2A0F, 0x24B1, 0x263E, 0x4665,
            0x6600, 0x6C5E, 0x76AF, 0x868B, 0xB02B, 0xCCD5, 0xDD82, 0xFE47, 0x9471, 0xACE4, 0xA554, 0x2123, 0x346F, 0x4415, 0x57B2, 0x9ECF, 0xF0CB,
            0x08AE, 0x0929, 0x1875, 0x04FF, 0x0DF6, 0x1C5A, 0x1718, 0x2A28, 0x328C, 0x38B2, 0x24E8, 0x2EEB, 0x2D3F, 0x4B64, 0x502E, 0x5013,
        )

        private const val MaxCorrectionBits = 1 // DICT_4X4_50 の訂正可能ビット数
        private const val ThresholdC = 7        // 周囲の平均よりこれだけ暗ければ黒
        private const val MinSidePx = 14        // これより小さい四角形は読まない

        /** 4 点の対応 (src → dst、各 x,y の 8 要素) から射影変換 (3x3、h33 = 1) を求める。 */
        fun homography(src: DoubleArray, dst: DoubleArray): DoubleArray? {
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = src[i * 2]; val y = src[i * 2 + 1]; val u = dst[i * 2]; val v = dst[i * 2 + 1]
                val r = i * 2
                a[r][0] = x; a[r][1] = y; a[r][2] = 1.0; a[r][6] = -u * x; a[r][7] = -u * y; a[r][8] = u
                a[r + 1][3] = x; a[r + 1][4] = y; a[r + 1][5] = 1.0; a[r + 1][6] = -v * x; a[r + 1][7] = -v * y; a[r + 1][8] = v
            }
            val s = solve(a, 8) ?: return null
            return doubleArrayOf(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], 1.0)
        }

        fun apply(hm: DoubleArray, x: Double, y: Double, out: DoubleArray) {
            val z = hm[6] * x + hm[7] * y + hm[8]
            out[0] = (hm[0] * x + hm[1] * y + hm[2]) / z
            out[1] = (hm[3] * x + hm[4] * y + hm[5]) / z
        }

        /** 拡大係数行列 (n x n+1) をガウスの消去法で解く。 */
        fun solve(a: Array<DoubleArray>, n: Int): DoubleArray? {
            for (col in 0 until n) {
                var piv = col
                for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                if (abs(a[piv][col]) < 1e-12) return null
                if (piv != col) {
                    val t = a[col]; a[col] = a[piv]; a[piv] = t
                }
                for (r in 0 until n) {
                    if (r == col) continue
                    val f = a[r][col] / a[col][col]
                    if (f == 0.0) continue
                    for (k in col..n) a[r][k] -= f * a[col][k]
                }
            }
            return DoubleArray(n) { a[it][n] / a[it][it] }
        }

        private fun sq(v: Double) = v * v
        private fun cross(ox: Double, oy: Double, ax: Double, ay: Double, bx: Double, by: Double) = (ax - ox) * (by - oy) - (ay - oy) * (bx - ox)
    }

    private var gray = ByteArray(0)
    private var w = 0
    private var h = 0
    private var integral = IntArray(0)
    private var labels = IntArray(0)
    private var parent = IntArray(0)
    private var dark = BooleanArray(0)

    private fun g(i: Int) = gray[i].toInt() and 0xFF

    /** gray: 1 画素 1 バイトの輝度 (上の行から)。acceptIds が null なら辞書の全 ID を返す。 */
    fun detect(gray: ByteArray, width: Int, height: Int, acceptIds: Set<Int>? = null): List<ArucoMarker> {
        this.gray = gray
        w = width
        h = height
        val found = mutableListOf<ArucoMarker>()
        if (w < 16 || h < 16) return found
        // 二値化の窓はマーカーのマスより大きくないと、大きく写ったマーカーの黒枠が途切れる。
        // 小さめの窓から始め、何も見つからなければ窓を 2 倍にしていく
        val r = max(5, min(w, h) / 32)
        for (scale in intArrayOf(1, 2, 4)) {
            detectPass(r * scale, acceptIds, found)
            if (found.isNotEmpty()) break
        }
        return found
    }

    private fun detectPass(r: Int, acceptIds: Set<Int>?, found: MutableList<ArucoMarker>) {
        threshold(r)
        val box = label()
        val count = box.count
        val maxSide = (min(w, h) * 0.98).toInt()
        for (l in 1 until count) {
            val bw = box.maxX[l] - box.minX[l] + 1
            val bh = box.maxY[l] - box.minY[l] + 1
            if (bw < MinSidePx || bh < MinSidePx || bw > maxSide && bh > maxSide) continue
            if (box.area[l] < MinSidePx * 4) continue
            if (box.minX[l] == 0 || box.minY[l] == 0 || box.maxX[l] == w - 1 || box.maxY[l] == h - 1) continue // 画面の端で切れている
            val quad = quad(l, box.minX[l], box.minY[l], box.maxX[l], box.maxY[l]) ?: continue
            val dec = decode(quad) ?: continue
            val (id, rotation) = dec
            if (acceptIds != null && id !in acceptIds) continue
            val m = ArucoMarker(id)
            // 読み取ったときの向きから、マーカー本来の左上を先頭にする
            for (i in 0 until 4) {
                val src = (i + rotation) % 4
                m.corners[i * 2] = quad[src * 2]
                m.corners[i * 2 + 1] = quad[src * 2 + 1]
            }
            refine(m.corners)
            for (i in 0 until 8) m.corners[i] -= 0.5 // OpenCV / カメラ内部パラメータと同じく画素の中心を整数に
            // 同じ ID が重なって見つかったら大きい方を残す
            val dup = found.indexOfFirst { it.id == id && overlaps(it.corners, m.corners) }
            if (dup < 0) found += m
            else if (perimeter(m.corners) > perimeter(found[dup].corners)) found[dup] = m
        }
    }

    // ------------------------------------------------------------ 二値化 (積分画像で窓 (半径 r) の平均と比べる)

    private fun threshold(r: Int) {
        val n = w * h
        if (integral.size < (w + 1) * (h + 1)) integral = IntArray((w + 1) * (h + 1))
        if (dark.size < n) dark = BooleanArray(n)
        val ww = w + 1
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                row += g(y * w + x)
                integral[(y + 1) * ww + x + 1] = integral[y * ww + x + 1] + row
            }
        }
        for (y in 0 until h) {
            val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
                val sum = integral[y1 * ww + x1] - integral[y0 * ww + x1] - integral[y1 * ww + x0] + integral[y0 * ww + x0]
                val cnt = (x1 - x0) * (y1 - y0)
                dark[y * w + x] = g(y * w + x) * cnt < sum - ThresholdC * cnt
            }
        }
    }

    // ------------------------------------------------------------ 連結成分 (8 近傍、Union-Find)

    private fun find(a0: Int): Int {
        var a = a0
        while (parent[a] != a) {
            parent[a] = parent[parent[a]]
            a = parent[a]
        }
        return a
    }

    private class Boxes(val count: Int, val minX: IntArray, val minY: IntArray, val maxX: IntArray, val maxY: IntArray, val area: IntArray)

    private fun label(): Boxes {
        val n = w * h
        if (labels.size < n) labels = IntArray(n)
        if (parent.size < n / 2 + 2) parent = IntArray(n / 2 + 2)
        var next = 1
        parent[0] = 0
        val nb = IntArray(4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!dark[i]) {
                    labels[i] = 0
                    continue
                }
                var best = 0
                // 左・左上・上・右上
                nb[0] = if (x > 0) labels[i - 1] else 0
                nb[1] = if (y > 0 && x > 0) labels[i - w - 1] else 0
                nb[2] = if (y > 0) labels[i - w] else 0
                nb[3] = if (y > 0 && x < w - 1) labels[i - w + 1] else 0
                for (v in nb) {
                    if (v == 0) continue
                    if (best == 0) best = find(v)
                    else {
                        val rv = find(v)
                        if (rv != best) {
                            if (rv < best) {
                                parent[best] = rv
                                best = rv
                            } else parent[rv] = best
                        }
                    }
                }
                if (best == 0) {
                    if (next >= parent.size) parent = parent.copyOf(parent.size * 2)
                    parent[next] = next
                    best = next++
                }
                labels[i] = best
            }
        }
        // ラベルを代表に詰め直し、外接矩形と画素数を集計
        val remap = IntArray(next)
        var count = 1
        for (l in 1 until next) if (find(l) == l) remap[l] = count++
        for (l in 1 until next) remap[l] = remap[find(l)]
        val minX = IntArray(count) { Int.MAX_VALUE }
        val minY = IntArray(count) { Int.MAX_VALUE }
        val maxX = IntArray(count) { -1 }
        val maxY = IntArray(count) { -1 }
        val area = IntArray(count)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var l = labels[i]
                if (l == 0) continue
                l = remap[l]
                labels[i] = l
                area[l]++
                if (x < minX[l]) minX[l] = x
                if (x > maxX[l]) maxX[l] = x
                if (y < minY[l]) minY[l] = y
                if (y > maxY[l]) maxY[l] = y
            }
        }
        return Boxes(count, minX, minY, maxX, maxY, area)
    }

    // ------------------------------------------------------------ 四角形 (凸包 → 4 頂点)

    private fun quad(l: Int, x0: Int, y0: Int, x1: Int, y1: Int): DoubleArray? {
        // 凸包は各行の左端・右端だけから作れる
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (y in y0..y1) {
            var lo = -1
            var hi = -1
            for (x in x0..x1) {
                if (labels[y * w + x] != l) continue
                if (lo < 0) lo = x
                hi = x
            }
            if (lo < 0) continue
            xs += lo + 0.5; ys += y + 0.5
            if (hi != lo) {
                xs += hi + 0.5; ys += y + 0.5
            }
        }
        val hull = hull(xs, ys)
        if (hull.size < 4) return null
        val hullArea = abs(area(hull))

        // 最も離れた 2 点 (対角) と、その対角線から両側に最も離れた点
        var ia = 0
        var ic = 0
        var best = -1.0
        for (i in hull.indices) for (j in i + 1 until hull.size) {
            val d = sq(hull[i][0] - hull[j][0]) + sq(hull[i][1] - hull[j][1])
            if (d > best) {
                best = d; ia = i; ic = j
            }
        }
        var ib = -1
        var id = -1
        var bestB = 0.0
        var bestD = 0.0
        for (i in hull.indices) {
            val s = cross(hull[ia][0], hull[ia][1], hull[ic][0], hull[ic][1], hull[i][0], hull[i][1])
            if (s > bestB) { bestB = s; ib = i }
            if (s < bestD) { bestD = s; id = i }
        }
        if (ib < 0 || id < 0) return null
        val q = mutableListOf(hull[ia], hull[ib], hull[ic], hull[id])
        var quadArea = area(q)
        if (quadArea < 0) { // 画像座標 (y 下向き) で時計回りにそろえる
            q.reverse()
            quadArea = -quadArea
        }
        if (quadArea < hullArea * 0.88) return null // 四角形らしくない
        val res = DoubleArray(8)
        for (i in 0 until 4) {
            res[i * 2] = q[i][0]
            res[i * 2 + 1] = q[i][1]
            val ex = q[(i + 1) % 4][0] - q[i][0]
            val ey = q[(i + 1) % 4][1] - q[i][1]
            if (ex * ex + ey * ey < MinSidePx * MinSidePx * 0.5) return null
        }
        return res
    }

    private fun hull(xs: List<Double>, ys: List<Double>): List<DoubleArray> {
        val p = xs.indices.map { doubleArrayOf(xs[it], ys[it]) }
            .sortedWith { a, b -> if (a[0] != b[0]) a[0].compareTo(b[0]) else a[1].compareTo(b[1]) }
        val hull = ArrayList<DoubleArray>()
        for (pass in 0 until 2) {
            val start = hull.size
            for (k in p.indices) {
                val pt = if (pass == 0) p[k] else p[p.size - 1 - k]
                while (hull.size >= start + 2 &&
                    cross(hull[hull.size - 2][0], hull[hull.size - 2][1], hull[hull.size - 1][0], hull[hull.size - 1][1], pt[0], pt[1]) <= 0
                ) hull.removeAt(hull.size - 1)
                hull += pt
            }
            hull.removeAt(hull.size - 1)
        }
        return hull
    }

    // ------------------------------------------------------------ 読み取り

    /**
     * 四角形 (時計回り、q[0] を仮の左上) の 6x6 マスを読む。外周が黒で、内側 4x4 が辞書と一致すれば ID と、
     * 本来の左上が q の何番目か (rotation) を返す。
     */
    private fun decode(q: DoubleArray): Pair<Int, Int>? {
        val hm = homography(doubleArrayOf(0.0, 0.0, 6.0, 0.0, 6.0, 6.0, 0.0, 6.0), q) ?: return null
        val cell = DoubleArray(36)
        var lo = 255.0
        var hi = 0.0
        val pt = DoubleArray(2)
        for (cy in 0 until 6) for (cx in 0 until 6) {
            var sum = 0.0
            for (sy in 0 until 3) for (sx in 0 until 3) {
                apply(hm, cx + 0.3 + sx * 0.2, cy + 0.3 + sy * 0.2, pt)
                sum += sample(pt[0], pt[1])
            }
            val v = sum / 9.0
            cell[cy * 6 + cx] = v
            lo = min(lo, v)
            hi = max(hi, v)
        }
        if (hi - lo < 25) return null
        val thr = (lo + hi) * 0.5
        var borderWhite = 0
        for (i in 0 until 6) {
            if (cell[i] > thr) borderWhite++
            if (cell[30 + i] > thr) borderWhite++
            if (i in 1..4) {
                if (cell[i * 6] > thr) borderWhite++
                if (cell[i * 6 + 5] > thr) borderWhite++
            }
        }
        if (borderWhite > 1) return null

        var bits = IntArray(16)
        for (r in 0 until 4) for (c in 0 until 4) bits[r * 4 + c] = if (cell[(r + 1) * 6 + c + 1] > thr) 1 else 0

        // k 回 時計回りに回した読みが辞書と一致したら、本来の左上は q[(4 - k) % 4]
        for (k in 0 until 4) {
            var code = 0
            for (i in 0 until 16) code = (code shl 1) or bits[i]
            for (d in Dict4x4_50.indices) {
                if (Integer.bitCount(code xor Dict4x4_50[d]) > MaxCorrectionBits) continue
                return d to (4 - k) % 4
            }
            bits = rotateCW(bits)
        }
        return null
    }

    private fun rotateCW(b: IntArray): IntArray {
        val o = IntArray(16)
        for (r in 0 until 4) for (c in 0 until 4) o[r * 4 + c] = b[(3 - c) * 4 + r]
        return o
    }

    // ------------------------------------------------------------ 角のサブピクセル化 (各辺の明暗の境目に直線を当てはめて交点)

    private fun refine(c: DoubleArray) {
        val lines = arrayOfNulls<DoubleArray>(4)
        for (e in 0 until 4) {
            val ax = c[e * 2]; val ay = c[e * 2 + 1]; val bx = c[(e + 1) % 4 * 2]; val by = c[(e + 1) % 4 * 2 + 1]
            var dx = bx - ax
            var dy = by - ay
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1) return
            dx /= len
            dy /= len
            // 時計回りなので (dy, -dx) が外向き
            val nx = dy
            val ny = -dx
            val reach = max(2.0, min(5.0, len / 12.0))
            val px = ArrayList<Double>()
            val py = ArrayList<Double>()
            val steps = 16
            val prof = DoubleArray(steps + 1)
            for (s in 1..11) {
                val t = 0.1 + 0.8 * s / 12.0
                val mx = ax + (bx - ax) * t
                val my = ay + (by - ay) * t
                // 内側 (黒) → 外側 (白) へ輝度を調べ、中間値を横切る位置
                var pmin = 255.0
                var pmax = 0.0
                for (k in 0..steps) {
                    val o = -reach + 2 * reach * k / steps
                    prof[k] = sample(mx + nx * o, my + ny * o)
                    pmin = min(pmin, prof[k])
                    pmax = max(pmax, prof[k])
                }
                if (pmax - pmin < 20) continue
                val mid = (pmin + pmax) * 0.5
                for (k in 0 until steps) {
                    if (prof[k] > mid || prof[k + 1] <= mid) continue
                    val f = (mid - prof[k]) / (prof[k + 1] - prof[k])
                    val o = -reach + 2 * reach * (k + f) / steps
                    px += mx + nx * o
                    py += my + ny * o
                    break
                }
            }
            if (px.size < 4) return // 当てはめられない辺があればそのまま
            lines[e] = fitLine(px, py)
        }
        val outC = DoubleArray(8)
        for (i in 0 until 4) {
            // 角 i は 辺 (i-1) と 辺 i の交点
            val l1 = lines[(i + 3) % 4]!!
            val l2 = lines[i]!!
            val det = l1[0] * l2[1] - l1[1] * l2[0]
            if (abs(det) < 1e-9) return
            val x = (l1[2] * l2[1] - l1[1] * l2[2]) / det
            val y = (l1[0] * l2[2] - l1[2] * l2[0]) / det
            if (sq(x - c[i * 2]) + sq(y - c[i * 2 + 1]) > 9.0) return // 大きく動くなら当てはめ失敗
            outC[i * 2] = x
            outC[i * 2 + 1] = y
        }
        System.arraycopy(outC, 0, c, 0, 8)
    }

    /** 主成分で直線 a x + b y = c を当てはめる。 */
    private fun fitLine(xs: List<Double>, ys: List<Double>): DoubleArray {
        val mx = xs.average()
        val my = ys.average()
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        val angle = 0.5 * atan2(2 * sxy, sxx - syy) // 直線の向き
        val a = -sin(angle)
        val b = cos(angle)
        return doubleArrayOf(a, b, a * mx + b * my)
    }

    // ------------------------------------------------------------ 小道具

    private fun sample(x0: Double, y0: Double): Double {
        var x = x0 - 0.5
        var y = y0 - 0.5
        if (x < 0) x = 0.0
        if (y < 0) y = 0.0
        if (x > w - 1.001) x = w - 1.001
        if (y > h - 1.001) y = h - 1.001
        val ix = x.toInt()
        val iy = y.toInt()
        val fx = x - ix
        val fy = y - iy
        val i = iy * w + ix
        val top = g(i) + (g(i + 1) - g(i)) * fx
        val bottom = g(i + w) + (g(i + w + 1) - g(i + w)) * fx
        return top + (bottom - top) * fy
    }

    private fun area(p: List<DoubleArray>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a[0] * b[1] - b[0] * a[1]
        }
        return s * 0.5
    }

    private fun perimeter(c: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until 4) s += sqrt(sq(c[(i + 1) % 4 * 2] - c[i * 2]) + sq(c[(i + 1) % 4 * 2 + 1] - c[i * 2 + 1]))
        return s
    }

    private fun overlaps(a: DoubleArray, b: DoubleArray): Boolean {
        val ax = (a[0] + a[2] + a[4] + a[6]) / 4; val ay = (a[1] + a[3] + a[5] + a[7]) / 4
        val bx = (b[0] + b[2] + b[4] + b[6]) / 4; val by = (b[1] + b[3] + b[5] + b[7]) / 4
        return sq(ax - bx) + sq(ay - by) < sq(max(perimeter(a), perimeter(b)) / 8)
    }
}

/**
 * 正方形マーカーの姿勢 (OpenCV のカメラ座標: x 右, y 下, z 前方)。マーカー座標は中心が原点、x 右・y 上・z 手前 (カメラ側)。
 * 射影変換の分解で初期値を作り、再投影誤差をガウス・ニュートン法で詰める。
 */
object ArucoPose {
    /** R: 3x3 (行優先、列 = マーカーの x,y,z 軸)。t: マーカー中心の位置 (size と同じ単位)。 */
    class Result(val r: DoubleArray, val t: DoubleArray)

    fun solve(corners: DoubleArray, size: Double, fx: Double, fy: Double, cx: Double, cy: Double): Result? {
        val s = size * 0.5
        val obj = doubleArrayOf(-s, s, s, s, s, -s, -s, -s)
        val img = DoubleArray(8)
        for (i in 0 until 4) {
            img[i * 2] = (corners[i * 2] - cx) / fx
            img[i * 2 + 1] = (corners[i * 2 + 1] - cy) / fy
        }
        val hm = ArucoDetector.homography(obj, img) ?: return null
        // H = λ [r1 r2 t]
        val n1 = sqrt(hm[0] * hm[0] + hm[3] * hm[3] + hm[6] * hm[6])
        val n2 = sqrt(hm[1] * hm[1] + hm[4] * hm[4] + hm[7] * hm[7])
        var lambda = 2.0 / (n1 + n2)
        if (hm[8] * lambda < 0) lambda = -lambda // マーカーはカメラの前 (z > 0)
        val r1 = doubleArrayOf(hm[0] * lambda, hm[3] * lambda, hm[6] * lambda)
        val r2 = doubleArrayOf(hm[1] * lambda, hm[4] * lambda, hm[7] * lambda)
        val t0 = doubleArrayOf(hm[2] * lambda, hm[5] * lambda, hm[8] * lambda)
        val r0 = orthonormal(r1, r2)

        // 回転ベクトル + 並進の 6 変数でガウス・ニュートン
        val p = doubleArrayOf(0.0, 0.0, 0.0, t0[0], t0[1], t0[2])
        for (iter in 0 until 8) {
            val res = residual(p, r0, obj, img)
            val jt = Array(6) { DoubleArray(8) }
            for (k in 0 until 6) {
                val q = p.clone()
                val eps = if (k < 3) 1e-6 else 1e-6 * max(1e-3, abs(p[5]))
                q[k] += eps
                val r2_ = residual(q, r0, obj, img)
                for (m in 0 until 8) jt[k][m] = (r2_[m] - res[m]) / eps
            }
            val a = Array(6) { DoubleArray(7) }
            for (i in 0 until 6) {
                for (j in 0 until 6) {
                    var sum = 0.0
                    for (m in 0 until 8) sum += jt[i][m] * jt[j][m]
                    a[i][j] = sum + if (i == j) 1e-9 else 0.0
                }
                var g = 0.0
                for (m in 0 until 8) g += jt[i][m] * res[m]
                a[i][6] = -g
            }
            val step = ArucoDetector.solve(a, 6) ?: break
            for (k in 0 until 6) p[k] += step[k]
            if (abs(step[0]) + abs(step[1]) + abs(step[2]) < 1e-9) break
        }
        val r = mul(rodrigues(p[0], p[1], p[2]), r0)
        val t = doubleArrayOf(p[3], p[4], p[5])
        return if (t[2] > 0) Result(r, t) else null
    }

    private fun residual(p: DoubleArray, r0: DoubleArray, obj: DoubleArray, img: DoubleArray): DoubleArray {
        val r = mul(rodrigues(p[0], p[1], p[2]), r0)
        val res = DoubleArray(8)
        for (i in 0 until 4) {
            val xo = obj[i * 2]; val yo = obj[i * 2 + 1]
            val x = r[0] * xo + r[1] * yo + p[3]
            val y = r[3] * xo + r[4] * yo + p[4]
            val z = r[6] * xo + r[7] * yo + p[5]
            res[i * 2] = x / z - img[i * 2]
            res[i * 2 + 1] = y / z - img[i * 2 + 1]
        }
        return res
    }

    private fun orthonormal(a: DoubleArray, b: DoubleArray): DoubleArray {
        // a, b の中間を基準に直交化 (どちらかに偏らないよう対称に)
        val x0 = norm(a)
        val y0 = norm(b)
        val sum = norm(doubleArrayOf(x0[0] + y0[0], x0[1] + y0[1], x0[2] + y0[2]))
        val diff = norm(doubleArrayOf(x0[0] - y0[0], x0[1] - y0[1], x0[2] - y0[2]))
        val k = 0.70710678118654752
        val x = doubleArrayOf((sum[0] + diff[0]) * k, (sum[1] + diff[1]) * k, (sum[2] + diff[2]) * k)
        val y = doubleArrayOf((sum[0] - diff[0]) * k, (sum[1] - diff[1]) * k, (sum[2] - diff[2]) * k)
        val z = crossV(x, y)
        return doubleArrayOf(x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2])
    }

    private fun rodrigues(rx: Double, ry: Double, rz: Double): DoubleArray {
        val th = sqrt(rx * rx + ry * ry + rz * rz)
        if (th < 1e-12) return doubleArrayOf(1.0, -rz, ry, rz, 1.0, -rx, -ry, rx, 1.0)
        val kx = rx / th; val ky = ry / th; val kz = rz / th
        val c = cos(th); val s = sin(th); val v = 1 - c
        return doubleArrayOf(
            c + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s,
            ky * kx * v + kz * s, c + ky * ky * v, ky * kz * v - kx * s,
            kz * kx * v - ky * s, kz * ky * v + kx * s, c + kz * kz * v,
        )
    }

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3)
            o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        return o
    }

    private fun norm(v: DoubleArray): DoubleArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (l > 0) doubleArrayOf(v[0] / l, v[1] / l, v[2] / l) else v
    }

    private fun crossV(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
}
