package dev.smsforwarder.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch

@Entity(tableName = "rule")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val senderMode: SenderMode,
    val senderPattern: String,
    val bodyMode: BodyMode?,
    val bodyPattern: String?,
    val sim: SimMatch,
    val createdAt: Long,
)

@Entity(
    tableName = "rule_destination",
    primaryKeys = ["ruleId", "destinationId"],
    foreignKeys = [
        ForeignKey(
            entity = RuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DestinationEntity::class,
            parentColumns = ["id"],
            childColumns = ["destinationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["destinationId"])],
)
data class RuleDestinationEntity(
    val ruleId: Long,
    val destinationId: Long,
)

data class RuleWithDestinations(
    @Embedded val rule: RuleEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = RuleDestinationEntity::class,
            parentColumn = "ruleId",
            entityColumn = "destinationId",
        ),
    )
    val destinations: List<DestinationEntity>,
)
