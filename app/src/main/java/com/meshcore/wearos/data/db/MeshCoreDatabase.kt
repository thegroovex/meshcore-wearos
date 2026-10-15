package com.meshcore.wearos.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.meshcore.wearos.data.model.Channel
import com.meshcore.wearos.data.model.Contact
import com.meshcore.wearos.data.model.Message
import com.meshcore.wearos.data.model.MessageDirection
import com.meshcore.wearos.data.model.MessageStatus
import com.meshcore.wearos.data.model.NodeType

/**
 * Room type converters for custom data types used in database entities.
 */
class MeshCoreTypeConverters {

    @TypeConverter
    fun fromNodeType(value: NodeType): Int = value.advType

    @TypeConverter
    fun toNodeType(value: Int): NodeType = NodeType.fromAdvType(value)

    @TypeConverter
    fun fromMessageDirection(value: MessageDirection): String = value.name

    @TypeConverter
    fun toMessageDirection(value: String): MessageDirection = MessageDirection.valueOf(value)

    @TypeConverter
    fun fromMessageStatus(value: MessageStatus): String = value.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus = MessageStatus.valueOf(value)

}

/**
 * Main Room database for the MeshCore WearOS client.
 * Stores contacts, messages, and channel information locally on the watch.
 */
@Database(
    entities = [Contact::class, Message::class, Channel::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(MeshCoreTypeConverters::class)
abstract class MeshCoreDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun channelDao(): ChannelDao
}
