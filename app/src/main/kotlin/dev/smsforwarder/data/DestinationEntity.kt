package dev.smsforwarder.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind

/** The URL and secret headers live in the secret store, not in this table. */
@Entity(tableName = "destination")
data class DestinationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val template: TemplateKind,
    val method: HttpMethod,
    val bodyTemplate: String?,
    val bodyIsJson: Boolean,
    /** JSON array of header name/value pairs that carry no secret. */
    val publicHeaders: String,
    val successPolicy: SuccessPolicy,
    val createdAt: Long,
    val deletedAt: Long? = null,
)
