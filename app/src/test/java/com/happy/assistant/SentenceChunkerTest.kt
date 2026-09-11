package com.happy.assistant

import com.happy.assistant.knowledge.SentenceChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Streaming chunks split mid-word, so this is what stands between the model's
 * output and the speech engine. Getting it wrong makes Happy stutter.
 */
class SentenceChunkerTest {

    @Test
    fun `emits a sentence only once it is complete`() {
        val chunker = SentenceChunker()
        assertEquals(emptyList<String>(), chunker.accept("The sky is "))
        assertEquals(emptyList<String>(), chunker.accept("blue because"))
        assertEquals(listOf("The sky is blue because of scattering."), chunker.accept(" of scattering. "))
    }

    @Test
    fun `several sentences in one chunk all come out`() {
        val chunker = SentenceChunker()
        assertEquals(
            listOf("One thing.", "Two things!", "Three?"),
            chunker.accept("One thing. Two things! Three? ")
        )
    }

    @Test
    fun `a decimal is not the end of a sentence`() {
        val chunker = SentenceChunker()
        assertEquals(emptyList<String>(), chunker.accept("It is 3.5 metres"))
        assertEquals(listOf("It is 3.5 metres long."), chunker.accept(" long. "))
    }

    @Test
    fun `initials do not split a name`() {
        val chunker = SentenceChunker()
        assertEquals(
            listOf("J. R. R. Tolkien wrote it."),
            chunker.accept("J. R. R. Tolkien wrote it. ")
        )
    }

    @Test
    fun `abbreviations do not end a sentence`() {
        val chunker = SentenceChunker()
        assertEquals(
            listOf("Dr. Bose explained it."),
            chunker.accept("Dr. Bose explained it. ")
        )
    }

    @Test
    fun `flush returns the unfinished tail`() {
        val chunker = SentenceChunker()
        chunker.accept("An answer that just stops")
        assertEquals("An answer that just stops", chunker.flush())
        // And nothing is left behind afterwards.
        assertNull(chunker.flush())
    }

    @Test
    fun `nothing is lost across many small fragments`() {
        val chunker = SentenceChunker()
        val text = "Rain falls. It is wet. Plants like it."
        val out = mutableListOf<String>()
        for (c in text) out += chunker.accept(c.toString())
        chunker.flush()?.let { out += it }
        assertEquals(listOf("Rain falls.", "It is wet.", "Plants like it."), out)
    }
}
