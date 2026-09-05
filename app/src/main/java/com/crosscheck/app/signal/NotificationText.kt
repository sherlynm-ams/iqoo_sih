package com.crosscheck.app.signal

/**
 * Builds the parser input for a posted notification: `title + "\n" + (bigText ?: text)`.
 * Pure so it is unit-testable; [NotificationListenerSource] feeds it the Notification extras.
 * `cmd notification post -S bigtext` leaves bigText null and puts the body in text, so text is the
 * fallback, never an addition (the two carry the same body on real apps).
 */
object NotificationText {
    fun compose(title: CharSequence?, text: CharSequence?, bigText: CharSequence?): String {
        val body = bigText?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val head = title?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(head, body).joinToString("\n")
    }
}
