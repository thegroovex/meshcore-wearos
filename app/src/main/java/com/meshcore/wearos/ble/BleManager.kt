package com.meshcore.wearos.ble

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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed class BleConnectionState {
    data object Disconnected : BleConnectionState()
    data object Scanning : BleConnectionState()
    data class Connecting(val deviceName: String, val deviceAddress: String) : BleConnectionState()
    /** Waiting for the user to enter the radio's BLE PIN in the system pairing dialog. */
    data class Pairing(val deviceName: String, val deviceAddress: String) : BleConnectionState()
    data class Connected(val deviceName: String, val deviceAddress: String) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice
)

/**
 * BLE transport to a MeshCore companion radio.
 *
 * Responsibilities:
 *  - scanning (NUS service filter)
 *  - bonding: the firmware protects the NUS characteristics with MITM encryption, so the
 *    watch has to be bonded (PIN from the radio's display / default 123456) before any
 *    GATT operation succeeds
 *  - MTU negotiation (frames are up to 176 bytes; default MTU would truncate them)
 *  - serialising GATT writes: Android allows only one outstanding GATT operation, the old
 *    code fired writes back-to-back and silently lost commands
 *  - automatic reconnect with back-off
 *
 * Every notification is handed out unchanged as one frame.
 */
@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "MeshCore-BLE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val prefs = context.getSharedPreferences("meshcore_ble", Context.MODE_PRIVATE)

    private val serviceUuid = UUID.fromString(MeshCoreProtocol.NUS_SERVICE_UUID)
    private val rxUuid = UUID.fromString(MeshCoreProtocol.NUS_RX_CHAR_UUID)
    private val txUuid = UUID.fromString(MeshCoreProtocol.NUS_TX_CHAR_UUID)
    private val cccdUuid = UUID.fromString(MeshCoreProtocol.CCCD_UUID)

    @Volatile private var bluetoothGatt: BluetoothGatt? = null
    @Volatile private var rxCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var txCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null
    @Volatile private var isScanning = false
    @Volatile private var autoReconnect = false
    @Volatile private var targetAddress: String? = null
    @Volatile private var targetName: String = ""
    @Volatile private var setupStarted = false
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var writerJob: Job? = null

    private var outgoing = Channel<ByteArray>(Channel.UNLIMITED)
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    /** Every received notification, in order, one element per frame. */
    val incomingFrames: Flow<ByteArray> = incoming.receiveAsFlow()

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _mtu = MutableStateFlow(23)
    val mtu: StateFlow<Int> = _mtu.asStateFlow()

    /** Address of the radio we last connected to successfully (survives app restarts). */
    val savedDeviceAddress: String? get() = prefs.getString(KEY_LAST_ADDRESS, null)
    val savedDeviceName: String? get() = prefs.getString(KEY_LAST_NAME, null)

    fun hasPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    // -------------------------------------------------------------------------
    // Scanning
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions()) {
            _connectionState.value = BleConnectionState.Error("Bluetooth permission missing")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null) {
            _connectionState.value = BleConnectionState.Error("Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            _connectionState.value = BleConnectionState.Error("Bluetooth is off")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            _connectionState.value = BleConnectionState.Error("Bluetooth not ready")
            return
        }
        if (_connectionState.value is BleConnectionState.Connected) return

        stopScanInternal()
        _scannedDevices.value = emptyList()

        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            isScanning = true
            _connectionState.value = BleConnectionState.Scanning
        } catch (e: Exception) {
            Log.e(tag, "startScan failed", e)
            _connectionState.value = BleConnectionState.Error("Scan failed: ${e.message}")
            return
        }
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_DURATION_MS)
            stopScan()
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        stopScanInternal()
        if (_connectionState.value is BleConnectionState.Scanning) {
            _connectionState.value = BleConnectionState.Disconnected
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        if (!isScanning) return
        isScanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(tag, "stopScan: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: safeName(device) ?: device.address
            val entry = BleDevice(name, device.address, result.rssi, device)
            val list = _scannedDevices.value.filterNot { it.address == entry.address } + entry
            _scannedDevices.value = list.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(tag, "BLE scan failed: $errorCode")
            isScanning = false
            _connectionState.value = BleConnectionState.Error(
                when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "Scan already running"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Scan failed - toggle Bluetooth and retry"
                    else -> "Scan failed (code $errorCode)"
                }
            )
        }
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    fun connect(address: String, name: String? = null) {
        if (!hasPermissions()) {
            _connectionState.value = BleConnectionState.Error("Bluetooth permission missing")
            return
        }
        stopScan()
        reconnectJob?.cancel()
        reconnectAttempt = 0
        autoReconnect = true
        targetAddress = address
        targetName = name ?: _scannedDevices.value.firstOrNull { it.address == address }?.name
            ?: savedDeviceName?.takeIf { savedDeviceAddress == address } ?: address
        openGatt(autoConnect = false)
    }

    /** Reconnect to the last used radio, e.g. after app start. */
    fun connectToSavedDevice(): Boolean {
        val address = savedDeviceAddress ?: return false
        val state = _connectionState.value
        if (state is BleConnectionState.Connected || state is BleConnectionState.Connecting ||
            state is BleConnectionState.Pairing) return true
        connect(address, savedDeviceName)
        return true
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        val gatt = bluetoothGatt
        if (gatt != null) {
            try { gatt.disconnect() } catch (_: Exception) {}
        }
        cleanup()
        _connectionState.value = BleConnectionState.Disconnected
    }

    /** Disconnect and forget the saved radio. */
    fun forgetDevice() {
        disconnect()
        prefs.edit().remove(KEY_LAST_ADDRESS).remove(KEY_LAST_NAME).apply()
    }

    @SuppressLint("MissingPermission")
    private fun openGatt(autoConnect: Boolean) {
        val address = targetAddress ?: return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = BleConnectionState.Error("Bluetooth is off")
            scheduleReconnect()
            return
        }
        cleanup()
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            _connectionState.value = BleConnectionState.Error("Invalid address")
            return
        }
        _connectionState.value = BleConnectionState.Connecting(targetName, address)
        Log.i(tag, "connectGatt($address, autoConnect=$autoConnect)")
        bluetoothGatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun cleanup() {
        writerJob?.cancel()
        writerJob = null
        pendingWrite?.complete(false)
        pendingWrite = null
        outgoing.close()
        outgoing = Channel(Channel.UNLIMITED)
        setupStarted = false
        rxCharacteristic = null
        txCharacteristic = null
        bluetoothGatt?.let {
            try { it.close() } catch (_: Exception) {}
        }
        bluetoothGatt = null
    }

    private fun scheduleReconnect() {
        if (!autoReconnect || targetAddress == null) return
        reconnectJob?.cancel()
        val wait = (RECONNECT_BASE_MS shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(RECONNECT_MAX_MS)
        reconnectAttempt++
        Log.i(tag, "Reconnect #$reconnectAttempt in ${wait}ms")
        reconnectJob = scope.launch {
            delay(wait)
            if (autoReconnect) {
                // after a few fast attempts let the BT stack wait for the radio (low power)
                openGatt(autoConnect = reconnectAttempt > 3)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSetup(gatt: BluetoothGatt) {
        if (setupStarted) return
        setupStarted = true
        // request a big MTU first; services are discovered in onMtuChanged
        if (!gatt.requestMtu(MeshCoreProtocol.DESIRED_MTU)) {
            gatt.discoverServices()
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            if (device?.address != targetAddress) return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            Log.i(tag, "Bond state -> $state")
            val gatt = bluetoothGatt ?: return
            when (state) {
                BluetoothDevice.BOND_BONDED -> startSetup(gatt)
                BluetoothDevice.BOND_NONE -> {
                    if (_connectionState.value is BleConnectionState.Pairing) {
                        _connectionState.value = BleConnectionState.Error("Pairing failed - check the PIN")
                        autoReconnect = false
                        try { gatt.disconnect() } catch (_: Exception) {}
                        cleanup()
                    }
                }
            }
        }
    }

    /**
     * BLE PIN of the radio (shown on its display; firmware default 123456). It is entered
     * automatically when Android asks for it, so nobody has to type 6 digits on a watch.
     */
    var pairingPin: String
        get() = prefs.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        set(value) { prefs.edit().putString(KEY_PIN, value.filter { it.isDigit() }.take(6)).apply() }

    private val pairingReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            if (device == null || device.address != targetAddress) return
            val pin = pairingPin
            if (pin.length != 6) return
            try {
                if (device.setPin(pin.toByteArray(Charsets.US_ASCII))) {
                    Log.i(tag, "PIN supplied automatically")
                    if (isOrderedBroadcast) abortBroadcast()
                }
            } catch (e: Exception) {
                // fall back to the system pairing dialog
                Log.w(tag, "setPin failed: ${e.message}")
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            context,
            pairingReceiver,
            IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply { priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1 },
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt != bluetoothGatt) {
                gatt.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(tag, "GATT connected")
                reconnectAttempt = 0
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                when (gatt.device.bondState) {
                    BluetoothDevice.BOND_BONDED -> startSetup(gatt)
                    BluetoothDevice.BOND_BONDING ->
                        _connectionState.value = BleConnectionState.Pairing(targetName, gatt.device.address)
                    else -> {
                        _connectionState.value = BleConnectionState.Pairing(targetName, gatt.device.address)
                        if (!gatt.device.createBond()) startSetup(gatt) // let the stack pair on demand
                    }
                }
            } else {
                Log.i(tag, "GATT disconnected (status=$status, state=$newState)")
                val wasPairing = _connectionState.value is BleConnectionState.Pairing
                cleanup()
                if (autoReconnect) {
                    _connectionState.value = if (wasPairing && status != BluetoothGatt.GATT_SUCCESS)
                        BleConnectionState.Error("Pairing failed (status $status)") else BleConnectionState.Disconnected
                    if (!wasPairing) scheduleReconnect() else autoReconnect = false
                } else {
                    _connectionState.value = BleConnectionState.Disconnected
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(tag, "MTU=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) _mtu.value = mtu
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(serviceUuid)
            val rx = service?.getCharacteristic(rxUuid)
            val tx = service?.getCharacteristic(txUuid)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null || tx == null) {
                Log.e(tag, "NUS service not found (status=$status)")
                _connectionState.value = BleConnectionState.Error("Not a MeshCore companion radio")
                autoReconnect = false
                gatt.disconnect()
                return
            }
            rxCharacteristic = rx
            txCharacteristic = tx
            gatt.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(cccdUuid)
            if (cccd == null) {
                onReady(gatt)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onReady(gatt)
            } else {
                Log.e(tag, "Enabling notifications failed: $status")
                // insufficient authentication/encryption -> bond and retry
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    setupStarted = false
                    _connectionState.value = BleConnectionState.Pairing(targetName, gatt.device.address)
                    gatt.device.createBond()
                } else {
                    gatt.disconnect()
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == txUuid) deliver(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == txUuid) {
                @Suppress("DEPRECATION")
                deliver(characteristic.value?.copyOf() ?: return)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun deliver(frame: ByteArray) {
        if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, "RX ${frame.toHex()}")
        incoming.trySend(frame)
    }

    @SuppressLint("MissingPermission")
    private fun onReady(gatt: BluetoothGatt) {
        val address = gatt.device.address
        prefs.edit().putString(KEY_LAST_ADDRESS, address).putString(KEY_LAST_NAME, targetName).apply()
        startWriter(gatt)
        _connectionState.value = BleConnectionState.Connected(targetName, address)
    }

    // -------------------------------------------------------------------------
    // Writing
    // -------------------------------------------------------------------------

    /** Queues one command frame. Returns false when not connected. */
    fun sendFrame(data: ByteArray): Boolean {
        if (_connectionState.value !is BleConnectionState.Connected) return false
        return outgoing.trySend(data).isSuccess
    }

    @SuppressLint("MissingPermission")
    private fun startWriter(gatt: BluetoothGatt) {
        writerJob?.cancel()
        val queue = outgoing
        writerJob = scope.launch {
            for (frame in queue) {
                val rx = rxCharacteristic ?: break
                val withResponse = rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                val writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                var ok = false
                for (attempt in 0 until 3) {
                    val done = CompletableDeferred<Boolean>()
                    pendingWrite = done
                    val started = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(rx, frame, writeType) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            rx.writeType = writeType
                            @Suppress("DEPRECATION")
                            rx.value = frame
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(rx)
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "write failed: ${e.message}")
                        false
                    }
                    if (started) {
                        ok = withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() } ?: false
                        if (ok) break
                    }
                    delay(50L * (attempt + 1)) // stack busy -> back off and retry
                }
                pendingWrite = null
                if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, "TX ${frame.toHex()} ok=$ok")
                if (!ok) Log.w(tag, "Dropped frame 0x%02x after retries".format(frame[0]))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String? =
        try { device.name } catch (_: SecurityException) { null }

    companion object {
        private const val KEY_LAST_ADDRESS = "last_address"
        private const val KEY_LAST_NAME = "last_name"
        private const val KEY_PIN = "ble_pin"
        const val DEFAULT_PIN = "123456"
        private const val SCAN_DURATION_MS = 20_000L
        private const val WRITE_TIMEOUT_MS = 3_000L
        private const val RECONNECT_BASE_MS = 2_000L
        private const val RECONNECT_MAX_MS = 30_000L
    }
}
