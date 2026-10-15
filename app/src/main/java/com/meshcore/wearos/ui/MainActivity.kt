package com.meshcore.wearos.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.meshcore.wearos.service.MeshCoreBleService
import com.meshcore.wearos.ui.screens.ChannelChatScreen
import com.meshcore.wearos.ui.screens.ChannelsScreen
import com.meshcore.wearos.ui.screens.ChatScreen
import com.meshcore.wearos.ui.screens.ContactsScreen
import com.meshcore.wearos.ui.screens.DeviceInfoScreen
import com.meshcore.wearos.ui.screens.HomeScreen
import com.meshcore.wearos.ui.screens.ScanScreen
import com.meshcore.wearos.ui.screens.SettingsScreen
import com.meshcore.wearos.ui.theme.MeshCoreTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val pendingConversation = mutableStateOf<String?>(null)
    private val permissionsGranted = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onPermissionsResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingConversation.value = intent?.getStringExtra(EXTRA_CONVERSATION)

        if (viewModel.hasPermissions()) onPermissionsResult()
        else permissionLauncher.launch(requiredPermissions())

        // decided once - changing the NavHost start destination later resets the back stack
        val startDestination = if (viewModel.hasSavedDevice) Screen.HOME else Screen.SCAN

        setContent {
            MeshCoreTheme {
                val navController = rememberSwipeDismissableNavController()
                val granted = permissionsGranted.value

                LaunchedEffect(Unit) {
                    viewModel.toasts.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
                }

                // deep link from a message notification
                val pending = pendingConversation.value
                LaunchedEffect(pending) {
                    Screen.routeForConversation(pending)?.let { route ->
                        navController.navigate(route) { launchSingleTop = true }
                    }
                    pendingConversation.value = null
                }

                SwipeDismissableNavHost(navController = navController, startDestination = startDestination) {
                    composable(Screen.SCAN) {
                        ScanScreen(
                            viewModel = viewModel,
                            permissionsGranted = granted,
                            onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                            onDeviceConnected = {
                                navController.navigate(Screen.HOME) {
                                    popUpTo(navController.graph.id) { inclusive = true }
                                }
                            }
                        )
                    }
                    composable(Screen.HOME) {
                        HomeScreen(
                            viewModel = viewModel,
                            onNavigateToContacts = { navController.navigate(Screen.CONTACTS) },
                            onNavigateToChannels = { navController.navigate(Screen.CHANNELS) },
                            onNavigateToDeviceInfo = { navController.navigate(Screen.DEVICE_INFO) },
                            onNavigateToSettings = { navController.navigate(Screen.SETTINGS) },
                            onChooseDevice = { navController.navigate(Screen.SCAN) }
                        )
                    }
                    composable(Screen.CONTACTS) {
                        ContactsScreen(
                            viewModel = viewModel,
                            onContactSelected = { navController.navigate(Screen.chatRoute(it)) }
                        )
                    }
                    composable(
                        Screen.CHAT,
                        arguments = listOf(navArgument("pubKeyHex") { type = NavType.StringType })
                    ) { entry ->
                        val key = entry.arguments?.getString("pubKeyHex") ?: return@composable
                        ChatScreen(viewModel = viewModel, pubKeyHex = key)
                    }
                    composable(Screen.CHANNELS) {
                        ChannelsScreen(
                            viewModel = viewModel,
                            onChannelSelected = { navController.navigate(Screen.channelChatRoute(it)) }
                        )
                    }
                    composable(
                        Screen.CHANNEL_CHAT,
                        arguments = listOf(navArgument("channelIndex") { type = NavType.IntType })
                    ) { entry ->
                        val index = entry.arguments?.getInt("channelIndex") ?: 0
                        ChannelChatScreen(viewModel = viewModel, channelIndex = index)
                    }
                    composable(Screen.DEVICE_INFO) {
                        DeviceInfoScreen(viewModel = viewModel)
                    }
                    composable(Screen.SETTINGS) {
                        SettingsScreen(
                            viewModel = viewModel,
                            onForgetDevice = {
                                viewModel.forgetDevice()
                                navController.navigate(Screen.SCAN) {
                                    popUpTo(navController.graph.id) { inclusive = true }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CONVERSATION)?.let { pendingConversation.value = it }
    }

    private fun onPermissionsResult() {
        val granted = viewModel.hasPermissions()
        permissionsGranted.value = granted
        if (granted) {
            MeshCoreBleService.start(this)
            viewModel.reconnect()
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    companion object {
        const val EXTRA_CONVERSATION = "conversation"
    }
}
