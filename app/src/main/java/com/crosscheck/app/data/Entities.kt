package com.crosscheck.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Source tags stored in [PaymentEntity.source] (SPEC section 1). */
object PaymentSource {
    const val SMS = "sms"
    const val NOTIFICATION = "notification"
}

/**
 * One confirmed incoming payment (Mode 1). Money is Long paise, timestamps are epoch millis UTC.
 * [utr] is normalised to digits only (or null when the alert carried no reference).
 */
@Entity(
    tableName = "payments",
    indices = [Index("utr"), Index("timestamp"), Index(value = ["amountPaise", "timestamp"])],
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountPaise: Long,
    val sender: String,
    val utr: String?,
    val timestamp: Long,
    val source: String,
    val raw: String,
)

/** How a payer key in `flagged_vpas` / `claims.payerKey` was derived. */
object PayerKeyKind {
    const val VPA = "VPA"
    const val NAME = "NAME"
    const val BANK_MASK = "BANK_MASK"
}

/**
 * Verification receipt written for every Mode 2 verdict (SPEC section 1).
 * [verdict] is one of "MATCH" | "LIKELY_MATCH" | "NO_MATCH"; [reason] is a Reason name or null.
 * [payerKey]/[keyKind] record which repeat-offender key (if any) this claim was counted under.
 */
@Entity(
    tableName = "claims",
    indices = [Index("createdAt"), Index("payerKey")],
)
data class ClaimEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountPaise: Long,
    val upiId: String?,
    val utr: String?,
    val claimedTimestamp: Long?,
    val verdict: String,
    val reason: String?,
    val createdAt: Long,
    val extractorBackend: String,
    val payerName: String?,
    val payerBankMask: String?,
    val payerKey: String?,
    val keyKind: String?,
)

/**
 * Repeat-offender memory keyed by a payer key: the payer's VPA when clearly theirs, else the
 * normalised payer name, else the bank mask (e.g. "SBI-XX4321"). [keyKind] is a [PayerKeyKind].
 */
@Entity(tableName = "flagged_vpas")
data class FlaggedVpaEntity(
    @PrimaryKey @ColumnInfo(name = "payerKey") val payerKey: String,
    val keyKind: String,
    val failCount: Int,
    val lastFailedAt: Long,
)
