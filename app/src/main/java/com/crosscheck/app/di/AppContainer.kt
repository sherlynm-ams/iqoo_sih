package com.crosscheck.app.di

import android.content.Context
import android.util.Log
import com.crosscheck.app.data.AppDatabase
import com.crosscheck.app.data.ClaimRepository
import com.crosscheck.app.data.FlaggedVpaRepository
import com.crosscheck.app.data.PaymentRepository
import com.crosscheck.app.data.RoomClaimRepository
import com.crosscheck.app.data.RoomFlaggedVpaRepository
import com.crosscheck.app.data.RoomPaymentRepository
import com.crosscheck.app.data.SettingsRepository
import com.crosscheck.app.signal.PaymentIngestor
import com.crosscheck.app.verify.Reconciler
import com.crosscheck.app.verify.VerificationService
import com.crosscheck.app.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Manual DI (SPEC section 4): one instance per process, built in [com.crosscheck.app.CrossCheckApp]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase = AppDatabase.build(appContext)
    val payments: PaymentRepository = RoomPaymentRepository(database.paymentDao())
    val claims: ClaimRepository = RoomClaimRepository(database.claimDao())
    val flaggedVpas: FlaggedVpaRepository = RoomFlaggedVpaRepository(database.flaggedVpaDao())
    val settings: SettingsRepository = SettingsRepository(appContext)

    val speaker: Speaker = Speaker(appContext)
    val ingestor: PaymentIngestor = PaymentIngestor(payments, speaker)
    val reconciler: Reconciler = Reconciler()
    val verification: VerificationService = VerificationService(payments, claims, flaggedVpas, reconciler)

    init {
        appScope.launch {
            settings.settings.map { it.speechLocale }.distinctUntilChanged().collect { speaker.setLocale(it) }
        }
    }

    /** Debug aid: logs every row of every table at `Log.i("CrossCheckDB", ...)`. */
    suspend fun dumpDatabaseToLog() {
        val p = payments.all()
        Log.i(DB_TAG, "payments: ${p.size} row(s)")
        p.forEach { Log.i(DB_TAG, "payment $it") }
        val c = claims.all()
        Log.i(DB_TAG, "claims: ${c.size} row(s)")
        c.forEach { Log.i(DB_TAG, "claim $it") }
        val f = flaggedVpas.all()
        Log.i(DB_TAG, "flagged_vpas: ${f.size} row(s)")
        f.forEach { Log.i(DB_TAG, "flagged $it") }
    }

    companion object {
        const val DB_TAG = "CrossCheckDB"
    }
}
