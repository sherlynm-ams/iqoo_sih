package com.crosscheck.app.voice

import com.crosscheck.app.data.parseRupeesToPaise

/**
 * Language-neutral transcript -> [Query]. Digits and sentence shape do the work; the per-locale
 * word lists below are deliberately small (fillers to drop, a few "how was it" cues, number words)
 * so a name in any script survives as "the tokens that are not keywords or amounts".
 *
 * Pure Kotlin; no Android types.
 */
object QueryParser {

    fun parse(transcript: String): Query {
        val tokens = tokenize(transcript)
        if (tokens.isEmpty()) return Query.Unknown

        val amount = findAmount(tokens)
        val consumed = amount?.tokenIndexes ?: emptySet()
        val keys = tokens.map { it.key }
        val hasPayCue = keys.any { it in PAY_CUES }
        val hasDigestCue = keys.any { it in DIGEST_CUES }

        val nameTokens = tokens.filterIndexed { i, t ->
            i !in consumed && t.key !in STOP_WORDS && t.key !in DIGEST_CUES && t.key !in PAY_CUES &&
                t.key !in NUMBER_WORDS && t.key !in MULTIPLIERS && t.key.any { it.isLetter() }
        }
        val name = nameTokens.joinToString(" ") { it.text }.takeIf { it.isNotEmpty() }

        return when {
            amount == null && name == null && hasDigestCue -> Query.DigestToday
            amount != null || name != null -> Query.DidPay(name, amount?.paise)
            hasPayCue -> Query.DidPay(null, null)
            else -> Query.Unknown
        }
    }

    // ---------------------------------------------------------------- tokens

    internal class Token(val text: String, val key: String)

    /** Splits on whitespace, strips edge punctuation and currency prefixes, keeps the original text. */
    internal fun tokenize(transcript: String): List<Token> =
        transcript.split(WHITESPACE).mapNotNull { raw ->
            var t = raw.trim { it in EDGE_PUNCTUATION }
            if (t.isEmpty()) return@mapNotNull null
            // "₹500", "Rs.500", "rs500", "inr500" -> "500"
            CURRENCY_PREFIX.find(t.lowercase())?.let { m -> t = t.substring(m.range.last + 1) }
            t = t.trim { it in EDGE_PUNCTUATION }
            if (t.isEmpty()) return@mapNotNull null
            // possessive "murugan's" -> "murugan"
            val lower = t.lowercase().removeSuffix("'s").removeSuffix("’s")
            val text = if (lower.length != t.length) t.substring(0, lower.length) else t
            Token(text, lower)
        }

    // ---------------------------------------------------------------- amounts

    internal class Amount(val paise: Long, val tokenIndexes: Set<Int>)

    /**
     * First run of numeric tokens: digits ("500", "1,250", "500.50") and/or number words
     * ("five hundred", "one thousand two hundred fifty", "2 thousand"). Very long digit strings
     * (UTRs are 12 digits) are never amounts.
     */
    internal fun findAmount(tokens: List<Token>): Amount? {
        var i = 0
        while (i < tokens.size) {
            if (!isNumeric(tokens[i].key)) { i++; continue }
            val start = i
            var end = i
            while (end < tokens.size && (isNumeric(tokens[end].key) || (tokens[end].key in JOINERS && end + 1 < tokens.size && isNumeric(tokens[end + 1].key)))) end++
            val run = tokens.subList(start, end)
            // "do" is Hindi for 2 but also English "do you know…": a lone ambiguous word is not an amount.
            val onlyAmbiguous = run.all { it.key in AMBIGUOUS_NUMBER_WORDS }
            val paise = if (onlyAmbiguous) null else evaluate(run.map { it.key })
            if (paise != null && paise > 0) return Amount(paise, (start until end).toSet())
            i = end
        }
        return null
    }

    private fun isNumeric(key: String): Boolean =
        key in NUMBER_WORDS || key in MULTIPLIERS || digitsRupees(key) != null

    /** "500" / "1,250" / "500.50" -> paise; null when not a plain amount or unreasonably long. */
    private fun digitsRupees(key: String): Long? {
        if (key.isEmpty() || !key[0].isDigit()) return null
        val digitCount = key.count { it.isDigit() }
        if (digitCount > MAX_AMOUNT_DIGITS) return null
        return parseRupeesToPaise(key)
    }

