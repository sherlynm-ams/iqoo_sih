package com.crosscheck.app.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.crosscheck.app.BuildConfig
import com.crosscheck.app.R
import com.crosscheck.app.data.Settings
import com.crosscheck.app.data.formatRupees
import com.crosscheck.app.di.AppContainer
import com.crosscheck.app.ui.theme.VerdictColors
import com.crosscheck.app.verify.Claim
import com.crosscheck.app.verify.ClaimApp
import com.crosscheck.app.verify.ClaimStatus
import com.crosscheck.app.verify.FixtureSuite
import com.crosscheck.app.verify.OcrClaimExtractor
import com.crosscheck.app.verify.QuickScan
import com.crosscheck.app.verify.Reason
import com.crosscheck.app.verify.Verdict
import com.crosscheck.app.verify.VerdictSpeech
import com.crosscheck.app.verify.VerificationService
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Verify screen states (SPEC section 1 UI): preview -> extracting -> verdict card. */
sealed interface VerifyUiState {
    data object Preview : VerifyUiState
    data object Extracting : VerifyUiState
    data class Result(val claim: Claim, val outcome: VerificationService.Outcome, val backend: String) : VerifyUiState
    data class Failed(@StringRes val messageRes: Int, val detail: String? = null) : VerifyUiState
}

class VerifyViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<VerifyUiState>(VerifyUiState.Preview)
    val state: StateFlow<VerifyUiState> = _state
    private var consecutiveHits = 0

    /**
     * True while a takePicture is in flight. The UI must stay in [VerifyUiState.Preview] until the bitmap
     * arrives - leaving it disposes the camera composable, which unbinds the use cases and aborts the
     * capture ("Camera is closed") - so this flag, not a state change, is what blocks a second capture.
     */
    @Volatile private var capturing = false

    /** Per analysed frame; true exactly once when two consecutive frames looked like a payment screen. */
    fun onScan(scan: QuickScan): Boolean {
        if (_state.value != VerifyUiState.Preview || capturing) return false
        consecutiveHits = if (scan.looksLikePaymentScreen) consecutiveHits + 1 else 0
        if (consecutiveHits < AUTO_CAPTURE_FRAMES) return false
        consecutiveHits = 0
        capturing = true
        return true
    }

    /** Manual capture button: claims the in-flight slot so auto-capture cannot fire on top of it. */
    fun beginCapture(): Boolean {
        if (_state.value != VerifyUiState.Preview || capturing) return false
        capturing = true
        return true
    }

    fun captureFailed(detail: String) {
        capturing = false
        _state.value = VerifyUiState.Failed(R.string.verify_camera_unavailable, detail)
    }

    fun process(bitmap: Bitmap) {
        capturing = false
        _state.value = VerifyUiState.Extracting
        viewModelScope.launch {
            val extraction = container.extractor.run(bitmap)
            val claim = extraction.claim
            if (claim == null) {
                _state.value = VerifyUiState.Failed(R.string.verify_read_failed)
                return@launch
            }
            val outcome = container.verification.verify(claim, extraction.backend)
            VerdictSpeech.speak(container.speaker, outcome.verdict, outcome.priorFailures)
            _state.value = VerifyUiState.Result(claim, outcome, extraction.backend)
        }
    }

    fun reset() {
        consecutiveHits = 0
        capturing = false
        _state.value = VerifyUiState.Preview
    }

    suspend fun runFixtureSuite(): FixtureSuite.Result = container.fixtureSuite.run()

    companion object {
        const val AUTO_CAPTURE_FRAMES = 2
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyScreen(container: AppContainer, onBack: () -> Unit, onViewReceipt: (Long) -> Unit) {
    val vm: VerifyViewModel = viewModel { VerifyViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var cameraGranted by remember { mutableStateOf(AppPermission.CAMERA.isGranted(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraGranted = it }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val bitmap = decodeSoftwareBitmap(context, uri)
                if (bitmap != null) vm.process(bitmap) else snackbar.showSnackbar(context.getString(R.string.verify_read_failed))
            }
        }
    }
    val suiteRunning = stringResource(R.string.verify_suite_running)
    val suiteMissing = stringResource(R.string.verify_suite_missing)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.verify_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    if (BuildConfig.DEBUG) {
                        TextButton(onClick = {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text(stringResource(R.string.verify_use_fixture)) }
                        TextButton(onClick = {
                            scope.launch {
                                snackbar.showSnackbar(suiteRunning)
                                val result = vm.runFixtureSuite()
                                snackbar.showSnackbar(
                                    if (result.total == 0) suiteMissing
                                    else context.getString(R.string.verify_suite_result, result.passed, result.total),
                                )
                            }
                        }) { Text(stringResource(R.string.verify_run_suite)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                VerifyUiState.Preview -> if (cameraGranted) {
                    CameraPreview(
                        ocr = container.ocrExtractor,
                        onScan = { vm.onScan(it) },
                        onManualCapture = { vm.beginCapture() },
                        onBitmap = { vm.process(it) },
                        onError = { vm.captureFailed(it) },
                    )
                } else {
                    CenteredMessage(stringResource(R.string.verify_camera_permission)) {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text(stringResource(R.string.verify_grant_camera))
                        }
                    }
                }

                VerifyUiState.Extracting -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.verify_extracting), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.verify_backend, settings.extractorMode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is VerifyUiState.Result -> VerdictCard(
                    state = s,
                    onViewReceipt = { onViewReceipt(s.outcome.claimId) },
                    onAgain = { vm.reset() },
                )

                is VerifyUiState.Failed -> CenteredMessage(
                    stringResource(s.messageRes, *(s.detail?.let { arrayOf<Any>(it) } ?: emptyArray())),
                ) {
                    Button(onClick = { vm.reset() }) { Text(stringResource(R.string.verify_again)) }
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String, action: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        action()
    }
}

@Composable
private fun CameraPreview(
    ocr: OcrClaimExtractor,
    onScan: (QuickScan) -> Boolean,
    onManualCapture: () -> Boolean,
    onBitmap: (Bitmap) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val session = remember { CameraSession(context, ocr) }
    DisposableEffect(lifecycleOwner) {
        session.bind(
            owner = lifecycleOwner,
            onScan = { scan -> if (onScan(scan)) session.capture(onBitmap, onError) },
            onError = onError,
        )
        onDispose { session.release() }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { session.previewView }, modifier = Modifier.fillMaxSize())
        Text(
            stringResource(R.string.verify_hint),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(12.dp),
        )
        Button(
            onClick = { if (onManualCapture()) session.capture(onBitmap, onError) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
        ) { Text(stringResource(R.string.verify_capture)) }
    }
}

/**
 * CameraX session per payment-screens.md section 3c: back camera, tap-to-focus (AF+AE, 3 s auto-cancel),
 * -1 EV exposure compensation when supported, torch off, MAXIMIZE_QUALITY capture at >= 1080p, and a
 * low-rate ML Kit analysis stream driving auto-capture.
 */
private class CameraSession(private val context: Context, private val ocr: OcrClaimExtractor) {
    val previewView: PreviewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lastScanAt = 0L

    @Volatile private var scanning = true

    fun bind(owner: LifecycleOwner, onScan: (QuickScan) -> Unit, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(1080, 1920), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(720, 1280), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyse(proxy, onScan) }
                p.unbindAll()
                val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                camera = cam
                imageCapture = capture
                applyControls(cam)
                installTapToFocus()
                Log.i(TAG, "camera bound; exposure=${cam.cameraInfo.exposureState.exposureCompensationIndex}")
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                onError(e.message ?: e.javaClass.simpleName)
            }
        }, mainExecutor)
    }

    private fun applyControls(cam: Camera) {
        cam.cameraControl.enableTorch(false)
        val exposure = cam.cameraInfo.exposureState
        if (exposure.isExposureCompensationSupported) {
            val step = exposure.exposureCompensationStep.toFloat()
            if (step > 0f) {
                val index = Math.round(TARGET_EV / step)
                    .coerceIn(exposure.exposureCompensationRange.lower, exposure.exposureCompensationRange.upper)
                cam.cameraControl.setExposureCompensationIndex(index)
            }
        }
    }

    private fun installTapToFocus() {
        previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                camera?.cameraControl?.startFocusAndMetering(
                    FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build(),
                )
                view.performClick()
            }
            true
        }
    }

    private fun analyse(proxy: ImageProxy, onScan: (QuickScan) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        val media = proxy.image
        if (!scanning || media == null || now - lastScanAt < SCAN_INTERVAL_MS) {
            proxy.close()
            return
        }
        lastScanAt = now
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scope.launch {
            val scan = try {
                ocr.quickScan(image)
            } catch (e: CancellationException) {
                null // session released mid-frame - not an error
            } catch (e: Exception) {
                Log.w(TAG, "analysis frame failed: ${e.message}")
                null
            } finally {
                proxy.close()
            }
            if (scan != null && scanning) onScan(scan)
        }
    }

    fun capture(onBitmap: (Bitmap) -> Unit, onError: (String) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onError("camera not ready")
            return
        }
        scanning = false
        capture.takePicture(
            mainExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val bitmap = try {
                        image.toBitmap()
                    } finally {
                        image.close()
                    }
                    Log.i(TAG, "captured ${bitmap.width}x${bitmap.height} rotation=$rotation")
                    onBitmap(rotate(bitmap, rotation))
                }

                override fun onError(exception: ImageCaptureException) {
                    scanning = true
                    Log.e(TAG, "capture failed", exception)
                    onError(exception.message ?: "capture failed")
                }
            },
        )
    }

    fun release() {
        scanning = false
        scope.cancel()
        runCatching { provider?.unbindAll() }
        analysisExecutor.shutdown()
    }

    companion object {
        const val TAG = "CrossCheckCamera"
        const val SCAN_INTERVAL_MS = 700L
        const val TARGET_EV = -1f

        fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
            if (degrees == 0) return bitmap
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }
}

