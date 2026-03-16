package com.meshcore.wearos.ble

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
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.meshcore.wearos.ble.MeshCoreProtocol.NUS_RX_CHAR_UUID
import com.meshcore.wearos.ble.MeshCoreProtocol.NUS_SERVICE_UUID
import com.meshcore.wearos.ble.MeshCoreProtocol.NUS_TX_CHAR_UUID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents the current BLE connection state.
 */
sealed class BleConnectionState {
    data object Disconnected : BleConnectionState()
    data object Scanning : BleConnectionState()
    data class Connecting(val deviceName: String, val deviceAddress: String) : BleConnectionState()
    data class Connected(val deviceName: String, val deviceAddress: String) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}

/**
 * Represents a discovered BLE device during scanning.
 */
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice
)

/**
 * BleManager handles all Bluetooth Low Energy operations for the MeshCore WearOS client.
 *
 * It manages device scanning, GATT connection lifecycle, characteristic read/write operations,
 * and notification subscriptions for the Nordic UART Service (NUS) used by MeshCore devices.
 */
@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "MeshCore-BLE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    // Write queue to prevent concurrent BLE writes
    private val writeQueue = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    // -------------------------------------------------------------------------
    // Public state flows
    // -------------------------------------------------------------------------

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingFrames: SharedFlow<ByteArray> = _incomingFrames.asSharedFlow()

    // -------------------------------------------------------------------------
    // BLE Scan
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            _connectionState.value = BleConnectionState.Error("Bluetooth not available")
            return
        }

        _scannedDevices.value = emptyList()
        _connectionState.value = BleConnectionState.Scanning

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(NUS_SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)

        // Auto-stop scan after 30 seconds
        scope.launch {
            delay(30_000)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_connectionState.value is BleConnectionState.Scanning) {
            _connectionState.value = BleConnectionState.Disconnected
        }
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            val address = device.address

            val bleDevice = BleDevice(
                name = name,
                address = address,
                rssi = result.rssi,
                device = device
            )

            val current = _scannedDevices.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.address == address }
            if (existingIndex >= 0) {
                current[existingIndex] = bleDevice
            } else {
                current.add(bleDevice)
            }
            _scannedDevices.value = current.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
            _connectionState.value = BleConnectionState.Error("Scan failed (code $errorCode)")
        }
    }

    // -------------------------------------------------------------------------
    // GATT Connection
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun connect(device: BleDevice) {
        stopScan()
        _connectionState.value = BleConnectionState.Connecting(device.name, device.address)

        bluetoothGatt = device.device.connectGatt(
            context,
            false, // autoConnect = false for faster initial connection
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @SuppressLint("MissingPermission")
    fun connectByAddress(address: String) {
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        val bleDevice = BleDevice(
            name = device.name ?: address,
            address = address,
            rssi = 0,
            device = device
        )
        connect(bleDevice)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
    }

    @SuppressLint("MissingPermission")
    private fun close() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
    }

    // -------------------------------------------------------------------------
    // GATT Callback
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server, discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server")
                    _connectionState.value = BleConnectionState.Disconnected
                    close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed with status: $status")
                _connectionState.value = BleConnectionState.Error("Service discovery failed")
                return
            }

            val nusService = gatt.getService(UUID.fromString(NUS_SERVICE_UUID))
            if (nusService == null) {
                Log.e(TAG, "NUS service not found on device")
                _connectionState.value = BleConnectionState.Error("MeshCore NUS service not found")
                disconnect()
                return
            }

            rxCharacteristic = nusService.getCharacteristic(UUID.fromString(NUS_RX_CHAR_UUID))
            txCharacteristic = nusService.getCharacteristic(UUID.fromString(NUS_TX_CHAR_UUID))

            if (rxCharacteristic == null || txCharacteristic == null) {
                Log.e(TAG, "NUS characteristics not found")
                _connectionState.value = BleConnectionState.Error("NUS characteristics not found")
                disconnect()
                return
            }

            // Enable notifications on TX characteristic (radio -> app)
            enableNotifications(gatt, txCharacteristic!!)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == UUID.fromString(NUS_TX_CHAR_UUID)) {
                scope.launch {
                    _incomingFrames.emit(value)
                }
            }
        }

        @Deprecated("Deprecated in API 33, but needed for API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (characteristic.uuid == UUID.fromString(NUS_TX_CHAR_UUID)) {
                    scope.launch {
                        _incomingFrames.emit(characteristic.value)
                    }
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Notifications enabled, connection ready")
                val deviceName = gatt.device.name ?: gatt.device.address
                _connectionState.value = BleConnectionState.Connected(
                    deviceName = deviceName,
                    deviceAddress = gatt.device.address
                )
                // Start write queue processor
                scope.launch { processWriteQueue() }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed with status: $status")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification setup
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)

        val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val descriptor = characteristic.getDescriptor(cccdUuid) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Sends a command frame to the connected MeshCore device.
     * Frames are queued and sent sequentially to prevent BLE write conflicts.
     */
    fun sendFrame(frame: ByteArray) {
        scope.launch {
            writeQueue.send(frame)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun processWriteQueue() {
        for (frame in writeQueue) {
            val gatt = bluetoothGatt ?: break
            val rx = rxCharacteristic ?: break

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    rx,
                    frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
            } else {
                @Suppress("DEPRECATION")
                rx.value = frame
                @Suppress("DEPRECATION")
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(rx)
            }

            // Small delay between writes to avoid BLE congestion
            delay(50)
        }
    }

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true
}
