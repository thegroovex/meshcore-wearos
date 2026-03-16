package com.meshcore.wearos.ui

/**
 * Defines all navigation routes within the MeshCore WearOS app.
 */
object Screen {
    const val SCAN = "scan"
    const val HOME = "home"
    const val CONTACTS = "contacts"
    const val CHAT = "chat/{pubKeyHex}"
    const val CHANNELS = "channels"
    const val CHANNEL_CHAT = "channel/{channelIndex}"
    const val DEVICE_INFO = "device_info"
    const val SETTINGS = "settings"

    fun chatRoute(pubKeyHex: String) = "chat/$pubKeyHex"
    fun channelChatRoute(channelIndex: Int) = "channel/$channelIndex"
}
