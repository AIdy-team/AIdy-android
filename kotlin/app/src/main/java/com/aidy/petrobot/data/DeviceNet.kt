package com.aidy.petrobot.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.aidy.petrobot.AidyApplication
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.net.UnknownHostException
import kotlin.random.Random

/**
 * 本体 (同じ Wi-Fi の中の機械) へ届くように HTTP をつなぐ下回り。
 *
 * - Android は「インターネットにつながらない Wi-Fi」(本体の親機モード AIdy-xxxx、テザリング先、学校の Wi-Fi など) に
 *   いるとき、アプリの通信をモバイルデータ側に流してしまう。そのままだと 192.168.x.x / 10.42.0.1 に届かず時間切れになる。
 *   → Wi-Fi の Network を探して、その上で接続する。
 * - 本体のアドレスが "<ホスト名>.local" のとき、Android は .local を引けない機種が多い。
 *   → mDNS (224.0.0.251:5353) に自分で A レコードを問い合わせて IP に直す。
 */
object DeviceNet {
    private const val MdnsTimeoutMs = 1500

    /** いまの Wi-Fi (無ければ null = OS 任せ)。 */
    fun wifiNetwork(): Network? = try {
        val cm = AidyApplication.context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    } catch (e: Exception) {
        Log.w("AIDY", "Wi-Fi の確認に失敗: ${e.message}")
        null
    }

    /** "ホスト:ポート" の URL を Wi-Fi 上で開く。.local は先に IP に直す。 */
    fun open(hostPort: String, path: String): HttpURLConnection {
        val net = wifiNetwork()
        val (name, port) = splitHostPort(hostPort)
        val addr = if (name.endsWith(".local", ignoreCase = true)) resolveMdns(name, net).hostAddress!! else name
        val hostPart = if (addr.contains(':') && !addr.startsWith("[")) "[$addr]" else addr
        val url = URL("http://$hostPart${if (port != null) ":$port" else ""}$path")
        val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        conn.useCaches = false
        return conn
    }

    fun splitHostPort(hostPort: String): Pair<String, Int?> {
        if (hostPort.startsWith("[")) { // [IPv6]:port
            val end = hostPort.indexOf(']')
            val name = hostPort.substring(1, end)
            val port = hostPort.substring(end + 1).removePrefix(":").toIntOrNull()
            return name to port
        }
        val i = hostPort.lastIndexOf(':')
        return if (i > 0 && hostPort.indexOf(':') == i) hostPort.substring(0, i) to hostPort.substring(i + 1).toIntOrNull()
        else hostPort to null
    }

    private val mdnsCache = HashMap<String, InetAddress>()

    /** "aidy2.local" → IPv4。見つからなければ UnknownHostException。 */
    fun resolveMdns(name: String, net: Network?): InetAddress {
        val key = name.lowercase()
        // OS が引ける機種ならそれで済ませる
        try {
            val a = (net?.getAllByName(name) ?: InetAddress.getAllByName(name)).firstOrNull()
            if (a != null) return a.also { synchronized(mdnsCache) { mdnsCache[key] = it } }
        } catch (_: Exception) {
        }
        val query = buildQuery(name)
        DatagramSocket().use { sock ->
            net?.bindSocket(sock)
            sock.soTimeout = MdnsTimeoutMs
            val group = InetSocketAddress(InetAddress.getByName("224.0.0.251"), 5353)
            // 送り元ポートが 5353 でないので、返事は自分あてに直接返ってくる (legacy unicast)
            repeat(2) {
                sock.send(DatagramPacket(query, query.size, group))
                val buf = ByteArray(1500)
                val deadline = System.currentTimeMillis() + MdnsTimeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(p)
                    } catch (e: java.net.SocketTimeoutException) {
                        break
                    }
                    parseA(buf, p.length, name)?.let { a ->
                        synchronized(mdnsCache) { mdnsCache[key] = a }
                        return a
                    }
                }
            }
        }
        synchronized(mdnsCache) { mdnsCache[key] }?.let { return it }
        throw UnknownHostException(name)
    }

    private fun buildQuery(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        val id = Random.nextInt(1, 0xFFFF)
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        writeName(out, name)
        out.write(byteArrayOf(0, 1, 0, 1)) // QTYPE=A, QCLASS=IN
        return out.toByteArray()
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.UTF_8)
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
    }

    /** DNS 応答から name の A レコードを探す。 */
    private fun parseA(b: ByteArray, len: Int, name: String): InetAddress? {
        if (len < 12) return null
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        if (b[2].toInt() and 0x80 == 0) return null // 返事でない
        val qd = u16(4)
        val records = u16(6) + u16(8) + u16(10)
        var pos = 12
        fun readName(start: Int): Pair<String, Int> {
            val sb = StringBuilder()
            var p = start
            var end = -1
            var jumps = 0
            while (p < len) {
                val l = b[p].toInt() and 0xFF
                if (l == 0) { p++; break }
                if (l and 0xC0 == 0xC0) {
                    if (end < 0) end = p + 2
                    p = ((l and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF)
                    if (++jumps > 20) break
                    continue
                }
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(b, p + 1, l, Charsets.UTF_8))
                p += 1 + l
            }
            return sb.toString() to (if (end >= 0) end else p)
        }
        try {
            repeat(qd) { pos = readName(pos).second + 4 }
            repeat(records) {
                val (rn, p) = readName(pos)
                val type = u16(p)
                val rdLen = u16(p + 8)
                val data = p + 10
                if (type == 1 && rdLen == 4 && rn.equals(name.trimEnd('.'), ignoreCase = true)) {
                    return InetAddress.getByAddress(b.copyOfRange(data, data + 4))
                }
                pos = data + rdLen
            }
        } catch (_: Exception) {
        }
        return null
    }
}
