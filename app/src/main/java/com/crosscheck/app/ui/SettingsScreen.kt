package com.crosscheck.app.ui

import android.content.Intent
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crosscheck.app.BuildConfig
import com.crosscheck.app.R
import com.crosscheck.app.data.Settings
import com.crosscheck.app.data.SpeechLocales
import com.crosscheck.app.data.TrustedPackages
import com.crosscheck.app.di.AppContainer
import com.crosscheck.app.signal.NotificationListenerSource
import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.crosscheck.app.data.ExtractorSettings
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())

    var listenerGranted by remember { mutableStateOf(NotificationListenerSource.isAccessGranted(context)) }
    LifecycleResumeEffect(Unit) {
        listenerGranted = NotificationListenerSource.isAccessGranted(context)
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_locale), style = MaterialTheme.typography.titleMedium)
            LocaleOption(SpeechLocales.TAMIL, R.string.settings_locale_ta, settings.speechLocale) {
                scope.launch { container.settings.setSpeechLocale(it) }
            }
            LocaleOption(SpeechLocales.ENGLISH_INDIA, R.string.settings_locale_en, settings.speechLocale) {
                scope.launch { container.settings.setSpeechLocale(it) }
            }
            LocaleOption(SpeechLocales.HINDI, R.string.settings_locale_hi, settings.speechLocale) {
                scope.launch { container.settings.setSpeechLocale(it) }
            }
            OutlinedButton(onClick = { container.speaker.announcePayment(50_000L, "Murugan") }) {
                Text(stringResource(R.string.settings_test_tts))
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.settings_sources), style = MaterialTheme.typography.titleMedium)
            SwitchRow(stringResource(R.string.settings_source_sms), settings.smsSourceEnabled) {
                scope.launch { container.settings.setSmsSourceEnabled(it) }
            }
            SwitchRow(stringResource(R.string.settings_source_notification), settings.notificationSourceEnabled) {
                scope.launch { container.settings.setNotificationSourceEnabled(it) }
            }
            Text(
                stringResource(if (listenerGranted) R.string.settings_listener_enabled else R.string.settings_listener_disabled),
                style = MaterialTheme.typography.bodyMedium,
                color = if (listenerGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    context.startActivity(
                        Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text(stringResource(R.string.settings_open_listener)) }
            }
            OutlinedButton(onClick = {
                permissionLauncher.launch(AppPermission.applicable().map { it.permission }.toTypedArray())
            }) { Text(stringResource(R.string.settings_request_permissions)) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.settings_trusted_packages), style = MaterialTheme.typography.titleMedium)
            TrustedPackages.KNOWN.forEach { (label, pkg) ->
                CheckboxRow(label, pkg, pkg in settings.trustedPackages) {
                    scope.launch { container.settings.setTrustedPackage(pkg, it) }
                }
            }
            if (BuildConfig.TRUST_SHELL_NOTIFICATIONS) {
                CheckboxRow(
                    stringResource(R.string.settings_trusted_shell),
                    TrustedPackages.SHELL,
                    TrustedPackages.SHELL in settings.trustedPackages,
                ) { scope.launch { container.settings.setTrustedPackage(TrustedPackages.SHELL, it) } }
            }

            // Phase 2: Mode 2 extractor backend (SPEC section 1 Settings) + model file status / debug import.
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ExtractorSection(container = container, settings = settings, snackbar = snackbar)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LocaleOption(tag: String, labelRes: Int, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect(tag) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected == tag, onClick = { onSelect(tag) })
        Spacer(Modifier.width(8.dp))
        Text(stringResource(labelRes))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ExtractorSection(container: AppContainer, settings: Settings, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var modelPresent by remember(settings.vlmModelPath) { mutableStateOf(File(settings.vlmModelPath).isFile) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = container.importModel(uri)
                modelPresent = result.isSuccess
                snackbar.showSnackbar(
                    result.fold(
                        onSuccess = { context.getString(R.string.settings_import_done, it) },
                        onFailure = { context.getString(R.string.settings_import_failed, it.message ?: it.javaClass.simpleName) },
                    ),
                )
            }
        }
    }

    Text(stringResource(R.string.settings_extractor), style = MaterialTheme.typography.titleMedium)
    LocaleOption(ExtractorSettings.MODE_AUTO, R.string.settings_extractor_auto, settings.extractorMode) {
        scope.launch { container.settings.setExtractorMode(it) }
    }
    LocaleOption(ExtractorSettings.MODE_VLM, R.string.settings_extractor_vlm, settings.extractorMode) {
        scope.launch { container.settings.setExtractorMode(it) }
    }
    LocaleOption(ExtractorSettings.MODE_OCR, R.string.settings_extractor_ocr, settings.extractorMode) {
        scope.launch { container.settings.setExtractorMode(it) }
    }
    Text(
        stringResource(R.string.settings_model_path, settings.vlmModelPath),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(if (modelPresent) R.string.settings_model_present else R.string.settings_model_absent),
        style = MaterialTheme.typography.bodyMedium,
        color = if (modelPresent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
    if (BuildConfig.DEBUG) {
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
            Text(stringResource(R.string.settings_import_model))
        }
    }
}

@Composable
private fun CheckboxRow(label: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
