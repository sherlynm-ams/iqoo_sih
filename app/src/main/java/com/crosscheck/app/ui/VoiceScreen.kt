package com.crosscheck.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.crosscheck.app.BuildConfig
import com.crosscheck.app.R
import com.crosscheck.app.data.ClaimEntity
import com.crosscheck.app.data.PayerKeyKind
import com.crosscheck.app.data.VerdictCode
import com.crosscheck.app.data.formatRupees
import com.crosscheck.app.di.AppContainer
import com.crosscheck.app.ui.theme.VerdictColors
import com.crosscheck.app.verify.Reason
import com.crosscheck.app.voice.Answer
import com.crosscheck.app.voice.Listener
import com.crosscheck.app.voice.Query
import com.crosscheck.app.voice.VoiceQueryService
import kotlinx.coroutines.launch

/** Screen state for the voice query flow: mic -> listening -> transcript -> parsed -> answered. */
sealed class VoiceUiState {
    data object Idle : VoiceUiState()
    data object Listening : VoiceUiState()
    data class Thinking(val transcript: String) : VoiceUiState()
    data class Answered(val result: VoiceQueryService.Result) : VoiceUiState()
    data class Failed(val error: Listener.ListenError) : VoiceUiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(container: AppContainer, onBack: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<VoiceUiState>(VoiceUiState.Idle) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }

    fun runTranscript(transcript: String) {
        state = VoiceUiState.Thinking(transcript)
        scope.launch { state = VoiceUiState.Answered(container.voiceQuery.ask(transcript)) }
    }

    fun listen() {
        state = VoiceUiState.Listening
        scope.launch {
            val transcript = container.listener.listenOnce()
            if (transcript == null) {
                state = VoiceUiState.Failed(container.listener.lastError ?: Listener.ListenError.OTHER)
            } else {
                runTranscript(transcript)
            }
        }
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) listen() else state = VoiceUiState.Failed(Listener.ListenError.NO_PERMISSION)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val listening = state is VoiceUiState.Listening
            FilledIconButton(
                onClick = { if (micGranted) listen() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                enabled = !listening && state !is VoiceUiState.Thinking,
                modifier = Modifier.size(128.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                ),
            ) {
                if (listening) {
                    CircularProgressIndicator(modifier = Modifier.size(56.dp), color = MaterialTheme.colorScheme.onError)
                } else {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.voice_tap_to_speak), modifier = Modifier.size(56.dp))
                }
            }
            Text(
                stringResource(if (listening) R.string.voice_listening else R.string.voice_tap_to_speak),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.voice_examples),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val s = state) {
                VoiceUiState.Idle, VoiceUiState.Listening -> Unit
                is VoiceUiState.Thinking -> {
                    LabelledText(stringResource(R.string.voice_heard), s.transcript)
                    CircularProgressIndicator()
                }
                is VoiceUiState.Answered -> AnswerCard(s.result)
                is VoiceUiState.Failed -> ErrorCard(s.error, onSettings)
            }

            if (BuildConfig.DEBUG) {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                DebugPanel(container = container, enabled = state !is VoiceUiState.Listening, onRun = ::runTranscript)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LabelledText(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AnswerCard(result: VoiceQueryService.Result) {
    val positive = result.spoken.answer is Answer.Yes || result.spoken.answer is Answer.PaidList || result.spoken.answer is Answer.DigestAnswer
    val negative = result.spoken.answer is Answer.NothingFromName || result.spoken.answer is Answer.NothingOfAmount ||
        result.spoken.answer is Answer.DifferentAmount
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabelledText(stringResource(R.string.voice_heard), result.transcript)
        LabelledText(stringResource(R.string.voice_parsed), describeQuery(result.query))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    positive -> MaterialTheme.colorScheme.primaryContainer
                    negative -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.voice_answer), style = MaterialTheme.typography.labelLarge)
                Text(
                    result.spoken.text,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        positive -> VerdictColors.Match
                        negative -> VerdictColors.NoMatch
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun describeQuery(query: Query): String = when (query) {
    is Query.DidPay -> stringResource(
        R.string.voice_parsed_didpay,
        query.name ?: stringResource(R.string.voice_parsed_anyone),
        query.amountPaise?.let { formatRupees(it) } ?: stringResource(R.string.voice_parsed_any_amount),
    )
    Query.DigestToday -> stringResource(R.string.voice_parsed_digest)
    Query.Unknown -> stringResource(R.string.voice_parsed_unknown)
}

@Composable
private fun ErrorCard(error: Listener.ListenError, onSettings: () -> Unit) {
    val message = when (error) {
        Listener.ListenError.NO_PERMISSION -> stringResource(R.string.voice_error_no_permission)
        Listener.ListenError.NOT_AVAILABLE -> stringResource(R.string.voice_error_unavailable)
        Listener.ListenError.LANGUAGE_UNAVAILABLE -> stringResource(R.string.voice_error_no_language)
        Listener.ListenError.NO_SPEECH -> stringResource(R.string.voice_error_no_speech)
        Listener.ListenError.AUDIO -> stringResource(R.string.voice_error_audio)
        Listener.ListenError.BUSY -> stringResource(R.string.voice_error_busy)
        Listener.ListenError.OTHER -> stringResource(R.string.voice_error_generic)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            if (error == Listener.ListenError.LANGUAGE_UNAVAILABLE || error == Listener.ListenError.NOT_AVAILABLE) {
                TextButton(onClick = onSettings) { Text(stringResource(R.string.voice_open_settings)) }
            }
        }
    }
}

/**
 * Debug builds only: run the same pipeline from typed text (the emulator has no microphone) and
 * seed a NoMatch claim so the digest / flagged export have a mismatch row to show.
 */
@Composable
private fun DebugPanel(container: AppContainer, enabled: Boolean, onRun: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var seeded by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.voice_debug_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text(stringResource(R.string.voice_debug_type)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onRun(typed) }, enabled = enabled && typed.isNotBlank()) {
                Text(stringResource(R.string.voice_debug_run))
            }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    val id = container.claims.insert(debugNoMatchClaim(System.currentTimeMillis()))
                    seeded = "claims#$id"
                }
            }) { Text(stringResource(R.string.voice_debug_seed)) }
        }
        seeded?.let { Text(stringResource(R.string.voice_debug_seeded, it), style = MaterialTheme.typography.bodySmall) }
    }
}

/** A NoMatch receipt for a ₹500 claim nothing matches (debug seeding only). */
internal fun debugNoMatchClaim(now: Long): ClaimEntity = ClaimEntity(
    amountPaise = 50_000L,
    upiId = "seller@okaxis",
    utr = "699912345678",
    claimedTimestamp = now - 60_000L,
    verdict = VerdictCode.NO_MATCH,
    reason = Reason.NOTHING_RECEIVED.name,
    createdAt = now,
    extractorBackend = "debug-seed",
    payerName = "RAVI",
    payerBankMask = "SBI-XX4321",
    payerKey = "RAVI",
    keyKind = PayerKeyKind.NAME,
)
