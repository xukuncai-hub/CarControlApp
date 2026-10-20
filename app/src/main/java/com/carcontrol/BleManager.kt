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
        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

        const val SCAN_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val WRITE_TIMEOUT_MS = 800L
        private const val MAX_QUEUE = 6
    }

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED }

    interface Callback {
        fun onStateChanged(state: State)
        fun onScanResult(device: BleDevice)
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

    private val writeQueue = ArrayDeque<String>()
    private var writeInFlight = false

    private val scanTimeout = Runnable {
        if (state == State.SCANNING) {
            stopScanInternal()
            setState(State.IDLE)
            callback?.onScanFinished()
            callback?.onLog("扫描结束")
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
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
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
        stopScanInternal()
        setState(State.IDLE)
        callback?.onScanFinished()
    }

    private fun stopScanInternal() {
        mainHandler.removeCallbacks(scanTimeout)
        if (state == State.SCANNING) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (state != State.SCANNING) return
            val device = result.device ?: return
            val name = resolveName(device, result)
            val isCar = result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true ||
                name.contains("ESP32", ignoreCase = true)
            val found = BleDevice(name, device.address, result.rssi, isCar)
            val previous = seenDevices[device.address]
            seenDevices[device.address] = found
            if (previous == null ||
                previous.name != found.name ||
                previous.isCar != found.isCar ||
                kotlin.math.abs(previous.rssi - found.rssi) >= 4
            ) {
                mainHandler.post {
                    if (state == State.SCANNING) callback?.onScanResult(found)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.post {
                stopScanInternal()
                setState(State.IDLE)
                val msg = when (errorCode) {
                    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "扫描已在进行中"
                    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "扫描初始化失败"
                    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "不支持低功耗蓝牙扫描"
                    else -> "扫描失败 ($errorCode)"
                }
                callback?.onError(msg)
                callback?.onScanFinished()
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
                        try {
                            gatt.discoverServices()
                        } catch (_: SecurityException) {
                            failConnection(gatt, "无法读取设备服务")
                        }
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
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
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

        val accepted = try {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(
                    characteristic,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (e: SecurityException) {
            false
        }

        if (accepted) {
            writeInFlight = true
            mainHandler.postDelayed(writeTimeout, WRITE_TIMEOUT_MS)
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