/** Decodes a picked image as a software bitmap (ML Kit cannot read hardware bitmaps). */
private suspend fun decodeSoftwareBitmap(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    } catch (e: Exception) {
        Log.e(CameraSession.TAG, "decode failed for $uri", e)
        null
    }
}

@Composable
private fun VerdictCard(state: VerifyUiState.Result, onViewReceipt: () -> Unit, onAgain: () -> Unit) {
    val verdict = state.outcome.verdict
    val (titleRes, colour) = when (verdict) {
        Verdict.Match -> R.string.verdict_match to VerdictColors.Match
        is Verdict.LikelyMatch -> R.string.verdict_likely to VerdictColors.Likely
        is Verdict.NoMatch -> R.string.verdict_nomatch to VerdictColors.NoMatch
    }
    val reason = verdict.reasonOrNull
    val claim = state.claim
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colour, contentColor = Color.White),
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(titleRes),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    lineHeight = 44.sp,
                )
                Text(
                    reasonText(verdict, reason),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (state.outcome.priorFailures > 0) {
                    Text(
                        stringResource(R.string.verify_repeat_note, state.outcome.priorFailures),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldRow(stringResource(R.string.field_amount), formatRupees(claim.amountPaise), emphasise = true)
                FieldRow(stringResource(R.string.field_utr), claim.utr ?: stringResource(R.string.field_none))
                FieldRow(stringResource(R.string.field_app), stringResource(appLabel(claim.app)))
                FieldRow(stringResource(R.string.field_status), stringResource(statusLabel(claim.status)))
                FieldRow(stringResource(R.string.field_time), claim.claimedTimestamp?.let(::formatDateTime) ?: stringResource(R.string.field_none))
                FieldRow(stringResource(R.string.field_payee), claim.upiId ?: stringResource(R.string.field_none))
                FieldRow(
                    stringResource(R.string.field_payer),
                    listOfNotNull(claim.payerName, claim.payerBankMask).joinToString(" · ").ifEmpty { stringResource(R.string.field_none) },
                )
                if (claim.otherIds.isNotEmpty()) FieldRow(stringResource(R.string.field_other_ids), claim.otherIds.joinToString(", "))
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                FieldRow(stringResource(R.string.field_backend), state.backend)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onViewReceipt, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.verify_view_receipt)) }
            OutlinedButton(onClick = onAgain, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.verify_again)) }
        }
    }
}

@Composable
private fun reasonText(verdict: Verdict, reason: Reason?): String = when {
    verdict is Verdict.Match -> stringResource(R.string.tts_verdict_match)
    verdict is Verdict.NoMatch && reason == Reason.PENDING -> stringResource(R.string.tts_verdict_pending)
    reason != null -> stringResource(VerdictSpeech.reasonRes(reason))
    else -> ""
}

@Composable
internal fun FieldRow(label: String, value: String, emphasise: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp),
        )
        Text(
            value,
            style = if (emphasise) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasise) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

@StringRes
internal fun appLabel(app: ClaimApp): Int = when (app) {
    ClaimApp.GPAY -> R.string.app_gpay
    ClaimApp.PHONEPE -> R.string.app_phonepe
    ClaimApp.UNKNOWN -> R.string.app_unknown
}

@StringRes
internal fun statusLabel(status: ClaimStatus): Int = when (status) {
    ClaimStatus.SUCCESS -> R.string.status_success
    ClaimStatus.PENDING -> R.string.status_pending
    ClaimStatus.FAILED -> R.string.status_failed
    ClaimStatus.UNKNOWN -> R.string.status_unknown
}

private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")

internal fun formatDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateTimeFormatter)
