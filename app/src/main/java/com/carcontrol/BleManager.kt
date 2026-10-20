package com.carcontrol

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * ESP32-C3 小车 BLE 通讯管理。
 * 固件为 NimBLE 单模蓝牙，只实现 BLE，不再扫描/连接经典蓝牙。
 */
class BleManager(private val context: Context) {

    companion object {
        // 标准 UUID (文档与 BLE 规范标准)
        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

        // 历史固件兼容 UUID (固件曾因字节序倒序生成的实际空中 UUID)
        val LEGACY_SERVICE_UUID: UUID = UUID.fromString("4b9131c3-c9c5-cc8f-459e-1fb54fafc201")
        val LEGACY_CHARACTERISTIC_UUID: UUID = UUID.fromString("a8261b36-07ea-f5b7-4688-36e1beb5483e")

        const val SCAN_TIMEOUT_MS = 12_000L
        private const val SCAN_UI_FLUSH_MS = 200L
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val WRITE_TIMEOUT_MS = 800L
        private const val MAX_QUEUE = 6
    }

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED }

    interface Callback {
        fun onStateChanged(state: State)
        fun onScanResultsUpdated(devices: List<BleDevice>)
        fun onScanFinished()
        fun onConnected(name: String, address: String)
        fun onDisconnected()
        fun onError(message: String)
        fun onLog(message: String)
    }

    var state: State = State.IDLE
        private set

    private var callback: Callback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val seenDevices = LinkedHashMap<String, BleDevice>()
    private val pendingDevices = HashMap<String, BleDevice>()
    private var flushScheduled = false

    private val writeQueue = ArrayDeque<String>()
    private var writeInFlight = false

    private val scanTimeout = Runnable {
        if (state == State.SCANNING) {
            finishScan()
            callback?.onLog("扫描结束")
        }
    }

    private val flushRunnable = Runnable {
        flushScheduled = false
        if (state == State.SCANNING) {
            emitPendingDevices()
        }
    }

    private val connectTimeout = Runnable {
        if (state == State.CONNECTING) {
            closeGatt()
            setState(State.IDLE)
            callback?.onError("连接超时，请靠近小车后重试")
        }
    }

    private val writeTimeout = Runnable {
        if (writeInFlight) {
            writeInFlight = false
            callback?.onError("指令发送超时")
            drainWrites()
        }
    }

    fun setCallback(cb: Callback) {
        callback = cb
    }

    // ========================= 扫描 =========================

    fun hasPermissions(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        granted(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun startScan(): Boolean {
        if (state == State.CONNECTING || state == State.CONNECTED) return false
        val bt = adapter ?: run {
            callback?.onError("此设备不支持蓝牙")
            return false
        }
        if (!bt.isEnabled) {
            callback?.onError("请先打开蓝牙")
            return false
        }
        if (!hasPermissions()) {
            callback?.onError("缺少蓝牙权限")
            return false
        }
        val scanner = bt.bluetoothLeScanner ?: run {
            callback?.onError("蓝牙扫描不可用")
            return false
        }

        stopScanInternal()
        seenDevices.clear()
        pendingDevices.clear()

        try {
            // 使用通用扫描，避免特定厂商对 128 位 UUID ScanFilter 过滤失效导致漏检
            scanner.startScan(null, buildScanSettings(), scanCallback)
        } catch (e: SecurityException) {
            callback?.onError("缺少蓝牙扫描权限")
            return false
        }
        setState(State.SCANNING)
        callback?.onLog("正在搜索附近的小车…")
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        return true
    }

    fun stopScan() {
        if (state != State.SCANNING) return
        finishScan()
    }

    private fun buildScanSettings(): ScanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setReportDelay(0)
        .build()

    private fun stopScanInternal() {
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.removeCallbacks(flushRunnable)
        if (state == State.SCANNING) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {
            }
        }
        pendingDevices.clear()
        flushScheduled = false
    }

    private fun finishScan() {
        emitPendingDevices()
        stopScanInternal()
        setState(State.IDLE)
        callback?.onScanFinished()
    }

    private fun isCarDevice(name: String, result: ScanResult): Boolean {
        val serviceUuids = result.scanRecord?.serviceUuids ?: emptyList()
        val hasMatchingUuid = serviceUuids.any {
            it.uuid == SERVICE_UUID || it.uuid == LEGACY_SERVICE_UUID
        }
        if (hasMatchingUuid) return true
        if (name.contains("ESP32", ignoreCase = true)) return true
        if (name.contains("car", ignoreCase = true)) return true
        return false
    }

    private fun handleScanResult(result: ScanResult) {
        if (state != State.SCANNING) return
        val device = result.device ?: return
        val name = resolveName(device, result)
        val isCar = isCarDevice(name, result)
        val found = BleDevice(name, device.address, result.rssi, isCar)
        val latest = pendingDevices[device.address] ?: seenDevices[device.address]

        if (latest == null ||
            latest.name != found.name ||
            latest.isCar != found.isCar ||
            kotlin.math.abs(latest.rssi - found.rssi) >= 3
        ) {
            pendingDevices[device.address] = found
            scheduleFlush()
        }
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        mainHandler.postDelayed(flushRunnable, SCAN_UI_FLUSH_MS)
    }

    private fun emitPendingDevices() {
        if (pendingDevices.isEmpty()) return
        seenDevices.putAll(pendingDevices)
        pendingDevices.clear()
        flushScheduled = false
        val devices = seenDevices.values
            .sortedWith(compareByDescending<BleDevice> { it.isCar }.thenByDescending { it.rssi })
            .take(30)
        callback?.onScanResultsUpdated(devices)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.post {
                finishScan()
                val msg = when (errorCode) {
                    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "扫描已在进行中"
                    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "扫描初始化失败"
                    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "不支持低功耗蓝牙扫描"
                    else -> "扫描失败 ($errorCode)"
                }
                callback?.onError(msg)
            }
        }
    }

    private fun resolveName(device: BluetoothDevice, result: ScanResult): String {
        result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            device.name?.takeIf { it.isNotBlank() } ?: "未命名设备"
        } catch (_: SecurityException) {
            "未命名设备"
        }
    }

    // ========================= 连接 =========================

    fun connect(address: String) {
        if (state == State.CONNECTING || state == State.CONNECTED) return
        val bt = adapter ?: run {
            callback?.onError("此设备不支持蓝牙")
            return
        }
        if (!bt.isEnabled) {
            callback?.onError("请先打开蓝牙")
            return
        }
        val device = try {
            bt.getRemoteDevice(address)
        } catch (_: Exception) {
            callback?.onError("无效的设备地址")
            return
        }

        stopScanInternal()
        closeGatt()
        setState(State.CONNECTING)
        callback?.onLog("正在连接 ${deviceName(device)}…")

        val gatt = try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            setState(State.IDLE)
            callback?.onError("缺少蓝牙连接权限")
            return
        }
        if (gatt == null) {
            setState(State.IDLE)
            callback?.onError("无法发起连接")
            return
        }
        bluetoothGatt = gatt
        mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post {
                if (gatt !== bluetoothGatt) return@post
                when {
                    newState == BluetoothProfile.STATE_CONNECTED &&
                        status == BluetoothGatt.GATT_SUCCESS -> {
                        callback?.onLog("已连接，正在发现服务…")
                        // 延迟 300ms 发现服务，避开蓝牙协议栈刚连接时的并发震荡
                        mainHandler.postDelayed({
                            if (gatt === bluetoothGatt && state == State.CONNECTING) {
                                try {
                                    val ok = gatt.discoverServices()
                                    if (!ok) {
                                        failConnection(gatt, "启动服务发现失败")
                                    }
                                } catch (_: SecurityException) {
                                    failConnection(gatt, "缺少蓝牙连接权限")
                                }
                            }
                        }, 300L)
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        val wasConnected = state == State.CONNECTED
                        closeGatt()
                        setState(State.IDLE)
                        if (wasConnected) {
                            callback?.onDisconnected()
                            callback?.onLog("连接已断开")
                        } else {
                            callback?.onError(
                                if (status == BluetoothGatt.GATT_SUCCESS) "连接已断开"
                                else "连接失败 (status=$status)，请靠近小车重试"
                            )
                        }
                    }

                    status != BluetoothGatt.GATT_SUCCESS -> {
                        failConnection(gatt, "连接失败 (status=$status)")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            mainHandler.post {
                if (gatt !== bluetoothGatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection(gatt, "读取服务失败 (status=$status)")
                    return@post
                }

                // 1. 尝试标准服务与特征
                var characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)

                // 2. 尝试历史固件服务与特征 (固件小端宏倒序)
                if (characteristic == null) {
                    characteristic = gatt.getService(LEGACY_SERVICE_UUID)?.getCharacteristic(LEGACY_CHARACTERISTIC_UUID)
                }

                // 3. 通用可写特征兜底查找 (排除系统标准 GATT 服务 0x1800/0x1801)
                if (characteristic == null) {
                    for (service in gatt.services) {
                        val sUuid = service.uuid.toString().lowercase()
                        if (sUuid.startsWith("00001800") || sUuid.startsWith("00001801")) {
                            continue
                        }
                        for (c in service.characteristics) {
                            val props = c.properties
                            val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                                (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                            if (canWrite) {
                                characteristic = c
                                break
                            }
                        }
                        if (characteristic != null) break
                    }
                }

                if (characteristic == null) {
                    failConnection(gatt, "这不是小车设备（未找到控制服务）")
                    return@post
                }

                mainHandler.removeCallbacks(connectTimeout)
                writeCharacteristic = characteristic
                if (hasPermissions()) {
                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    } catch (_: SecurityException) {
                    }
                }
                setState(State.CONNECTED)
                val name = deviceName(gatt.device)
                callback?.onConnected(name, gatt.device.address)
                callback?.onLog("已连接 $name")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            mainHandler.post {
                if (gatt !== bluetoothGatt) return@post
                mainHandler.removeCallbacks(writeTimeout)
                writeInFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    callback?.onError("指令发送失败 (status=$status)")
                    writeQueue.clear()
                }
                drainWrites()
            }
        }
    }

    private fun failConnection(gatt: BluetoothGatt, message: String) {
        if (gatt === bluetoothGatt) {
            closeGatt()
            setState(State.IDLE)
            callback?.onError(message)
        }
    }

    private fun deviceName(device: BluetoothDevice): String = try {
        device.name?.takeIf { it.isNotBlank() } ?: device.address
    } catch (_: SecurityException) {
        device.address
    }

    private fun closeGatt() {
        mainHandler.removeCallbacks(connectTimeout)
        mainHandler.removeCallbacks(writeTimeout)
        writeInFlight = false
        writeQueue.clear()
        writeCharacteristic = null
        val gatt = bluetoothGatt
        bluetoothGatt = null
        try {
            gatt?.disconnect()
        } catch (_: SecurityException) {
        }
        try {
            gatt?.close()
        } catch (_: SecurityException) {
        }
    }

    // ========================= 发送 =========================

    fun send(command: String, urgent: Boolean = false) {
        if (state != State.CONNECTED) {
            callback?.onError("未连接小车")
            return
        }
        if (urgent) {
            writeQueue.clear()
        }
        if (writeQueue.lastOrNull() == command) return
        if (writeQueue.size >= MAX_QUEUE) {
            writeQueue.removeFirst()
        }
        writeQueue.addLast(command)
        drainWrites()
    }

    private fun drainWrites() {
        if (writeInFlight || state != State.CONNECTED) return
        val command = writeQueue.removeFirstOrNull() ?: return
        val gatt = bluetoothGatt ?: return
        val characteristic = writeCharacteristic ?: return
        val payload = command.toByteArray(Charsets.UTF_8)

        val props = characteristic.properties
        val canWriteWithResp = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val writeType = if (canWriteWithResp) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        val accepted = try {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(characteristic, payload, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (e: SecurityException) {
            false
        }

        if (accepted) {
            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                // WRITE_NO_RESPONSE 不产生系统 onCharacteristicWrite 回调，间隔 20ms 发送下一条
                mainHandler.postDelayed({ drainWrites() }, 20)
            } else {
                writeInFlight = true
                mainHandler.postDelayed(writeTimeout, WRITE_TIMEOUT_MS)
            }
        } else {
            callback?.onError("指令发送失败，请重试")
            mainHandler.postDelayed({ drainWrites() }, 120)
        }
    }

    // ========================= 断开 =========================

    fun disconnect() {
        stopScanInternal()
        val wasConnected = state == State.CONNECTED || state == State.CONNECTING
        closeGatt()
        setState(State.IDLE)
        if (wasConnected) {
            callback?.onDisconnected()
            callback?.onLog("已断开连接")
        }
    }

    fun release() {
        stopScanInternal()
        closeGatt()
        state = State.IDLE
        callback = null
    }

    private fun setState(newState: State) {
        if (state != newState) {
            state = newState
            callback?.onStateChanged(newState)
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
