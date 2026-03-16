package com.meshcore.wearos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.meshcore.wearos.data.model.Channel
import com.meshcore.wearos.data.model.Contact
import com.meshcore.wearos.data.model.Message
import kotlinx.coroutines.flow.Flow

// -------------------------------------------------------------------------
// Contact DAO
// -------------------------------------------------------------------------

@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY isFavorite DESC, lastMessageTimestamp DESC, lastSeen DESC")
    fun getAllContacts(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE publicKeyHex = :pubKeyHex LIMIT 1")
    suspend fun getContactByPubKey(pubKeyHex: String): Contact?

    @Query("SELECT * FROM contacts WHERE nodeType = 1 ORDER BY lastMessageTimestamp DESC")
    fun getChatContacts(): Flow<List<Contact>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateContact(contact: Contact)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateContacts(contacts: List<Contact>)

    @Update
    suspend fun updateContact(contact: Contact)

    @Query("DELETE FROM contacts WHERE publicKeyHex = :pubKeyHex")
    suspend fun deleteContact(pubKeyHex: String)

    @Query("DELETE FROM contacts")
    suspend fun deleteAllContacts()

    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun getContactCount(): Int

    @Query("UPDATE contacts SET unreadCount = 0 WHERE publicKeyHex = :pubKeyHex")
    suspend fun clearUnreadCount(pubKeyHex: String)

    @Query("""
        UPDATE contacts 
        SET lastMessageText = :text, lastMessageTimestamp = :timestamp, unreadCount = unreadCount + 1
        WHERE publicKeyHex = :pubKeyHex
    """)
    suspend fun updateLastMessage(pubKeyHex: String, text: String, timestamp: Long)
}

// -------------------------------------------------------------------------
// Message DAO
// -------------------------------------------------------------------------

@Dao
interface MessageDao {

    @Query("""
        SELECT * FROM messages 
        WHERE (senderPubKeyHex = :pubKeyHex OR recipientPubKeyHex = :pubKeyHex)
        AND isChannelMessage = 0
        ORDER BY timestamp ASC
    """)
    fun getMessagesForContact(pubKeyHex: String): Flow<List<Message>>

    @Query("""
        SELECT * FROM messages 
        WHERE isChannelMessage = 1 AND channelIndex = :channelIndex
        ORDER BY timestamp ASC
    """)
    fun getMessagesForChannel(channelIndex: Int): Flow<List<Message>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentMessages(limit: Int = 50): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message): Long

    @Query("UPDATE messages SET status = :status WHERE expectedAckCode = :ackCode")
    suspend fun updateMessageStatusByAck(ackCode: Long, status: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Long)

    @Query("DELETE FROM messages WHERE senderPubKeyHex = :pubKeyHex OR recipientPubKeyHex = :pubKeyHex")
    suspend fun deleteMessagesForContact(pubKeyHex: String)

    @Query("SELECT COUNT(*) FROM messages WHERE isChannelMessage = 0 AND status = 'SENT'")
    suspend fun getUnreadDirectMessageCount(): Int
}

// -------------------------------------------------------------------------
// Channel DAO
// -------------------------------------------------------------------------

@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels ORDER BY `index` ASC")
    fun getAllChannels(): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE `index` = :channelIndex LIMIT 1")
    suspend fun getChannel(channelIndex: Int): Channel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateChannel(channel: Channel)

    @Query("UPDATE channels SET unreadCount = 0 WHERE `index` = :channelIndex")
    suspend fun clearChannelUnreadCount(channelIndex: Int)

    @Query("""
        UPDATE channels 
        SET lastMessageText = :text, lastMessageTimestamp = :timestamp, unreadCount = unreadCount + 1
        WHERE `index` = :channelIndex
    """)
    suspend fun updateChannelLastMessage(channelIndex: Int, text: String, timestamp: Long)
}
