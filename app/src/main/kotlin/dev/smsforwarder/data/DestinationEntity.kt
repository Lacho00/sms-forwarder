package dev.smsforwarder.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

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

/** Parses [DestinationEntity.publicHeaders]; a malformed value yields no headers rather than a crash. */
fun DestinationEntity.publicHeaderList(): List<Pair<String, String>> = try {
    Json.parseToJsonElement(publicHeaders).jsonArray.map { pair ->
        val p = pair.jsonArray
        p[0].jsonPrimitive.content to p[1].jsonPrimitive.content
    }
} catch (e: Exception) {
    emptyList()
}
