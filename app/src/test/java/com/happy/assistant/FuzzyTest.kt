package com.happy.assistant

import com.happy.assistant.router.Fuzzy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {

    @Test
    fun `exact and prefix beat edit distance`() {
        assertTrue(Fuzzy.score("spotify", "Spotify")!! == 0)
        // A prefix is stronger evidence than a typo. "whatsap" is also a prefix,
        // so the comparison only means something against a genuine misspelling.
        assertTrue(Fuzzy.score("what", "WhatsApp")!! < Fuzzy.score("whatzapp", "WhatsApp")!!)
        // Four edits is not a match at all, however plausible it looks.
        assertNull(Fuzzy.score("wapp", "WhatsApp"))
    }

    @Test
    fun `a misheard vowel still finds the person`() {
        assertNotNull(Fuzzy.score("rohith", "Rohit"))
    }

    @Test
    fun `long names get more slack than short ones`() {
        // The real failure: recognition heard "chutrant" for Chitransh.
        assertNotNull(Fuzzy.score("chutrant", "Chitransh"))
        // But a short name must stay strict, or everyone matches everyone.
        assertNull(Fuzzy.score("ravi", "amit"))
    }

    @Test
    fun `different people stay different`() {
        assertNull(Fuzzy.score("rohit", "mohit sharma"))
    }
}
