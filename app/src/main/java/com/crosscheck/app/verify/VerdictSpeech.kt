package com.crosscheck.app.verify

import androidx.annotation.StringRes
import com.crosscheck.app.R
import com.crosscheck.app.voice.Speaker

/**
 * Spoken verdict (SPEC section 1): MATCH / LIKELY MATCH + discrepancy / NO MATCH + reason, with the
 * repeat-offender prefix when the payer key had prior failures. Strings come from the `values`, `values-ta`
 * and `values-hi` string resources resolved in the current *speech* locale; the matching logic itself never
 * sees a language.
 */
object VerdictSpeech {

    @StringRes
    fun reasonRes(reason: Reason): Int = when (reason) {
        Reason.WRONG_AMOUNT -> R.string.reason_wrong_amount
        Reason.WRONG_UTR -> R.string.reason_wrong_utr
        Reason.NOTHING_RECEIVED -> R.string.reason_nothing_received
        Reason.TIMESTAMP_MISMATCH -> R.string.reason_timestamp_mismatch
        Reason.PENDING -> R.string.reason_pending
    }

    fun text(speaker: Speaker, verdict: Verdict, priorFailures: Int): String {
        val body = when (verdict) {
            Verdict.Match -> speaker.localizedString(R.string.tts_verdict_match)
            is Verdict.LikelyMatch ->
                speaker.localizedString(R.string.tts_verdict_likely, speaker.localizedString(reasonRes(verdict.reason)))
            is Verdict.NoMatch ->
                if (verdict.reason == Reason.PENDING) {
                    speaker.localizedString(R.string.tts_verdict_pending)
                } else {
                    speaker.localizedString(R.string.tts_verdict_nomatch, speaker.localizedString(reasonRes(verdict.reason)))
                }
        }
        if (priorFailures <= 0) return body
        return speaker.localizedString(R.string.tts_repeat_offender, priorFailures) + " " + body
    }

    fun speak(speaker: Speaker, verdict: Verdict, priorFailures: Int): String = speaker.speak(text(speaker, verdict, priorFailures))
}
