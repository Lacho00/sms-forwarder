package dev.smsforwarder.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.smsforwarder.core.DeliveryStatus

@Entity(
    tableName = "delivery",
    foreignKeys = [
        ForeignKey(
            entity = SmsEntity::class,
            parentColumns = ["id"],
            childColumns = ["smsId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DestinationEntity::class,
            parentColumns = ["id"],
            childColumns = ["destinationId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["smsId", "destinationId"], unique = true),
        Index(value = ["status"]),
    ],
)
data class DeliveryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val smsId: Long,
    val destinationId: Long?,
    /** Kept so the history still names the destination after it is deleted. */
    val destinationName: String,
    val status: DeliveryStatus,
    val attempts: Int = 0,
    val lastHttpCode: Int? = null,
    val lastError: String? = null,
    val sentAt: Long? = null,
)
