package com.meshcore.wearos.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.meshcore.wearos.ble.BleConnectionState
import com.meshcore.wearos.service.MeshCoreBleService
import com.meshcore.wearos.ui.screens.ChatScreen
import com.meshcore.wearos.ui.screens.ContactsScreen
import com.meshcore.wearos.ui.screens.DeviceInfoScreen
import com.meshcore.wearos.ui.screens.HomeScreen
import com.meshcore.wearos.ui.screens.ScanScreen
import com.meshcore.wearos.ui.screens.SettingsScreen
import com.meshcore.wearos.ui.theme.MeshCoreTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * MainActivity is the single entry point for the MeshCore WearOS app.
 *
 * It sets up the Compose UI with Wear OS navigation, handles runtime permission
 * requests for Bluetooth, and manages the lifecycle of the BLE foreground service.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startBleService()

        setContent {
            MeshCoreTheme {
                val navController = rememberSwipeDismissableNavController()
                val connectionState by viewModel.connectionState.collectAsState()

                // Determine start destination based on connection state
                val startDestination = when (connectionState) {
                    is BleConnectionState.Connected -> Screen.HOME
                    else -> Screen.SCAN
                }

                SwipeDismissableNavHost(
                    navController = navController,
                    startDestination = startDestination
                ) {
                    composable(Screen.SCAN) {
                        ScanScreen(
                            viewModel = viewModel,
                            onDeviceConnected = {
                                navController.navigate(Screen.HOME) {
                                    popUpTo(Screen.SCAN) { inclusive = true }
                                }
                            }
                        )
                    }

                    composable(Screen.HOME) {
                        HomeScreen(
                            viewModel = viewModel,
                            onNavigateToContacts = { navController.navigate(Screen.CONTACTS) },
                            onNavigateToDeviceInfo = { navController.navigate(Screen.DEVICE_INFO) },
                            onNavigateToSettings = { navController.navigate(Screen.SETTINGS) },
                            onDisconnected = {
                                navController.navigate(Screen.SCAN) {
                                    popUpTo(Screen.HOME) { inclusive = true }
                                }
                            }
                        )
                    }

                    composable(Screen.CONTACTS) {
                        ContactsScreen(
                            viewModel = viewModel,
                            onContactSelected = { pubKeyHex ->
                                navController.navigate(Screen.chatRoute(pubKeyHex))
                            }
                        )
                    }

                    composable(Screen.CHAT) { backStackEntry ->
                        val pubKeyHex = backStackEntry.arguments?.getString("pubKeyHex") ?: return@composable
                        ChatScreen(
                            viewModel = viewModel,
                            pubKeyHex = pubKeyHex
                        )
                    }

                    composable(Screen.DEVICE_INFO) {
                        DeviceInfoScreen(viewModel = viewModel)
                    }

                    composable(Screen.SETTINGS) {
                        SettingsScreen(
                            viewModel = viewModel,
                            onDisconnect = {
                                viewModel.disconnect()
                                navController.navigate(Screen.SCAN) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requestBluetoothPermissions()
    }

    private fun requestBluetoothPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startBleService() {
        val intent = Intent(this, MeshCoreBleService::class.java).apply {
            action = MeshCoreBleService.ACTION_START
        }
        startForegroundService(intent)
    }
}
