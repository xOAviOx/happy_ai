package com.happy.assistant

import com.happy.assistant.audio.TtsSanitizer.clean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * These are the six cases the build spec requires, verbatim, plus the ones that
 * would catch a regression in each individual transformation.
 */
class TtsSanitizerTest {

    // ----- the six required cases from section 8 -----

    @Test
    fun `strips bold emphasis`() {
        assertEquals(
            "Loop quantum gravity is a theory...",
            clean("**Loop quantum gravity** is a theory...")
        )
    }

    @Test
    fun `turns bullets into sentences`() {
        assertEquals("item one. item two.", clean("- item one\n- item two"))
    }

    @Test
    fun `turns a heading into a sentence`() {
        assertEquals("Heading. Text", clean("## Heading\nText"))
    }

    @Test
    fun `keeps the text inside backticks`() {
        assertEquals("Use git push here", clean("Use `git push` here"))
    }

    @Test
    fun `expands degrees and percent and drops emoji`() {
        assertEquals(
            "It's 30 degrees C and 50 percent humidity",
            clean("It's 30\u00B0C and 50% humidity \uD83C\uDF1E")
        )
    }

    @Test
    fun `keeps the link label and drops the url`() {
        assertEquals("See docs", clean("See [docs](https://x.com)"))
    }

    // ----- the rest of the transformation list -----

    @Test
    fun `numbers become ordinals`() {
        assertEquals(
            "First, boil water. Second, add rice.",
            clean("1. boil water\n2. add rice")
        )
    }

    @Test
    fun `strips code fences but keeps the code`() {
        assertEquals("run this npm install", clean("run this\n```bash\nnpm install\n```"))
    }

    @Test
    fun `strips blockquotes and table pipes`() {
        assertEquals("quoted. a b c", clean("> quoted\n| a | b | c |"))
    }

    @Test
    fun `drops a bare url entirely`() {
        assertEquals("read it at", clean("read it at https://example.com/x?y=1"))
    }

    @Test
    fun `expands common abbreviations`() {
        assertEquals(
            "for example rice, that is grain, and so on",
            clean("e.g. rice, i.e. grain, etc.")
        )
    }

    @Test
    fun `expands currency and arithmetic symbols`() {
        assertEquals(
            "it costs 40 rupees or 5 dollars, 2 plus 2 equals 4",
            clean("it costs ₹40 or $5, 2+2=4")
        )
    }

    @Test
    fun `expands a slash only between letters`() {
        assertEquals("input slash output on 24 7", clean("input/output on 24/7"))
    }

    @Test
    fun `expands hash only before a digit`() {
        assertEquals("issue number 42", clean("issue #42"))
    }

    @Test
    fun `strips underscores and strikethrough`() {
        assertEquals("italic and gone", clean("_italic_ and ~~gone~~"))
    }

    @Test
    fun `collapses whitespace`() {
        assertEquals("one two three", clean("one   two \t  three"))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", clean(""))
        assertEquals("", clean("   \n  "))
    }

    @Test
    fun `no forbidden character ever survives`() {
        val nasty = "## Hi **there** `code` [l](http://u) ~~x~~ | > 100% \uD83D\uDE00 <tag> {json} ^caret"
        val out = clean(nasty)
        val forbidden = charArrayOf(
            '*', '_', '`', '#', '[', ']', '(', ')', '|', '>', '<', '{', '}', '^', '~', '%',
        )
        for (c in forbidden) {
            assertFalse("sanitizer let through '$c' in: $out", out.contains(c))
        }
    }
}
