package com.aidy.petrobot.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.aidy.petrobot.AidyApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Low Energy で Aidy 本体と話す。約束は docs/本体Bluetooth対応_仕様.txt のとおり。
 *
 * - 本体は GATT サーバー (ペリフェラル)。サービス 1 つ・キャラクタリスティック 2 つ:
 *     RX (アプリ → 本体, Write)  /  TX (本体 → アプリ, Notify)
 * - 中身は Wi-Fi 版と同じ /api/... を、1 行 1 メッセージの JSON (末尾 "\n") で包んだもの。
 *     送る: {"id":1,"m":"GET","p":"/api/ping","t":"合言葉"}         (POST は "b":{...} を足す)
 *     返る: {"id":1,"s":200,"b":{...HTTP 版の返事と同じ...}}
 * - 1 行が MTU に入らないときは何回にも分けて書く / 届く。受け取り側は "\n" が来るまでつなげる。
 * - ペアリングは不要 (合言葉で守る)。
 */
object BleLink {
    val ServiceUuid: UUID = UUID.fromString("a1d70001-8c4f-4b6e-9d2a-5f3e7c1b0a90")
    val RxUuid: UUID = UUID.fromString("a1d70002-8c4f-4b6e-9d2a-5f3e7c1b0a90") // アプリ → 本体 (Write)
    val TxUuid: UUID = UUID.fromString("a1d70003-8c4f-4b6e-9d2a-5f3e7c1b0a90") // 本体 → アプリ (Notify)
    private val CccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** 名前を指定しないで探すときの目印 (本体の広告名は "AIdy-xxxx")。 */
    const val NamePrefix = "AIdy"

    private const val ScanMs = 8000L
    private const val ConnectMs = 10000L
    private const val StepMs = 5000L
    private const val RequestMs = 10000L
    private const val IdleDisconnectMs = 30000L
    private const val WantMtu = 517

    private const val AddrKey = "aidy.device.bleAddr"

    /** BLE 側のつまずき (原因をそのまま画面に出せる文にしてある)。IOException なので Wi-Fi 版と同じ扱いになる。 */
    class BleException(message: String) : IOException(message)

    private val ctx: Context get() = AidyApplication.context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var nextId = 1
    private var idleJob: Job? = null

    /** つながった本体の広告名 ("AIdy-1A2B" など)。 */
    var connectedName: String = ""
        private set

    // コールバック → コルーチンの橋渡し (同時に 1 つしか待たない: lock で直列化している)
    @Volatile private var step: CompletableDeferred<Int>? = null
    @Volatile private var reply: CompletableDeferred<String>? = null
    private val lineBuf = ByteArrayOutputStream()

    /** 必要な実行時の許可 (画面側でまとめて求める)。 */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun adapter(): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * 1 回の問い合わせ。返り値は (状態コード, 返事の本文 JSON 文字列)。HTTP 版の (responseCode, body) と同じ意味。
     * name = 本体の広告名 (空なら近くの "AIdy…" を探す)。
     */
    suspend fun request(name: String, method: String, path: String, token: String, body: JSONObject?): Pair<Int, String> =
        lock.withLock {
            idleJob?.cancel()
            try {
                ensureConnected(name)
                val id = nextId++
                val msg = JSONObject().put("id", id).put("m", method).put("p", path).put("t", token)
                if (body != null) msg.put("b", body)
                val res = try {
                    exchange(msg.toString() + "\n")
                } catch (e: BleException) {
                    close() // 途中で切れたら 1 回だけつなぎ直して送り直す
                    ensureConnected(name)
                    exchange(msg.toString() + "\n")
                }
                val o = try {
                    JSONObject(res)
                } catch (e: Exception) {
                    throw BleException("本体からの返事を読み取れませんでした (Bluetooth)")
                }
                val code = o.optInt("s", 0)
                val b = o.opt("b")
                code to (b?.toString() ?: "{}")
            } catch (e: BleException) {
                close()
                throw e
            } finally {
                idleJob = scope.launch { delay(IdleDisconnectMs); lock.withLock { close() } }
            }
        }

    // ------------------------------------------------------------------ 接続

    @SuppressLint("MissingPermission")
    private suspend fun ensureConnected(name: String) {
        if (gatt != null && rx != null) return
        if (!hasPermissions()) throw BleException("Bluetooth の許可がありません。設定の「Aidy本体と接続」で許可してください")
        val ad = adapter() ?: throw BleException("この端末は Bluetooth に対応していません")
        if (!ad.isEnabled) throw BleException("Bluetooth がオフです。スマホの Bluetooth をオンにしてください")

        // 前につながった本体の住所があれば、探さずに直接つなぐ (だめなら探し直す)
        val saved = Prefs.getString(AddrKey, "")
        if (saved.isNotEmpty() && BluetoothAdapter.checkBluetoothAddress(saved)) {
            try {
                connect(ad.getRemoteDevice(saved), name)
                return
            } catch (e: BleException) {
                Log.w("AIDY", "BLE 直接接続に失敗、探し直す: ${e.message}")
                close()
            }
        }
        val dev = scan(ad, name)
        connect(dev, name)
        Prefs.putString(AddrKey, dev.address)
    }

    /** 保存した本体の住所を忘れる (本体を変えたとき)。 */
    fun forgetDevice() {
        Prefs.putString(AddrKey, "")
        scope.launch { lock.withLock { close() } }
    }

