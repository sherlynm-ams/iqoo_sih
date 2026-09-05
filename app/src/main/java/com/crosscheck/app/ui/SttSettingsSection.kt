package com.crosscheck.app.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.crosscheck.app.R
import com.crosscheck.app.data.SpeechLocales
import com.crosscheck.app.di.AppContainer
import com.crosscheck.app.voice.Listener
import kotlinx.coroutines.launch

/**
 * Settings block: on-device speech-recognition status per locale (API 33+ `checkRecognitionSupport`,
 * run when the screen opens) with a Download action (`triggerModelDownload`).
 */
@Composable
fun SttSettingsSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var support by remember { mutableStateOf<Map<String, Listener.Support>?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    LaunchedEffect(refreshKey) {
        support = container.listener.checkSupport(SpeechLocales.ALL)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_stt), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(
                when {
                    !container.listener.isRecognitionAvailable() -> R.string.settings_stt_none
                    container.listener.isOnDeviceAvailable() -> R.string.settings_stt_engine_on_device
                    else -> R.string.settings_stt_engine_default
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!supported) {
            Text(stringResource(R.string.settings_stt_api), style = MaterialTheme.typography.bodyMedium)
        }
        SpeechLocales.ALL.forEach { tag ->
            val status = support?.get(tag)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(localeLabel(tag))
                    Text(
                        stringResource(supportLabel(status)),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (status) {
                            Listener.Support.INSTALLED -> MaterialTheme.colorScheme.primary
                            Listener.Support.UNSUPPORTED, Listener.Support.ONLINE_ONLY -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (status == Listener.Support.DOWNLOADABLE || status == Listener.Support.PENDING) {
                    OutlinedButton(
                        enabled = status == Listener.Support.DOWNLOADABLE,
                        onClick = {
                            scope.launch {
                                container.listener.triggerModelDownload(tag)
                                refreshKey++
                            }
                        },
                    ) { Text(stringResource(R.string.settings_stt_download)) }
                }
            }
        }
        if (supported) {
            TextButton(onClick = { refreshKey++ }) { Text(stringResource(R.string.settings_stt_refresh)) }
        }
    }
}

@Composable
private fun localeLabel(tag: String): String = stringResource(
    when (tag) {
        SpeechLocales.TAMIL -> R.string.settings_locale_ta
        SpeechLocales.HINDI -> R.string.settings_locale_hi
        else -> R.string.settings_locale_en
    },
)

private fun supportLabel(status: Listener.Support?): Int = when (status) {
    Listener.Support.INSTALLED -> R.string.settings_stt_installed
    Listener.Support.DOWNLOADABLE -> R.string.settings_stt_downloadable
    Listener.Support.PENDING -> R.string.settings_stt_pending
    Listener.Support.ONLINE_ONLY -> R.string.settings_stt_online
    Listener.Support.UNSUPPORTED -> R.string.settings_stt_unsupported
    Listener.Support.UNKNOWN -> R.string.settings_stt_unknown
    null -> R.string.settings_stt_checking
}
