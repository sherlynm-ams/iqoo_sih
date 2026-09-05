package com.crosscheck.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as SystemSettings
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.crosscheck.app.R
import com.crosscheck.app.signal.NotificationListenerSource

/** Explains each permission, shows its state, and offers the request / system-settings escape hatch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // Bumped after every result / resume so the rows re-read their state.
    var revision by remember { mutableIntStateOf(0) }
    val askedOnce = remember { mutableStateOf(setOf<AppPermission>()) }
    var listenerGranted by remember { mutableStateOf(NotificationListenerSource.isAccessGranted(context)) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        askedOnce.value = askedOnce.value + AppPermission.applicable().filter { it.permission in result.keys }
        revision++
    }
    LifecycleResumeEffect(Unit) {
        listenerGranted = NotificationListenerSource.isAccessGranted(context)
        revision++
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.perm_title)) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.perm_intro), style = MaterialTheme.typography.bodyLarge)

            AppPermission.applicable().forEach { perm ->
                // Reading `revision` ties this row to the refresh counter.
                val granted = remember(revision) { perm.isGranted(context) }
                val permanentlyDenied = !granted && perm in askedOnce.value &&
                    activity != null && !perm.shouldShowRationale(activity)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(perm.label), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(perm.why), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(
                                    when {
                                        granted -> R.string.perm_granted
                                        permanentlyDenied -> R.string.perm_denied_permanently
                                        else -> R.string.perm_denied
                                    },
                                ),
                                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            if (!granted) {
                                if (permanentlyDenied) {
                                    OutlinedButton(onClick = { openAppSettings(context) }) {
                                        Text(stringResource(R.string.perm_open_settings))
                                    }
                                } else {
                                    Button(onClick = { launcher.launch(arrayOf(perm.permission)) }) {
                                        Text(stringResource(R.string.perm_request))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.settings_source_notification), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(if (listenerGranted) R.string.settings_listener_enabled else R.string.settings_listener_disabled),
                        color = if (listenerGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (!listenerGranted) {
                        Button(onClick = {
                            context.startActivity(
                                Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }) { Text(stringResource(R.string.settings_open_listener)) }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { openAppSettings(context) }) { Text(stringResource(R.string.perm_open_settings)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onBack) { Text(stringResource(R.string.perm_continue)) }
            }
        }
    }
}

private fun openAppSettings(context: android.content.Context) {
    context.startActivity(
        Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
