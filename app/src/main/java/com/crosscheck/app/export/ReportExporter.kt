package com.crosscheck.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.crosscheck.app.R
import com.crosscheck.app.data.ClaimRepository
import com.crosscheck.app.data.DayWindow
import com.crosscheck.app.data.PaymentRepository
import com.crosscheck.app.voice.Answer
import com.crosscheck.app.voice.Digest
import com.crosscheck.app.voice.DigestBuilder
import com.crosscheck.app.voice.QueryAnswerer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Office Kit export (SPEC section 1): writes today's reconciliation CSV, flagged-claims CSV and a
 * text summary to `getExternalFilesDir("reports")` and builds an `ACTION_SEND_MULTIPLE` intent over
 * `FileProvider` URIs so any file-transfer app can pick them up. No network.
 */
class ReportExporter(
    context: Context,
    private val payments: PaymentRepository,
    private val claims: ClaimRepository,
    private val answerer: QueryAnswerer,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext

    data class Export(val digest: Digest, val digestSentence: String, val files: List<File>) {
        val directory: File? get() = files.firstOrNull()?.parentFile
    }

    val reportsDir: File
        get() = appContext.getExternalFilesDir(REPORTS_SUBDIR) ?: File(appContext.filesDir, REPORTS_SUBDIR)

    suspend fun exportToday(): Export = withContext(Dispatchers.IO) {
        val now = clock()
        val window = DayWindow.today(now, zone)
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todaysPayments = payments.between(window.start, window.end)
        val todaysClaims = claims.between(window.start, window.end)
        val digest = DigestBuilder.build(todaysPayments, todaysClaims)
        val sentence = answerer.phrase(Answer.DigestAnswer(digest))

        val dir = reportsDir.apply { mkdirs() }
        val base = ReportContent.fileBaseName(date)
        val reconciliation = File(dir, "$base-reconciliation.csv")
        val flagged = File(dir, "$base-flagged.csv")
        val summary = File(dir, "$base-summary.txt")
        reconciliation.writeText(ReportContent.reconciliationCsv(todaysPayments, zone))
        flagged.writeText(ReportContent.flaggedCsv(todaysClaims, zone))
        summary.writeText(ReportContent.summaryText(date, digest, sentence, now, zone))

        val files = listOf(reconciliation, flagged, summary)
        files.forEach { Log.i(TAG, "wrote ${it.absolutePath} (${it.length()} bytes)") }
        Export(digest, sentence, files)
    }

    /** Chooser over the exported files. Caller starts it from an Activity context. */
    fun shareIntent(export: Export): Intent {
        val authority = appContext.packageName + AUTHORITY_SUFFIX
        val uris = ArrayList<Uri>(export.files.map { FileProvider.getUriForFile(appContext, authority, it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, export.files.first().nameWithoutExtension.substringBeforeLast('-'))
            putExtra(Intent.EXTRA_TEXT, export.digestSentence)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(appContext.contentResolver, "reports", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
        }
        return Intent.createChooser(send, appContext.getString(R.string.export_share_title))
    }

    companion object {
        const val TAG = "CrossCheckExport"
        const val REPORTS_SUBDIR = "reports"
        const val AUTHORITY_SUFFIX = ".fileprovider"
    }
}
