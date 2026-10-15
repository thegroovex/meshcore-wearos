package com.meshcore.wearos.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.meshcore.wearos.data.model.Channel
import com.meshcore.wearos.data.model.Contact
import com.meshcore.wearos.data.model.Message
import com.meshcore.wearos.data.model.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY isFavorite DESC, lastMessageTimestamp DESC, lastSeen DESC")
    fun getAllContacts(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE publicKeyHex = :pubKeyHex LIMIT 1")
    fun observeContact(pubKeyHex: String): Flow<Contact?>

    @Query("SELECT * FROM contacts WHERE publicKeyHex = :pubKeyHex LIMIT 1")
    suspend fun getContactByPubKey(pubKeyHex: String): Contact?

    @Query("SELECT * FROM contacts WHERE publicKeyHex LIKE :prefixHex || '%' LIMIT 1")
    suspend fun getContactByPrefix(prefixHex: String): Contact?

    @Query("SELECT publicKeyHex FROM contacts")
    suspend fun getAllKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateContact(contact: Contact)

    /** Insert/refresh radio data while keeping local-only fields (favorite, last message, unread). */
    @Transaction
    suspend fun upsertFromRadio(contact: Contact) {
        val existing = getContactByPubKey(contact.publicKeyHex)
        insertOrUpdateContact(
            if (existing == null) contact else contact.copy(
                isFavorite = existing.isFavorite,
                lastMessageText = existing.lastMessageText,
                lastMessageTimestamp = existing.lastMessageTimestamp,
                unreadCount = existing.unreadCount
            )
        )
    }

    @Query("DELETE FROM contacts WHERE publicKeyHex = :pubKeyHex")
    suspend fun deleteContact(pubKeyHex: String)

    @Query("DELETE FROM contacts WHERE publicKeyHex NOT IN (:keep)")
    suspend fun deleteAllExcept(keep: List<String>)

    @Query("DELETE FROM contacts")
    suspend fun deleteAllContacts()

    @Query("UPDATE contacts SET unreadCount = 0 WHERE publicKeyHex = :pubKeyHex")
    suspend fun clearUnreadCount(pubKeyHex: String)

    @Query("UPDATE contacts SET unreadCount = unreadCount + 1 WHERE publicKeyHex = :pubKeyHex")
    suspend fun incrementUnread(pubKeyHex: String)

    @Query("UPDATE contacts SET isFavorite = NOT isFavorite WHERE publicKeyHex = :pubKeyHex")
    suspend fun toggleFavorite(pubKeyHex: String)

    @Query("""
        UPDATE contacts
        SET lastMessageText = :text, lastMessageTimestamp = :timestamp,
            unreadCount = unreadCount + :unreadIncrement
        WHERE publicKeyHex = :pubKeyHex
    """)
    suspend fun updateLastMessage(pubKeyHex: String, text: String, timestamp: Long, unreadIncrement: Int)

    @Query("SELECT COALESCE(SUM(unreadCount), 0) FROM contacts")
    fun observeUnreadTotal(): Flow<Int>
}

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationKey = :key ORDER BY receivedAt ASC, id ASC")
    fun getMessages(key: String): Flow<List<Message>>

    @Query("SELECT * FROM messages ORDER BY receivedAt DESC LIMIT :limit")
    fun getRecentMessages(limit: Int = 50): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessage(id: Long): Message?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message): Long

    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE conversationKey = :key AND direction = 'INCOMING' AND timestamp = :timestamp AND text = :text
    """)
    suspend fun countDuplicates(key: String, timestamp: Long, text: String): Int

    @Query("UPDATE messages SET status = :status, expectedAckCode = :ackCode, attempt = :attempt WHERE id = :id")
    suspend fun updateSendState(id: Long, status: MessageStatus, ackCode: Long, attempt: Int)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: MessageStatus)

    @Query("UPDATE messages SET status = 'DELIVERED' WHERE expectedAckCode = :ackCode AND direction = 'OUTGOING' AND status != 'DELIVERED'")
    suspend fun markDeliveredByAck(ackCode: Long): Int

    @Query("UPDATE messages SET status = 'FAILED' WHERE status = 'SENDING'")
    suspend fun failStaleSending()

    @Query("UPDATE messages SET conversationKey = :newKey, senderPubKeyHex = :fullKey WHERE conversationKey = :oldKey")
    suspend fun reassignConversation(oldKey: String, newKey: String, fullKey: String)

    /** Conversations of senders that were unknown when the message arrived (6-byte key prefix). */
    @Query("SELECT DISTINCT conversationKey FROM messages WHERE conversationKey LIKE 'c:%' AND length(conversationKey) = 14")
    suspend fun getPrefixConversations(): List<String>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("DELETE FROM messages WHERE conversationKey = :key")
    suspend fun deleteConversation(key: String)
}

@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels ORDER BY `index` ASC")
    fun getAllChannels(): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE `index` = :channelIndex LIMIT 1")
    suspend fun getChannel(channelIndex: Int): Channel?

    @Query("SELECT * FROM channels WHERE `index` = :channelIndex LIMIT 1")
    fun observeChannel(channelIndex: Int): Flow<Channel?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateChannel(channel: Channel)

    @Transaction
    suspend fun upsertFromRadio(index: Int, name: String) {
        val existing = getChannel(index)
        insertOrUpdateChannel(existing?.copy(name = name) ?: Channel(index, name))
    }

    @Query("DELETE FROM channels WHERE `index` NOT IN (:keep)")
    suspend fun deleteAllExcept(keep: List<Int>)

    @Query("UPDATE channels SET unreadCount = 0 WHERE `index` = :channelIndex")
    suspend fun clearChannelUnreadCount(channelIndex: Int)

    @Query("""
        UPDATE channels
        SET lastMessageText = :text, lastMessageTimestamp = :timestamp, unreadCount = unreadCount + :unreadIncrement
        WHERE `index` = :channelIndex
    """)
    suspend fun updateChannelLastMessage(channelIndex: Int, text: String, timestamp: Long, unreadIncrement: Int)

    @Query("SELECT COALESCE(SUM(unreadCount), 0) FROM channels")
    fun observeUnreadTotal(): Flow<Int>
}
