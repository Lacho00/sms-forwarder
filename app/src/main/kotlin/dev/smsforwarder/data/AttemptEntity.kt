package dev.smsforwarder.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attempt",
    foreignKeys = [
        ForeignKey(
            entity = SmsEntity::class,
            parentColumns = ["id"],
            childColumns = ["smsId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["smsId"])],
)
data class AttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val smsId: Long,
    val at: Long,
    val httpCode: Int?,
    val error: String?,
)
