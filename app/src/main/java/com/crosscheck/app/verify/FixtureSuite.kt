package com.crosscheck.app.verify

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.crosscheck.app.data.PaymentEntity
import com.crosscheck.app.data.PaymentRepository
import com.crosscheck.app.data.PaymentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug-only acceptance test for build-order step 2: runs the router over the 10 fixture PNGs
 * (`fixtures/fixtures.json` is the contract), seeds the two payments the fixtures reconcile against, and
 * logs `CrossCheckFixture: <file> backend=<b> claim=<...> verdict=<...> expected=<...> PASS|FAIL` per image
 * plus a summary line. Looks in /sdcard/Download/crosscheck-fixtures/ first (needs the debug-only
 * READ_MEDIA_IMAGES grant), then in the app's own external files dir.
 */
class FixtureSuite(
    context: Context,
    private val payments: PaymentRepository,
    private val router: ExtractorRouter,
    private val verification: VerificationService,
) {
    private val appContext = context.applicationContext

    data class Result(val passed: Int, val total: Int, val directory: String?) {
        val allPassed: Boolean get() = total > 0 && passed == total
    }

    fun candidateDirectories(): List<File> = listOf(
        File(PUBLIC_DIR),
        File(appContext.getExternalFilesDir(null), DIR_NAME),
    )

    /**
     * A file may live in either directory: scoped storage lets a debug build with READ_MEDIA_IMAGES read the
     * PNGs pushed to Download, but never a non-media `fixtures.json` there, so that one is normally found in
     * the app-specific directory.
     */
    fun resolve(name: String): File? = candidateDirectories().map { File(it, name) }.firstOrNull { it.canRead() }

    suspend fun run(): Result = withContext(Dispatchers.IO) {
        val manifestFile = resolve(MANIFEST)
        if (manifestFile == null) {
            Log.e(TAG, "no readable $MANIFEST in ${candidateDirectories().joinToString { it.path }}")
            return@withContext Result(0, 0, null)
        }
        seedPayments()
        val manifest = JSONArray(manifestFile.readText())
        var passed = 0
        for (i in 0 until manifest.length()) {
            if (runOne(manifest.getJSONObject(i))) passed++
        }
        Log.i(TAG, "summary $passed/${manifest.length()} PASS manifest=${manifestFile.path}")
        Result(passed, manifest.length(), manifestFile.parent)
    }

    /** The two payments from fixtures/README.md (IST timestamps), inserted only if their UTR is absent. */
    suspend fun seedPayments() {
        for (seed in SEEDS) {
            if (payments.findByUtr(seed.utr!!) == null) {
                payments.insert(seed)
                Log.i(TAG, "seeded payment ${seed.sender} ${seed.amountPaise} ${seed.utr}")
            }
        }
    }

    private suspend fun runOne(entry: JSONObject): Boolean {
        val file = entry.getString("file")
        val expected = entry.getJSONObject("expected")
        val expectedVerdict = entry.getString("expectedVerdict")
        val bitmap = resolve(file)?.let { BitmapFactory.decodeFile(it.path) }
        if (bitmap == null) {
            Log.e(TAG, "$file backend=- claim=- verdict=- expected=$expectedVerdict FAIL (cannot read/decode)")
            return false
        }
        val extraction = router.run(bitmap)
        val claim = extraction.claim
        if (claim == null) {
            Log.e(TAG, "$file backend=${extraction.backend} claim=null verdict=- expected=$expectedVerdict FAIL (no claim)")
            return false
        }
        val outcome = verification.verify(claim, extraction.backend)
        val verdict = describe(outcome.verdict)
        val mismatches = ArrayList<String>()
        fun check(name: String, actual: Any?, wanted: Any?) {
            if (actual != wanted) mismatches.add("$name=$actual (expected $wanted)")
        }
        check("amountPaise", claim.amountPaise, expected.getLong("amountPaise"))
        check("utr", Utr.normalise(claim.utr), expected.optStringOrNull("utr"))
        check("app", claim.app.name.lowercase(), entry.getString("app"))
        check("status", claim.status.name.lowercase(), expected.getString("status"))
        check("otherIds", claim.otherIds, expected.getJSONArray("otherIds").let { a -> List(a.length()) { a.getString(it) } })
        check("payeeUpiId", claim.upiId, expected.optStringOrNull("payeeUpiId"))
        check("payerName", claim.payerName, expected.optStringOrNull("payerName"))
        check("payerBankMask", claim.payerBankMask, expected.optStringOrNull("payerBankMask"))
        check("timestamp", claim.claimedTimestamp, expected.optStringOrNull("timestampText")?.let { ClaimFieldParser.parseTimestamp(it) })
        check("verdict", verdict, expectedVerdict)
        val pass = mismatches.isEmpty()
        val line = "$file backend=${extraction.backend} claim=$claim verdict=$verdict expected=$expectedVerdict " +
            (if (pass) "PASS" else "FAIL " + mismatches.joinToString("; "))
        if (pass) Log.i(TAG, line) else Log.e(TAG, line)
        return pass
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    companion object {
        const val TAG = "CrossCheckFixture"
        const val DIR_NAME = "crosscheck-fixtures"
        const val PUBLIC_DIR = "/sdcard/Download/$DIR_NAME"
        const val MANIFEST = "fixtures.json"

        /** "Match" | "LikelyMatch(REASON)" | "NoMatch(REASON)" - the notation fixtures.json uses. */
        fun describe(verdict: Verdict): String = when (verdict) {
            Verdict.Match -> "Match"
            is Verdict.LikelyMatch -> "LikelyMatch(${verdict.reason.name})"
            is Verdict.NoMatch -> "NoMatch(${verdict.reason.name})"
        }

        val SEEDS: List<PaymentEntity> = listOf(
            PaymentEntity(
                amountPaise = 50_000,
                sender = "MURUGAN",
                utr = "426112345678",
                timestamp = ClaimFieldParser.parseTimestamp("06 Sep 2026, 12:41 pm")!!,
                source = PaymentSource.SMS,
                raw = "fixture seed p1 (fixtures/README.md)",
            ),
            PaymentEntity(
                amountPaise = 125_000,
                sender = "PRIYA",
                utr = "624911223344",
                timestamp = ClaimFieldParser.parseTimestamp("06 Sep 2026, 11:05 am")!!,
                source = PaymentSource.NOTIFICATION,
                raw = "fixture seed p2 (fixtures/README.md)",
            ),
        )
    }
}