    private fun evaluate(keys: List<String>): Long? {
        var total = 0L
        var current = 0L
        var sawValue = false
        for (k in keys) {
            if (k in JOINERS) continue
            val word = NUMBER_WORDS[k]
            val mult = MULTIPLIERS[k]
            val digits = if (word == null && mult == null) digitsRupees(k) else null
            when {
                word != null -> { current += word; sawValue = true }
                mult != null -> {
                    current = if (current == 0L) mult else current * mult
                    if (mult >= 1000) { total += current; current = 0 }
                    sawValue = true
                }
                digits != null -> {
                    if (digits % 100 != 0L) {
                        // decimal rupees: a standalone amount, nothing multiplies it
                        return if (!sawValue) digits else null
                    }
                    current += digits / 100
                    sawValue = true
                }
                else -> return null
            }
        }
        if (!sawValue) return null
        return (total + current) * 100
    }

    // ---------------------------------------------------------------- word lists (small, per locale)

    private val WHITESPACE = Regex("\\s+")
    private const val EDGE_PUNCTUATION = "?!.,;:'\"()[]{}“”‘’"
    private val CURRENCY_PREFIX = Regex("^(?:₹|rs\\.?|inr|rupees?|ரூ\\.?|रु\\.?|₹\\s*)(?=\\d)")
    private const val MAX_AMOUNT_DIGITS = 8

    private val JOINERS = setOf("and")
    private val AMBIGUOUS_NUMBER_WORDS = setOf("do", "teen", "bees")

    /** Words that signal "did X pay" in the three demo locales (Latin transliterations included). */
    private val PAY_CUES: Set<String> = setOf(
        // en
        "pay", "paid", "pays", "paying", "payment", "sent", "send", "gave", "give", "given", "transferred", "transfer", "received", "receive", "got",
        "came", "come", "arrived", "arrive", "reached", "credited", "credit",
        // ta (script + Tanglish)
        "கொடுத்தாரா", "கொடுத்தானா", "கொடுத்தாளா", "கொடுத்தார்", "கொடுத்தான்", "கொடுத்தாங்களா", "அனுப்பினாரா", "அனுப்பினானா", "அனுப்பினார்",
        "வந்ததா", "வந்துச்சா", "வந்திருச்சா", "வந்தது", "பணம்",
        "vandhucha", "vanthucha", "vandhutha", "vanthutha", "vandhuducha", "vanthuducha", "vandhadha", "vanthatha",
        "koduthara", "kuduthara", "koduthaara", "kuduthaara", "kodutharaa", "kudutharaa", "koduthaan", "kuduthan", "koduthan",
        "anupinara", "anuppinara", "anuppinaara", "panam",
        // hi (script + Latin)
        "दिया", "दिए", "दी", "भेजा", "भेजे", "भेजी", "किया", "भुगतान", "मिला", "मिले", "आया", "आए",
        "diya", "diye", "di", "bheja", "bheje", "bheji", "kiya", "bhugtan", "mila", "mile", "aaya", "aaye",
    )

    /** "how was today" cues. Only reach Digest when no name and no amount were found. */
    private val DIGEST_CUES: Set<String> = setOf(
        "how", "how's", "hows", "summary", "digest", "report", "total", "totals", "overall", "status", "recap",
        "எப்படி", "எப்படியிருந்தது", "மொத்தம்", "மொத்தமா", "சுருக்கம்", "eppadi", "epdi", "motham", "mothama",
        "kaisa", "kaise", "kaisi", "haal", "kul", "sab", "कैसा", "कैसे", "कैसी", "हाल", "कुल", "सब",
    )

