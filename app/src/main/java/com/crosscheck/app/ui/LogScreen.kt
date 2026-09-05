package com.crosscheck.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crosscheck.app.BuildConfig
import com.crosscheck.app.R
import com.crosscheck.app.data.ClaimEntity
import com.crosscheck.app.data.DayWindow
import com.crosscheck.app.data.PaymentEntity
import com.crosscheck.app.data.PaymentSource
import com.crosscheck.app.data.formatRupees
import com.crosscheck.app.di.AppContainer
import com.crosscheck.app.signal.NotificationListenerSource
import com.crosscheck.app.ui.theme.VerdictColors
import com.crosscheck.app.verify.Reason
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    container: AppContainer,
    onVerifyClaim: () -> Unit,
    onVoiceQuery: () -> Unit,
    onSettings: () -> Unit,
    onPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val today = remember { DayWindow.today() }
    val payments by container.payments.observeBetween(today.start, today.end)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val flagged by container.claims.observeNoMatchBetween(today.start, today.end)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var signalsMissing by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        signalsMissing = AppPermission.missingCore(context).isNotEmpty() ||
            !NotificationListenerSource.isAccessGranted(context)
        onPauseOrDispose { }
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val dumpedMessage = stringResource(R.string.log_dumped)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name) + " · " + stringResource(R.string.log_title)) },
                actions = {
                    if (BuildConfig.DEBUG) {
                        IconButton(onClick = {
                            scope.launch {
                                container.dumpDatabaseToLog()
                                snackbar.showSnackbar(dumpedMessage)
                            }
                        }) {
                            Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.log_dump_db))
                        }
                    }
                    IconButton(onClick = onPermissions) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = stringResource(R.string.nav_permissions),
                            tint = if (signalsMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onVerifyClaim, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.ic_camera), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.log_verify_claim))
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalIconButton(onClick = onVoiceQuery, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.log_voice_query))
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (signalsMissing) {
                item {
                    Card(
                        onClick = onPermissions,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.perm_intro),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.log_section_payments),
                    trailing = stringResource(R.string.log_total_today, payments.size, formatRupees(payments.sumOf { it.amountPaise })),
                )
            }
            if (payments.isEmpty()) {
                item { EmptyRow(stringResource(R.string.log_empty_payments)) }
            } else {
                items(payments, key = { "p" + it.id }) { PaymentRow(it) }
            }

            item { Spacer(Modifier.height(8.dp)) }
            item { SectionHeader(title = stringResource(R.string.log_section_flagged), trailing = flagged.size.toString()) }
            if (flagged.isEmpty()) {
                item { EmptyRow(stringResource(R.string.log_empty_flagged)) }
            } else {
                items(flagged, key = { "c" + it.id }) { FlaggedRow(it) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(top = 4.dp))
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun PaymentRow(payment: PaymentEntity) {
    val sender = payment.sender.ifBlank { stringResource(R.string.tts_unknown_sender) }
    val sourceLabel = if (payment.source == PaymentSource.SMS) R.string.log_source_sms else R.string.log_source_notification
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatRupees(payment.amountPaise),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = VerdictColors.Match,
                    modifier = Modifier.weight(1f),
                )
                Text(formatTime(payment.timestamp), style = MaterialTheme.typography.labelLarge)
            }
            Text(sender, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    payment.utr?.let { "UTR $it" } ?: stringResource(R.string.log_no_utr),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                AssistChip(onClick = {}, label = { Text(stringResource(sourceLabel)) })
            }
        }
    }
}

@Composable
private fun FlaggedRow(claim: ClaimEntity) {
    val reasonRes = when (claim.reason?.let { runCatching { Reason.valueOf(it) }.getOrNull() }) {
        Reason.WRONG_AMOUNT -> R.string.reason_wrong_amount
        Reason.WRONG_UTR -> R.string.reason_wrong_utr
        Reason.TIMESTAMP_MISMATCH -> R.string.reason_timestamp_mismatch
        Reason.PENDING -> R.string.reason_pending
        Reason.NOTHING_RECEIVED, null -> R.string.reason_nothing_received
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatRupees(claim.amountPaise),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = VerdictColors.NoMatch,
                    modifier = Modifier.weight(1f),
                )
                Text(formatTime(claim.createdAt), style = MaterialTheme.typography.labelLarge)
            }
            val who = listOfNotNull(claim.payerName, claim.payerBankMask, claim.upiId).joinToString(" · ")
            if (who.isNotEmpty()) Text(who, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.verdict_nomatch) + " — " + stringResource(reasonRes),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Unspecified,
            )
        }
    }
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timeFormatter)
