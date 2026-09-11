package com.happy.assistant.knowledge

/**
 * Turns a stream of arbitrary text fragments into whole sentences.
 *
 * Spec section 3: speak each sentence the moment it is complete rather than
 * waiting for the whole answer. Chunks from the model split mid-word, so they
 * cannot be spoken directly - this accumulates until a sentence actually ends.
 *
 * Not every full stop ends a sentence. "Dr." and "U.S." and "3.5" would each cut
 * a sentence in half and make the speech stutter, so they are held back.
 */
class SentenceChunker {

    private val buffer = StringBuilder()

    /** Sentences completed by this fragment, in order. Usually empty. */
    fun accept(fragment: String): List<String> {
        if (fragment.isEmpty()) return emptyList()
        buffer.append(fragment)

        val done = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < buffer.length) {
            if (buffer[i] in TERMINATORS && endsSentence(i)) {
                val sentence = buffer.substring(start, i + 1).trim()
                if (sentence.isNotEmpty()) done += sentence
                start = i + 1
            }
            i++
        }
        if (start > 0) buffer.delete(0, start)
        return done
    }

    /** Whatever is left over, for when the stream ends mid-sentence. */
    fun flush(): String? {
        val rest = buffer.toString().trim()
        buffer.setLength(0)
        return rest.ifEmpty { null }
    }

    private fun endsSentence(index: Int): Boolean {
        val next = buffer.getOrNull(index + 1)
        // Nothing after it yet: wait, since more text may still arrive.
        if (next == null) return false
        if (!next.isWhitespace()) {
            // "3.5" and "google.com" are not sentence ends.
            return false
        }
        if (buffer[index] != '.') return true

        // A single capital before the stop is an initial: "J. R. R. Tolkien".
        val before = buffer.getOrNull(index - 1) ?: return true
        val twoBefore = buffer.getOrNull(index - 2)
        if (before.isUpperCase() && (twoBefore == null || !twoBefore.isLetter())) return false

        // Common abbreviations that are followed by more of the same sentence.
        val tail = buffer.substring(maxOf(0, index - LONGEST_ABBREVIATION), index + 1).lowercase()
        return ABBREVIATIONS.none { tail.endsWith(it) }
    }

    companion object {
        private val TERMINATORS = charArrayOf('.', '!', '?')
        private val ABBREVIATIONS = listOf(
            "mr.", "mrs.", "ms.", "dr.", "prof.", "st.", "jr.", "sr.",
            "e.g.", "i.e.", "etc.", "vs.", "approx.", "no.", "fig.",
        )
        private const val LONGEST_ABBREVIATION = 6
    }
}
