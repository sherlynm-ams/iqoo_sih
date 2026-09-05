package com.crosscheck.app.voice

import android.util.Log
import com.crosscheck.app.data.ClaimRepository
import com.crosscheck.app.data.DayWindow
import com.crosscheck.app.data.PaymentRepository

/**
 * Transcript -> [QueryParser] -> today's rows -> [QueryAnswerer] -> spoken. Used by the Voice
 * screen for both the microphone path and the debug typed path, so both are the same pipeline.
 */
class VoiceQueryService(
    private val payments: PaymentRepository,
    private val claims: ClaimRepository,
    private val answerer: QueryAnswerer,
    private val speak: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Result(val transcript: String, val query: Query, val spoken: Spoken)

    suspend fun ask(transcript: String): Result {
        val query = QueryParser.parse(transcript)
        val today = DayWindow.today(clock())
        val spoken = answerer.answer(query, payments.between(today.start, today.end), claims.between(today.start, today.end))
        Log.i(Listener.TAG, "query transcript=\"$transcript\" parsed=$query answer=\"${spoken.text}\"")
        speak(spoken.text)
        return Result(transcript, query, spoken)
    }
}