    @SuppressLint("MissingPermission")
    private suspend fun scan(ad: BluetoothAdapter, name: String): BluetoothDevice {
        val scanner = ad.bluetoothLeScanner ?: throw BleException("Bluetooth がオフです。スマホの Bluetooth をオンにしてください")
        val found = CompletableDeferred<BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val rec = result.scanRecord
                val advName = rec?.deviceName ?: result.device.name ?: ""
                val hasService = rec?.serviceUuids?.any { it.uuid == ServiceUuid } == true
                val match = if (name.isNotEmpty()) advName.equals(name, ignoreCase = true)
                else hasService || advName.startsWith(NamePrefix, ignoreCase = true)
                if (match) found.complete(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(BleException("Bluetooth で本体を探せませんでした (エラー $errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        try {
            return withTimeoutOrNull(ScanMs) { found.await() }
                ?: throw BleException(
                    if (name.isNotEmpty()) "Bluetooth で本体 ($name) が見つかりません。本体の電源と、近くにいるかを確かめてください"
                    else "Bluetooth で近くに Aidy 本体が見つかりません。本体の電源と、近くにいるかを確かめてください"
                )
        } finally {
            try { scanner.stopScan(cb) } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connect(dev: BluetoothDevice, name: String) {
        val connected = CompletableDeferred<Int>()
        step = connected
        lineBuf.reset()
        val g = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw BleException("Bluetooth で本体につなげませんでした")
        gatt = g
        withTimeoutOrNull(ConnectMs) { connected.await() }
            ?: throw BleException("Bluetooth で本体につながりません (時間切れ)。本体の Bluetooth が動いているか確かめてください")

        // MTU を広げる (本体が受けなくても 23 のまま続ける)
        mtu = 23
        awaitStep { g.requestMtu(WantMtu) }?.let { mtu = it }

        awaitStep { g.discoverServices() } ?: throw BleException("Bluetooth: 本体の中身を調べられませんでした")
        val svc = g.getService(ServiceUuid)
            ?: throw BleException("Bluetooth でつながった機械 (${dev.name ?: dev.address}) は Aidy の通信に対応していません")
        val rxC = svc.getCharacteristic(RxUuid) ?: throw BleException("Bluetooth: 本体に受け口 (RX) がありません")
        val txC = svc.getCharacteristic(TxUuid) ?: throw BleException("Bluetooth: 本体に送り口 (TX) がありません")

        g.setCharacteristicNotification(txC, true)
        val cccd = txC.getDescriptor(CccdUuid) ?: throw BleException("Bluetooth: 本体の通知の設定 (CCCD) がありません")
        val ok = awaitStep {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
        if (ok != BluetoothGatt.GATT_SUCCESS) throw BleException("Bluetooth: 本体からの通知を受け取れるようにできませんでした")
        rx = rxC
        connectedName = dev.name ?: name
        Log.i("AIDY", "BLE つながった ${dev.address} mtu=$mtu")
    }

    /** start() を呼んで、対応するコールバックの結果 (status や MTU) を待つ。start が false / 時間切れなら null。 */
    private suspend fun awaitStep(start: () -> Boolean): Int? {
        val d = CompletableDeferred<Int>()
        step = d
        if (!start()) return null
        return withTimeoutOrNull(StepMs) { d.await() }
    }

    @SuppressLint("MissingPermission")
    private fun close() {
        idleJob = null
        val g = gatt
        gatt = null
        rx = null
        connectedName = ""
        reply?.completeExceptionally(BleException("Bluetooth が切れました"))
        reply = null
        step?.cancel()
        step = null
        try { g?.disconnect() } catch (_: Exception) {}
        try { g?.close() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ 1 往復

    @SuppressLint("MissingPermission")
    private suspend fun exchange(line: String): String {
        val g = gatt ?: throw BleException("Bluetooth が切れました")
        val c = rx ?: throw BleException("Bluetooth が切れました")
        val answer = CompletableDeferred<String>()
        reply = answer
        synchronized(lineBuf) { lineBuf.reset() }

        val bytes = line.toByteArray(Charsets.UTF_8)
        val chunk = (mtu - 3).coerceIn(20, 512)
        var pos = 0
        while (pos < bytes.size) {
            val part = bytes.copyOfRange(pos, minOf(bytes.size, pos + chunk))
            val st = awaitStep {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, part, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    c.value = part
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
            }
            if (st != BluetoothGatt.GATT_SUCCESS) throw BleException("Bluetooth で本体に送れませんでした")
            pos += part.size
        }
        return try {
            withTimeout(RequestMs) { answer.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw BleException("Bluetooth: 本体から返事がありません (時間切れ)")
        } finally {
            reply = null
        }
    }

    private fun onNotify(value: ByteArray) {
        synchronized(lineBuf) {
            for (b in value) {
                if (b == '\n'.code.toByte()) {
                    val text = lineBuf.toString(Charsets.UTF_8.name())
                    lineBuf.reset()
                    if (text.isNotBlank()) reply?.complete(text)
                } else {
                    lineBuf.write(b.toInt())
                }
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                step?.complete(status)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                Log.i("AIDY", "BLE 切断 status=$status")
                step?.completeExceptionally(BleException("Bluetooth で本体につながりません (status $status)"))
                reply?.completeExceptionally(BleException("Bluetooth が切れました"))
                if (gatt === g) {
                    gatt = null
                    rx = null
                    try { g.close() } catch (_: Exception) {}
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            step?.complete(if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            step?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            step?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            step?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == TxUuid) onNotify(value)
        }

        @Deprecated("Android 12 以前用")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return // 新しい方で受け取る
            @Suppress("DEPRECATION")
            if (c.uuid == TxUuid) onNotify(c.value ?: return)
        }
    }
}
