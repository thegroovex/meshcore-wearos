package com.meshcore.wearos.ui

/** Navigation routes. */
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

    /** Maps a conversation key ("c:<hex>" / "ch:<n>") from a notification to a route. */
    fun routeForConversation(key: String?): String? = when {
        key == null -> null
        key.startsWith("ch:") -> key.removePrefix("ch:").toIntOrNull()?.let { channelChatRoute(it) }
        key.startsWith("c:") -> chatRoute(key.removePrefix("c:"))
        else -> null
    }
}