    /** Fillers dropped before the leftover tokens are read as the payer's name. */
    private val STOP_WORDS: Set<String> = setOf(
        // en
        "did", "does", "do", "has", "have", "had", "is", "was", "were", "are", "be", "been", "will", "would", "can", "could",
        "the", "a", "an", "me", "my", "i", "we", "you", "it", "this", "that", "of", "for", "from", "by", "to", "in", "on", "at", "with",
        "today", "todays", "today's", "yesterday", "now", "yet", "already", "still", "just", "again",
        "please", "hey", "ok", "okay", "hi", "hello", "tell", "check", "whether", "if", "so", "any", "anyone", "anybody", "someone", "somebody",
        "much", "many", "what", "what's", "whats", "which", "who", "when", "there", "here", "then", "and", "or", "not", "no", "yes",
        "it's", "its", "let's", "lets", "know", "see", "want", "wanted", "need",
        "rupees", "rupee", "rs", "inr", "money", "amount", "cash", "upi", "gpay", "phonepe", "paytm", "bank", "account",
        "day", "morning", "evening", "afternoon", "night", "went", "go", "going", "done", "doing", "things", "business", "sales",
        // ta (script + Tanglish)
        "இன்று", "இன்னைக்கு", "இன்றைக்கு", "இன்னிக்கு", "நாள்", "ரூபாய்", "ரூபா", "ரூ", "பணத்தை", "என்ன", "ஆ", "ஆமா", "இல்ல", "இல்லை",
        "எவ்வளவு", "யார்", "நான்", "நீ", "நீங்க", "அவர்", "அவன்", "அவள்", "கிட்ட", "இருந்து", "இருந்தது", "இருக்கு", "இருக்கா",
        "innaiku", "innaikku", "inniki", "innikku", "indru", "inru", "naal", "rupa", "rupai", "roopa", "roopai", "rooba", "rubai",
        "enna", "aama", "illa", "illai", "evlo", "evvalavu", "yaar", "yaaru", "naan", "nee", "neenga", "avar", "avan", "aval", "kitta", "irundhu", "irunthu", "irundhadhu", "irunthathu", "irukku", "irukka",
        // hi (script + Latin)
        "आज", "कल", "ने", "को", "से", "का", "की", "के", "क्या", "है", "हैं", "था", "थे", "थी", "रहा", "रही", "रहे", "पैसे", "पैसा", "रुपये", "रुपया", "रुपए", "रु",
        "मुझे", "मैं", "हम", "आप", "तुम", "वो", "वह", "यह", "ये", "कोई", "कितना", "कितने", "कौन", "दिन", "गया", "गए", "गई", "हुआ", "हुए", "हुई",
        "aaj", "kal", "ne", "ko", "se", "ka", "ki", "ke", "kya", "hai", "hain", "tha", "the", "thi", "raha", "rahi", "rahe",
        "paise", "paisa", "rupaye", "rupaya", "rupay", "rupee", "mujhe", "main", "hum", "aap", "tum", "wo", "woh", "yeh", "ye", "koi", "kitna", "kitne", "kaun", "din", "gaya", "gaye", "gayi", "hua", "hue", "hui",
    )

    /** Units and tens. English plus a few Hindi (Latin) and Tamil words STT engines commonly emit as words. */
    private val NUMBER_WORDS: Map<String, Long> = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
        // hi (Latin)
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5, "chhe" to 6, "che" to 6, "saat" to 7, "aath" to 8, "nau" to 9,
        "das" to 10, "bees" to 20, "pachas" to 50,
        // ta
        "ஒன்று" to 1, "இரண்டு" to 2, "மூன்று" to 3, "நான்கு" to 4, "ஐந்து" to 5, "ஆறு" to 6, "ஏழு" to 7, "எட்டு" to 8, "ஒன்பது" to 9,
        "பத்து" to 10, "இருபது" to 20, "ஐம்பது" to 50,
        "onnu" to 1, "rendu" to 2, "moonu" to 3, "naalu" to 4, "anju" to 5, "aaru" to 6, "yezhu" to 7, "ettu" to 8, "ombadhu" to 9, "pathu" to 10,
    ).mapValues { it.value.toLong() }

    private val MULTIPLIERS: Map<String, Long> = mapOf(
        "hundred" to 100L, "hundreds" to 100L, "thousand" to 1_000L, "thousands" to 1_000L, "lakh" to 100_000L, "lakhs" to 100_000L, "lac" to 100_000L,
        "sau" to 100L, "hazaar" to 1_000L, "hazar" to 1_000L,
        "நூறு" to 100L, "ஆயிரம்" to 1_000L, "லட்சம்" to 100_000L, "nooru" to 100L, "aayiram" to 1_000L, "ayiram" to 1_000L, "latcham" to 100_000L,
    )
}
