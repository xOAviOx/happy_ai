package com.happy.assistant.audio

/**
 * The last line of defence before anything is spoken aloud.
 *
 * Spec section 6 and section 8: the text-to-speech engine must never read a
 * symbol out loud. The Gemini prompt asks for plain prose, but models drift back
 * into markdown, so the prompt is the request and this is the guarantee. Every
 * string that reaches TextToSpeech.speak goes through here first, with no
 * exceptions.
 *
 * Pure and side-effect free on purpose, so it can be tested without a device.
 * The unit tests are the specification.
 */
object TtsSanitizer {

    private val CODE_FENCE = Regex("""```[a-zA-Z0-9+#_-]*""")
    private val BOLD_ITALIC = Regex("""\*{1,3}([^*\n]+)\*{1,3}""")
    private val UNDERSCORE = Regex("""_{1,3}([^_\n]+)_{1,3}""")
    private val STRIKE = Regex("""~~([^~\n]+)~~""")
    private val LINK = Regex("""\[([^\]]*)\]\(([^)]*)\)""")
    private val BARE_URL = Regex("""(https?://|www\.)\S+""")
    private val HEADING = Regex("""^\s*#{1,6}\s*""")
    private val QUOTE = Regex("""^\s*>+\s*""")
    private val BULLET = Regex("""^\s*[-*•+]\s+""")
    private val NUMBERED = Regex("""^\s*(\d{1,2})[.)]\s+""")
    private val HASH_NUMBER = Regex("""#(?=\d)""")
    private val LETTER_SLASH = Regex("""(?<=[a-zA-Z])/(?=[a-zA-Z])""")
    private val SPACE_BEFORE_PUNCT = Regex(""" +([.,?!:;])""")
    private val REPEATED_SPACE = Regex("""[ \t]+""")

    /**
     * Emoji and pictographs. The final guard would strip these anyway, but doing
     * it explicitly keeps the intent visible and handles the pieces that sit
     * inside otherwise-legal ranges, like variation selectors.
     */
    private val EMOJI = Regex(
        "[\u2190-\u21FF\u2300-\u23FF\u2600-\u27BF\u2B00-\u2BFF\uFE0F\u200D]" +
            "|[\uD83C-\uDBFF][\uDC00-\uDFFF]"
    )

    /** Anything outside this set is not speakable. Note the hyphen stays last. */
    private val ALLOWED = Regex("""[^\p{L}\p{N} .,?!':;-]""")

    private val RUPEES = Regex(Regex.escape("₹") + """\s*(\d+(?:[.,]\d+)*)""")
    private val DOLLARS = Regex(Regex.escape("$") + """\s*(\d+(?:[.,]\d+)*)""")

    private val ORDINALS = listOf("First", "Second", "Third", "Fourth", "Fifth")

    private val ABBREVIATIONS = listOf(
        "e.g." to "for example",
        "i.e." to "that is",
        "etc." to "and so on",
        "vs." to "versus",
        "approx." to "approximately",
    )

    fun clean(raw: String): String {
        if (raw.isBlank()) return ""

        var text = raw
        text = CODE_FENCE.replace(text, "")
        text = text.replace("`", "")
        text = STRIKE.replace(text, "$1")
        text = BOLD_ITALIC.replace(text, "$1")
        text = UNDERSCORE.replace(text, "$1")
        text = LINK.replace(text, "$1")
        text = BARE_URL.replace(text, "")

        text = joinLines(text)
        text = expandSymbols(text)
        text = expandAbbreviations(text)
        text = EMOJI.replace(text, "")
        // A space, not an empty string: deleting the separator in "24/7" yields
        // "247", which is read aloud as two hundred and forty seven.
        text = ALLOWED.replace(text, " ")

        text = REPEATED_SPACE.replace(text, " ")
        text = SPACE_BEFORE_PUNCT.replace(text, "$1")
        return text.trim()
    }

    /**
     * Flattens the text to one spoken line.
     *
     * A line that carried structure - a heading, a bullet, a numbered item, a
     * quote - becomes its own sentence, because that is the pause a listener
     * expects where the layout used to be. A plain line is just joined on.
     */
    private fun joinLines(text: String): String {
        val pieces = mutableListOf<String>()
        for (original in text.lines()) {
            var line = original
            var structural = false

            HEADING.replace(line, "").let { if (it != line) { structural = true; line = it } }
            QUOTE.replace(line, "").let { if (it != line) { structural = true; line = it } }

            val numbered = NUMBERED.find(line)
            if (numbered != null) {
                structural = true
                val n = numbered.groupValues[1].toIntOrNull() ?: 0
                val ordinal = ORDINALS.getOrNull(n - 1)
                line = NUMBERED.replace(line, "")
                if (ordinal != null) line = "$ordinal, $line"
            } else {
                BULLET.replace(line, "").let { if (it != line) { structural = true; line = it } }
            }

            line = line.replace("|", " ").trim()
            if (line.isEmpty()) continue
            if (structural && line.last() !in SENTENCE_END) line = "$line."
            pieces += line
        }
        return pieces.joinToString(" ")
    }

    private fun expandSymbols(text: String): String {
        var out = text
        out = HASH_NUMBER.replace(out, " number ")
        out = LETTER_SLASH.replace(out, " slash ")
        // Written before the amount but spoken after it: 40 rupees, not rupees 40.
        out = RUPEES.replace(out, "$1 rupees")
        out = DOLLARS.replace(out, "$1 dollars")
        out = out.replace("&", " and ")
        out = out.replace("%", " percent ")
        out = out.replace("@", " at ")
        out = out.replace("\u00B0", " degrees ")
        out = out.replace("\u20B9", " rupees ")
        out = out.replace('$'.toString(), " dollars ")
        out = out.replace("+", " plus ")
        out = out.replace("=", " equals ")
        return out
    }

    private fun expandAbbreviations(text: String): String {
        var out = text
        for ((from, to) in ABBREVIATIONS) {
            out = out.replace(from, to, ignoreCase = true)
        }
        return out
    }

    private val SENTENCE_END = charArrayOf('.', '!', '?', ':', ';', ',')
}
