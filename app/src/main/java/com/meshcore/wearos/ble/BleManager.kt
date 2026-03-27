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
    private var isScanning = false
    private var lastConnectedAddress: String? = null
    private var isAutoReconnectEnabled = false

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingFrames: SharedFlow<ByteArray> = _incomingFrames.asSharedFlow()

    @SuppressLint("MissingPermission")
    fun startScan() {
        scope.launch {
            val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
                _connectionState.value = BleConnectionState.Error("Bluetooth not available")
                return@launch
            }

            if (bluetoothAdapter?.isEnabled != true) {
                _connectionState.value = BleConnectionState.Error("Bluetooth is disabled")
                return@launch
            }

            if (isScanning) {
                Log.d(TAG, "Scan already in progress, stopping first")
                stopScan()
                delay(500)
            }

            _scannedDevices.value = emptyList()
            _connectionState.value = BleConnectionState.Scanning

            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(NUS_SERVICE_UUID))
                .build()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            try {
                isScanning = true
                scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
                delay(30_000)
                if (isScanning) stopScan()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start scan: ${e.message}")
                isScanning = false
                _connectionState.value = BleConnectionState.Error("Scan start failed: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}")
        } finally {
            isScanning = false
            if (_connectionState.value is BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Disconnected
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Unknown"
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
            isScanning = false
            _connectionState.value = BleConnectionState.Error("Scan failed (code $errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BleDevice) {
        stopScan()
        lastConnectedAddress = device.address
        isAutoReconnectEnabled = true
        _connectionState.value = BleConnectionState.Connecting(device.name, device.address)

        // Use TRANSPORT_LE and autoConnect = true for better background stability
        bluetoothGatt = device.device.connectGatt(
            context,
            true, // autoConnect = true is better for background/standby reconnection
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
        isAutoReconnectEnabled = false
        bluetoothGatt?.disconnect()
    }

    @SuppressLint("MissingPermission")
    private fun close() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server, discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server, status: $status")
                    _connectionState.value = BleConnectionState.Disconnected
                    close()
                    
                    // Trigger auto-reconnect if enabled and it wasn't a manual disconnect
                    if (isAutoReconnectEnabled && lastConnectedAddress != null) {
                        Log.i(TAG, "Attempting auto-reconnect to $lastConnectedAddress")
                        scope.launch {
                            delay(2000)
                            connectByAddress(lastConnectedAddress!!)
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.Error("Service discovery failed")
                return
            }

            val nusService = gatt.getService(UUID.fromString(NUS_SERVICE_UUID))
            if (nusService == null) {
                _connectionState.value = BleConnectionState.Error("NUS service not found")
                disconnect()
                return
            }

            rxCharacteristic = nusService.getCharacteristic(UUID.fromString(NUS_RX_CHAR_UUID))
            txCharacteristic = nusService.getCharacteristic(UUID.fromString(NUS_TX_CHAR_UUID))

            if (rxCharacteristic == null || txCharacteristic == null) {
                _connectionState.value = BleConnectionState.Error("NUS characteristics not found")
                disconnect()
                return
            }

            gatt.setCharacteristicNotification(txCharacteristic!!, true)
            val descriptor = txCharacteristic!!.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == UUID.fromString(NUS_TX_CHAR_UUID)) {
                Log.d(TAG, "RX NUS Frame: ${value.joinToString(", ") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }}")
                scope.launch { _incomingFrames.emit(value) }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (characteristic.uuid == UUID.fromString(NUS_TX_CHAR_UUID)) {
                    val value = characteristic.value
                    Log.d(TAG, "RX NUS Frame (Legacy): ${value.joinToString(", ") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }}")
                    scope.launch { _incomingFrames.emit(value) }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val deviceName = gatt.device.name ?: gatt.device.address
                _connectionState.value = BleConnectionState.Connected(deviceName, gatt.device.address)
            }
        }
    }

    fun sendFrame(data: ByteArray): Boolean = sendData(data)

    @SuppressLint("MissingPermission")
    fun sendData(data: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = rxCharacteristic ?: return false
        
        Log.d(TAG, "TX NUS Frame: ${data.joinToString(", ") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }}")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            char.value = data
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(char)
        }
        return true
    }
}
