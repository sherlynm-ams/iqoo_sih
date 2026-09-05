package com.crosscheck.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crosscheck.app.R
import com.crosscheck.app.data.ClaimEntity
import com.crosscheck.app.data.VerdictCode
import com.crosscheck.app.data.formatRupees
import com.crosscheck.app.di.AppContainer
import com.crosscheck.app.ui.theme.VerdictColors
import com.crosscheck.app.verify.Reason
import com.crosscheck.app.verify.VerdictSpeech

/**
 * Verification receipt (SPEC section 1): a formatted view of one `claims` row - the screenshot-able
 * evidence. No new pipeline; everything shown is what [ClaimEntity] already persisted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScreen(container: AppContainer, claimId: Long, onBack: () -> Unit) {
    var claim by remember { mutableStateOf<ClaimEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(claimId) {
        claim = container.claims.byId(claimId)
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.receipt_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val row = claim
            when {
                !loaded -> Unit
                row == null -> Text(stringResource(R.string.receipt_not_found), style = MaterialTheme.typography.bodyLarge)
                else -> ReceiptBody(row)
            }
        }
    }
}

@Composable
private fun ReceiptBody(row: ClaimEntity) {
    val (verdictRes, colour) = when (row.verdict) {
        VerdictCode.MATCH -> R.string.verdict_match to VerdictColors.Match
        VerdictCode.LIKELY_MATCH -> R.string.verdict_likely to VerdictColors.Likely
        else -> R.string.verdict_nomatch to VerdictColors.NoMatch
    }
    val reason = row.reason?.let { name -> runCatching { Reason.valueOf(name) }.getOrNull() }
    val reasonText = when {
        row.verdict == VerdictCode.MATCH -> stringResource(R.string.tts_verdict_match)
        reason == Reason.PENDING -> stringResource(R.string.tts_verdict_pending)
        reason != null -> stringResource(VerdictSpeech.reasonRes(reason))
        else -> stringResource(R.string.field_none)
    }
    val none = stringResource(R.string.field_none)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colour, contentColor = Color.White),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.receipt_claim_id, row.id), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(verdictRes), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Text(reasonText, style = MaterialTheme.typography.bodyLarge)
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldRow(stringResource(R.string.receipt_created), formatDateTime(row.createdAt))
            FieldRow(stringResource(R.string.field_amount), formatRupees(row.amountPaise), emphasise = true)
            FieldRow(stringResource(R.string.field_utr), row.utr ?: none)
            FieldRow(stringResource(R.string.field_time), row.claimedTimestamp?.let(::formatDateTime) ?: none)
            FieldRow(stringResource(R.string.field_payee), row.upiId ?: none)
            FieldRow(stringResource(R.string.field_payer), listOfNotNull(row.payerName, row.payerBankMask).joinToString(" · ").ifEmpty { none })
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            FieldRow(stringResource(R.string.receipt_verdict), row.verdict)
            FieldRow(stringResource(R.string.receipt_reason), row.reason ?: none)
            FieldRow(stringResource(R.string.receipt_payer_key), row.payerKey ?: none)
            FieldRow(stringResource(R.string.receipt_key_kind), row.keyKind ?: none)
            FieldRow(stringResource(R.string.field_backend), row.extractorBackend)
        }
    }
    Text(
        stringResource(R.string.receipt_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
