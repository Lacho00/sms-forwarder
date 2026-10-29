package dev.smsforwarder.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.SmsStatus

@Entity(
    tableName = "sms",
    indices = [Index(value = ["localKey"], unique = true), Index(value = ["receivedAt"])],
)
data class SmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localKey: String,
    val sender: String,
    val senderNormalized: String,
    val body: String,
    val receivedAt: Long,
    val phoneDate: Long?,
    val subId: Int?,
    val origin: SmsOrigin,
    val status: SmsStatus,
    val attempts: Int = 0,
    val lastHttpCode: Int? = null,
    val lastError: String? = null,
    val firstSeenAt: Long,
    val sentAt: Long? = null,
    val stuckNotified: Boolean = false,
) {
    fun toRecord(): SmsRecord = SmsRecord(sender, body, receivedAt, phoneDate, subId)
}
