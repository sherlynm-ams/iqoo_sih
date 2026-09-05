package com.crosscheck.app.data

/**
 * The single rupee formatter (SPEC section 4). Input is Long paise; output uses Indian digit
 * grouping ("1,50,000.50"). Whole-rupee amounts omit the paise ("₹500"), otherwise two decimals.
 */
fun formatRupees(paise: Long): String {
    val negative = paise < 0
    val abs = if (negative) -paise else paise
    val rupees = abs / 100
    val fraction = abs % 100
    val grouped = groupIndian(rupees.toString())
    val body = if (fraction == 0L) grouped else grouped + "." + fraction.toString().padStart(2, '0')
    return (if (negative) "-₹" else "₹") + body
}

/** "150000" -> "1,50,000"; "999" -> "999"; "1000" -> "1,000". */
internal fun groupIndian(digits: String): String {
    if (digits.length <= 3) return digits
    val last3 = digits.takeLast(3)
    val head = digits.dropLast(3)
    val parts = ArrayList<String>()
    var i = head.length
    while (i > 0) {
        val from = maxOf(0, i - 2)
        parts.add(head.substring(from, i))
        i = from
    }
    return parts.reversed().joinToString(",") + "," + last3
}

/** Parses "1,50,000.50" / "500" / "500.5" into paise. Returns null when the text is not a plain amount. */
fun parseRupeesToPaise(text: String): Long? {
    val cleaned = text.replace(",", "").trim()
    if (cleaned.isEmpty() || !cleaned.all { it.isDigit() || it == '.' }) return null
    val dot = cleaned.indexOf('.')
    if (dot != cleaned.lastIndexOf('.')) return null
    val whole = if (dot < 0) cleaned else cleaned.substring(0, dot)
    val frac = if (dot < 0) "" else cleaned.substring(dot + 1)
    if (whole.isEmpty() && frac.isEmpty()) return null
    if (frac.length > 2) return null
    val rupees = if (whole.isEmpty()) 0L else whole.toLongOrNull() ?: return null
    val paise = if (frac.isEmpty()) 0L else frac.padEnd(2, '0').toLong()
    return rupees * 100 + paise
}
