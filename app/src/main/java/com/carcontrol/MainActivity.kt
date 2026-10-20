package com.carcontrol

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.carcontrol.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), BleManager.Callback {

    companion object {
        private const val PREFS = "car_control"
        private const val KEY_LAST_ADDRESS = "last_address"
        private const val KEY_LAST_NAME = "last_name"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_SPEED = "speed"

        private const val REPEAT_INTERVAL_MS = 160L
        private const val SPEED_SEND_DELAY_MS = 300L
        private const val PRESET_SLOW = 120
        private const val PRESET_STANDARD = 180
        private const val PRESET_FAST = 220
    }

    private enum class FirmwareMode { TRACKING, REMOTE, PERFORM }

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var deviceAdapter: ScanDeviceAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val logLines = ArrayDeque<String>()

    private var connected = false
    private var firmwareMode = FirmwareMode.REMOTE
    private var currentSpeed = 200
    private var repeatRunnable: Runnable? = null
    private var speedSendRunnable: Runnable? = null
    private var autoReconnectAttempted = false
    private var pendingScan = false
    private var pendingConnectAddress: String? = null
    private var actionAfterBluetooth: (() -> Unit)? = null
    private var lastErrorToastAt = 0L
    private var lastErrorMessage = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        bleManager = BleManager(this)
        bleManager.setCallback(this)

        setupDeviceList()
        setupConnectionActions()
        setupDriveControls()
        setupModes()
        setupPerformanceActions()
        setupSpeed()
        setupLog()

        binding.switchAutoReconnect.isChecked = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        binding.switchAutoReconnect.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTO_RECONNECT, checked).apply()
        }

        renderDisconnectedUi()
        appendLog(getString(R.string.msg_ready))

        if (bleManager.hasPermissions()) {
            maybeAutoReconnect()
        } else {
            requestBluetoothPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    override fun onDestroy() {
        stopRepeatTimer()
        speedSendRunnable?.let { mainHandler.removeCallbacks(it) }
        speedSendRunnable = null
        if (bleManager.state == BleManager.State.CONNECTED) {
            bleManager.send("1", urgent = true)
            bleManager.send("5")
            mainHandler.postDelayed({ bleManager.release() }, 300)
        } else {
            bleManager.release()
        }
        super.onDestroy()
    }

    // ==================== 界面初始化 ====================

    private fun applyWindowInsets() {
        val baseLeft = binding.root.paddingLeft
        val baseTop = binding.root.paddingTop
        val baseRight = binding.root.paddingRight
        val baseBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
    }

    private fun setupDeviceList() {
        deviceAdapter = ScanDeviceAdapter { device -> connectTo(device.address) }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.itemAnimator = null
        binding.rvDevices.adapter = deviceAdapter
    }

    private fun setupConnectionActions() {
        binding.btnScan.setOnClickListener { handleScanClick() }
        binding.btnScanNow.setOnClickListener { handleScanClick() }
        binding.btnDisconnect.setOnClickListener {
            haptic(it)
            bleManager.disconnect()
        }
        binding.btnReconnect.setOnClickListener { view ->
            haptic(view)
            val address = prefs.getString(KEY_LAST_ADDRESS, null) ?: return@setOnClickListener
            connectNow(address)
        }
    }

    private fun setupDriveControls() {
        bindHold(binding.btnForward) { pressDirection("3", getString(R.string.dir_forward)) }
        bindHold(binding.btnLeft) { pressDirection("2", getString(R.string.dir_left)) }
        bindHold(binding.btnRight) { pressDirection("4", getString(R.string.dir_right)) }
        binding.btnStop.setOnClickListener { view ->
            haptic(view)
            stopSequence()
        }
    }

    private fun setupModes() {
        binding.btnModeTrack.setOnClickListener { view ->
            haptic(view)
            if (!connected) return@setOnClickListener
            firmwareMode = FirmwareMode.TRACKING
            bleManager.send("0")
            setModeUi(FirmwareMode.TRACKING)
            setPerformanceRunning(false)
            setAction(getString(R.string.dir_idle), false)
            appendLog(getString(R.string.msg_mode_track))
        }
        binding.btnModeRemote.setOnClickListener { view ->
            haptic(view)
            if (!connected) return@setOnClickListener
            if (firmwareMode != FirmwareMode.REMOTE) {
                appendLog(getString(R.string.msg_mode_remote))
            }
            firmwareMode = FirmwareMode.REMOTE
            bleManager.send("1", urgent = true)
            setModeUi(FirmwareMode.REMOTE)
            setPerformanceRunning(false)
            setAction(getString(R.string.dir_idle), false)
        }
    }

    private fun setupPerformanceActions() {
        bindPerformance(binding.btnSpin, "6", R.string.perf_spin)
        bindPerformance(binding.btnDance, "7", R.string.perf_dance)
        bindPerformance(binding.btnZigzag, "8", R.string.perf_zigzag)
        bindPerformance(binding.btnFigure8, "9", R.string.perf_figure8)
        bindPerformance(binding.btnWave, "10", R.string.perf_wave)
        binding.btnBeep.setOnClickListener { view ->
            haptic(view)
            if (!connected) return@setOnClickListener
            bleManager.send("11")
            setAction(getString(R.string.perf_beep), true)
            appendLog(getString(R.string.msg_beep_reserved))
        }
    }

    private fun bindPerformance(button: MaterialButton, code: String, labelRes: Int) {
        button.setOnClickListener { view ->
            haptic(view)
            if (!connected) return@setOnClickListener
            val label = getString(labelRes)
            bleManager.send(code)
            firmwareMode = FirmwareMode.PERFORM
            setModeUi(FirmwareMode.PERFORM)
            setPerformanceRunning(true)
            setAction(label, true)
            appendLog(getString(R.string.msg_perf, label))
        }
    }

    private fun setupSpeed() {
        currentSpeed = prefs.getInt(KEY_SPEED, 200).coerceIn(60, 255)
        binding.speedSlider.value = currentSpeed.toFloat()
        binding.tvSpeed.text = getString(R.string.speed_value, currentSpeed)
        updateSpeedPresets(currentSpeed)

        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val speed = value.toInt()
            applySpeed(speed, send = false)
            scheduleSpeedSend(speed)
        }
        binding.btnSpeedSlow.setOnClickListener { onPresetSpeed(PRESET_SLOW) }
        binding.btnSpeedStandard.setOnClickListener { onPresetSpeed(PRESET_STANDARD) }
        binding.btnSpeedFast.setOnClickListener { onPresetSpeed(PRESET_FAST) }
    }

    private fun setupLog() {
        binding.btnClearLog.setOnClickListener {
            logLines.clear()
            binding.tvLog.setText(R.string.log_empty)
            haptic(it)
        }
    }

    // ==================== 扫描与连接 ====================

    private fun handleScanClick() {
        when (bleManager.state) {
            BleManager.State.SCANNING -> {
                bleManager.stopScan()
                appendLog(getString(R.string.msg_scan_cancelled))
                return
            }

            BleManager.State.CONNECTING -> {
                bleManager.disconnect()
                return
            }

            BleManager.State.CONNECTED -> return
            else -> Unit
        }

        autoReconnectAttempted = true
        if (!bleManager.hasPermissions()) {
            pendingScan = true
            requestBluetoothPermissions()
            return
        }
        if (Build.VERSION.SDK_INT < 31 && !isLocationEnabled()) {
            appendLog(getString(R.string.msg_location_off))
            toast(getString(R.string.msg_location_off))
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        ensureBluetoothOn { startScanNow() }
    }

    private fun startScanNow() {
        if (!bleManager.hasPermissions()) {
            pendingScan = true
            requestBluetoothPermissions()
            return
        }
        deviceAdapter.submitList(emptyList())
        binding.rvDevices.isVisible = false
        binding.tvScanEmpty.isVisible = false
        bleManager.startScan()
    }

    private fun connectTo(address: String) {
        connectNow(address)
    }

    private fun connectNow(address: String) {
        autoReconnectAttempted = true
        if (!bleManager.hasPermissions()) {
            pendingConnectAddress = address
            requestBluetoothPermissions()
            return
        }
        ensureBluetoothOn {
            if (bleManager.state == BleManager.State.IDLE) {
                bleManager.connect(address)
            }
        }
    }

    private fun maybeAutoReconnect() {
        if (autoReconnectAttempted) return
        if (!binding.switchAutoReconnect.isChecked) return
        val address = prefs.getString(KEY_LAST_ADDRESS, null) ?: return
        if (!bleManager.isBluetoothEnabled()) return
        autoReconnectAttempted = true
        appendLog(getString(R.string.msg_connecting_last))
        bleManager.connect(address)
    }

    private fun ensureBluetoothOn(action: () -> Unit) {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            appendLog(getString(R.string.msg_no_bluetooth))
            toast(getString(R.string.msg_no_bluetooth))
            return
        }
        if (!adapter.isEnabled) {
            actionAfterBluetooth = action
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        action()
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = actionAfterBluetooth
        actionAfterBluetooth = null
        if (result.resultCode == RESULT_OK) {
            appendLog(getString(R.string.msg_bluetooth_on))
            action?.invoke()
        } else {
            appendLog(getString(R.string.msg_bluetooth_off))
            toast(getString(R.string.msg_bluetooth_off))
            renderDisconnectedUi()
        }
    }

    // ==================== 权限 ====================

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    private fun requestBluetoothPermissions() {
        permissionsLauncher.launch(requiredPermissions())
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.isNotEmpty() && results.values.all { it } && bleManager.hasPermissions()) {
            appendLog("蓝牙权限已就绪")
            when {
                pendingConnectAddress != null -> {
                    val address = pendingConnectAddress
                    pendingConnectAddress = null
                    connectNow(address!!)
                }

                pendingScan -> {
                    pendingScan = false
                    ensureBluetoothOn { startScanNow() }
                }

                else -> maybeAutoReconnect()
            }
        } else {
            pendingScan = false
            pendingConnectAddress = null
            appendLog(getString(R.string.msg_permission_denied))
            toast(getString(R.string.msg_permission_denied))
            renderDisconnectedUi()
        }
    }

    private fun isLocationEnabled(): Boolean {
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            true
        }
    }

    // ==================== BLE 回调 ====================

    override fun onStateChanged(state: BleManager.State) {
        when (state) {
            BleManager.State.SCANNING -> showScanningUi()
            BleManager.State.CONNECTING -> showConnectingUi()
            BleManager.State.CONNECTED -> Unit
            BleManager.State.IDLE -> if (!connected) showIdleUi()
        }
    }

    override fun onScanResultsUpdated(devices: List<BleDevice>) {
        deviceAdapter.submitList(devices)
        if (devices.isNotEmpty() && !connected) {
            binding.rvDevices.isVisible = true
            binding.tvScanEmpty.isVisible = false
        }
    }

    override fun onScanFinished() {
        binding.btnScan.setText(R.string.btn_scan)
        binding.btnScan.setIconResource(R.drawable.ic_scan)
        binding.btnScan.isEnabled = !connected
        binding.scanProgressRow.isVisible = false
        binding.btnScanNow.isEnabled = true
        binding.tvScanEmpty.isVisible = deviceAdapter.itemCount == 0 && !connected
        binding.rvDevices.isVisible = deviceAdapter.itemCount > 0
        if (!connected) {
            binding.placeholderPanel.isVisible = true
            updateStatus(R.string.status_disconnected, R.color.idle_dot)
            updateLastDeviceRow()
        }
    }

    override fun onConnected(name: String, address: String) {
        connected = true
        firmwareMode = FirmwareMode.REMOTE
        setModeUi(FirmwareMode.REMOTE)
        setPerformanceRunning(false)
        setAction(getString(R.string.dir_idle), false)

        binding.tvConnectHint.text = getString(R.string.connect_hint_connected, name)
        binding.btnDisconnect.isVisible = true
        binding.btnScan.isEnabled = false
        binding.btnScan.setText(R.string.btn_scan)
        binding.btnScan.setIconResource(R.drawable.ic_scan)
        binding.scanProgressRow.isVisible = false
        binding.rvDevices.isVisible = false
        binding.tvScanEmpty.isVisible = false
        binding.lastDeviceRow.isVisible = false
        binding.placeholderPanel.isVisible = false
        binding.controlPanel.isVisible = true
        binding.btnScanNow.isEnabled = true
        updateStatus(R.string.status_connected, R.color.success)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        saveLastDevice(name, address)
        applySpeed(currentSpeed, send = false)

        bleManager.send("1", urgent = true)
        bleManager.send("s$currentSpeed")
        appendLog(getString(R.string.msg_connected_ready))
    }

    override fun onDisconnected() {
        connected = false
        stopRepeatTimer()
        setPerformanceRunning(false)
        setAction(getString(R.string.dir_idle), false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderDisconnectedUi()
    }

    override fun onError(message: String) {
        appendLog(message)
        val now = android.os.SystemClock.elapsedRealtime()
        if (message != lastErrorMessage || now - lastErrorToastAt > 2000) {
            toast(message)
            lastErrorMessage = message
            lastErrorToastAt = now
        }
        if (!connected && bleManager.state == BleManager.State.IDLE) {
            renderDisconnectedUi()
        }
    }

    override fun onLog(message: String) {
        appendLog(message)
    }

    // ==================== 界面状态 ====================

    private fun showScanningUi() {
        updateStatus(R.string.status_scanning, R.color.warning)
        binding.btnScan.setText(R.string.btn_scan_stop)
        binding.btnScan.setIconResource(R.drawable.ic_close)
        binding.btnScan.isEnabled = true
        binding.tvScanProgress.setText(R.string.scanning_hint)
        binding.scanProgressRow.isVisible = true
        binding.tvScanEmpty.isVisible = false
        binding.btnScanNow.isEnabled = false
        binding.lastDeviceRow.isVisible = false
        binding.placeholderPanel.isVisible = false
    }

    private fun showConnectingUi() {
        updateStatus(R.string.status_connecting, R.color.secondary)
        binding.btnScan.setText(R.string.btn_cancel)
        binding.btnScan.setIconResource(R.drawable.ic_close)
        binding.btnScan.isEnabled = true
        binding.tvConnectHint.setText(R.string.connect_hint_connecting)
        binding.tvScanProgress.setText(R.string.connect_hint_connecting)
        binding.scanProgressRow.isVisible = true
        binding.tvScanEmpty.isVisible = false
        binding.rvDevices.isVisible = false
        binding.lastDeviceRow.isVisible = false
        binding.placeholderPanel.isVisible = false
    }

    private fun showIdleUi() {
        binding.scanProgressRow.isVisible = false
        binding.btnScan.setText(R.string.btn_scan)
        binding.btnScan.setIconResource(R.drawable.ic_scan)
        binding.btnScan.isEnabled = !connected
        binding.btnScanNow.isEnabled = true
        if (!connected) {
            binding.placeholderPanel.isVisible = true
            updateStatus(R.string.status_disconnected, R.color.idle_dot)
            updateLastDeviceRow()
        }
    }

    private fun renderDisconnectedUi() {
        binding.tvConnectHint.setText(R.string.connect_hint_disconnected)
        binding.btnDisconnect.isVisible = false
        binding.scanProgressRow.isVisible = false
        binding.btnScan.setText(R.string.btn_scan)
        binding.btnScan.setIconResource(R.drawable.ic_scan)
        binding.btnScan.isEnabled = bleManager.state == BleManager.State.IDLE
        binding.btnScanNow.isEnabled = true
        binding.placeholderPanel.isVisible = true
        binding.controlPanel.isVisible = false
        binding.lastDeviceRow.isVisible = false
        updateStatus(R.string.status_disconnected, R.color.idle_dot)
        updateLastDeviceRow()
    }

    private fun updateStatus(textRes: Int, colorRes: Int) {
        val color = ContextCompat.getColor(this, colorRes)
        binding.tvStatus.setText(textRes)
        binding.tvStatus.setTextColor(color)
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun updateLastDeviceRow() {
        val name = prefs.getString(KEY_LAST_NAME, null)
        val address = prefs.getString(KEY_LAST_ADDRESS, null)
        val show = !connected &&
            address != null &&
            bleManager.state == BleManager.State.IDLE
        binding.lastDeviceRow.isVisible = show
        if (name != null) {
            binding.tvLastDevice.text = getString(R.string.last_device, name)
        }
    }

    private fun saveLastDevice(name: String, address: String) {
        prefs.edit()
            .putString(KEY_LAST_ADDRESS, address)
            .putString(KEY_LAST_NAME, name)
            .apply()
    }

    private fun setModeUi(mode: FirmwareMode) {
        binding.btnModeTrack.isChecked = mode == FirmwareMode.TRACKING
        binding.btnModeRemote.isChecked = mode == FirmwareMode.REMOTE
        binding.tvModeHint.setText(
            when (mode) {
                FirmwareMode.TRACKING -> R.string.mode_hint_track
                FirmwareMode.REMOTE -> R.string.mode_hint_remote
                FirmwareMode.PERFORM -> R.string.mode_hint_perform
            }
        )
    }

    private fun setPerformanceRunning(running: Boolean) {
        binding.tvPerfState.setText(if (running) R.string.perf_running else R.string.perf_idle)
        binding.tvPerfState.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.warning else R.color.text_secondary)
        )
        binding.tvPerfState.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.primary_container else R.color.surface_higher)
        )
    }

    private fun setAction(label: String, active: Boolean) {
        binding.tvAction.text = label
        binding.tvAction.setTextColor(
            ContextCompat.getColor(this, if (active) R.color.primary else R.color.text_secondary)
        )
    }

    // ==================== 驾驶指令 ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun bindHold(button: MaterialButton, action: () -> Unit) {
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!connected) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        return@setOnTouchListener true
                    }
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    view.isPressed = true
                    haptic(view)
                    startRepeat(action)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    view.isPressed = false
                    stopRepeatTimer()
                    if (connected) stopSequence()
                    true
                }

                else -> false
            }
        }
    }

    private fun startRepeat(action: () -> Unit) {
        stopRepeatTimer()
        action()
        val runnable = object : Runnable {
            override fun run() {
                if (!connected) {
                    stopRepeatTimer()
                    return
                }
                action()
                mainHandler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeatRunnable = runnable
        mainHandler.postDelayed(runnable, REPEAT_INTERVAL_MS)
    }

    private fun stopRepeatTimer() {
        repeatRunnable?.let { mainHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun pressDirection(code: String, label: String) {
        if (!connected) return
        ensureRemoteForDrive()
        setAction(label, true)
        bleManager.send(code)
    }

    private fun ensureRemoteForDrive() {
        if (firmwareMode == FirmwareMode.REMOTE) return
        firmwareMode = FirmwareMode.REMOTE
        bleManager.send("1", urgent = true)
        setModeUi(FirmwareMode.REMOTE)
        setPerformanceRunning(false)
        appendLog(getString(R.string.msg_switch_remote))
    }

    private fun stopSequence() {
        stopRepeatTimer()
        if (!connected) return
        if (firmwareMode != FirmwareMode.REMOTE) {
            bleManager.send("1", urgent = true)
            firmwareMode = FirmwareMode.REMOTE
            setModeUi(FirmwareMode.REMOTE)
        }
        bleManager.send("5")
        setAction(getString(R.string.btn_stop), true)
        setPerformanceRunning(false)
    }

    // ==================== 速度 ====================

    private fun onPresetSpeed(speed: Int) {
        haptic(binding.speedSlider)
        speedSendRunnable?.let { mainHandler.removeCallbacks(it) }
        speedSendRunnable = null
        applySpeed(speed, send = true)
    }

    private fun scheduleSpeedSend(speed: Int) {
        speedSendRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (connected) {
                bleManager.send("s$speed")
                appendLog(getString(R.string.msg_speed, speed))
            }
        }
        speedSendRunnable = runnable
        mainHandler.postDelayed(runnable, SPEED_SEND_DELAY_MS)
    }

    private fun applySpeed(speed: Int, send: Boolean) {
        val value = speed.coerceIn(60, 255)
        currentSpeed = value
        if (binding.speedSlider.value != value.toFloat()) {
            binding.speedSlider.value = value.toFloat()
        }
        binding.tvSpeed.text = getString(R.string.speed_value, value)
        updateSpeedPresets(value)
        prefs.edit().putInt(KEY_SPEED, value).apply()
        if (send && connected) {
            bleManager.send("s$value")
            appendLog(getString(R.string.msg_speed, value))
        }
    }

    private fun updateSpeedPresets(speed: Int) {
        binding.btnSpeedSlow.isChecked = speed == PRESET_SLOW
        binding.btnSpeedStandard.isChecked = speed == PRESET_STANDARD
        binding.btnSpeedFast.isChecked = speed == PRESET_FAST
    }

    // ==================== 日志与工具 ====================

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logLines.addLast("$time  $message")
        while (logLines.size > 6) {
            logLines.removeFirst()
        }
        binding.tvLog.text = logLines.joinToString("\n")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun haptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    appendLog(getString(R.string.msg_bluetooth_off_event))
                    if (bleManager.state != BleManager.State.IDLE) {
                        bleManager.disconnect()
                    }
                }

                BluetoothAdapter.STATE_ON -> {
                    appendLog(getString(R.string.msg_bluetooth_on))
                    maybeAutoReconnect()
                }
            }
        }
    }
}
