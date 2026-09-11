package com.happy.assistant

import com.happy.assistant.knowledge.MathEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The whole point of this class is that a language model never sees a sum, so
 * the sums had better be right.
 */
class MathEvaluatorTest {

    private val math = MathEvaluator()

    @Test
    fun `spoken arithmetic`() {
        assertEquals("That is 4.", math.evaluate("2 plus 2"))
        assertEquals("That is 391.", math.evaluate("17 times 23"))
        assertEquals("That is 7.", math.evaluate("what is 21 divided by 3"))
        assertEquals("That is 15.", math.evaluate("20 minus 5"))
        assertEquals("That is 144.", math.evaluate("12 squared"))
    }

    @Test
    fun `precedence is respected`() {
        // 2 + 3 * 4 is 14, not 20.
        assertEquals("That is 14.", math.evaluate("2 plus 3 times 4"))
        assertEquals("That is 20.", math.evaluate("(2 plus 3) times 4"))
    }

    @Test
    fun `percentages`() {
        assertEquals("That is 12.", math.evaluate("15 percent of 80"))
        assertEquals("That is 50.", math.evaluate("what is 50 percent of 100"))
    }

    @Test
    fun `length and mass conversion`() {
        assertEquals("That is 5 kilometres.", math.evaluate("convert 5000 m to km"))
        assertEquals("That is 10 miles.", math.evaluate("convert 16.09344 km to miles"))
        assertEquals("That is 2.204623 pounds.", math.evaluate("convert 1 kg to pounds"))
    }

    @Test
    fun `temperature is an offset scale not a ratio`() {
        assertEquals("That is 86 degrees fahrenheit.", math.evaluate("convert 30 celsius to fahrenheit"))
        assertEquals("That is 100 degrees celsius.", math.evaluate("convert 212 fahrenheit to celsius"))
        assertEquals("That is 273.15 kelvin.", math.evaluate("convert 0 celsius to kelvin"))
    }

    @Test
    fun `mismatched dimensions are refused`() {
        assertNull(math.evaluate("convert 5 km to pounds"))
    }

    @Test
    fun `division by zero has no answer`() {
        assertNull(math.evaluate("5 divided by 0"))
    }

    @Test
    fun `a bare number is not a sum`() {
        // Otherwise every number question stops here instead of reaching Wikipedia.
        assertNull(math.evaluate("42"))
        assertNull(math.evaluate("who is 50 cent"))
    }

    @Test
    fun `ordinary questions are left alone`() {
        assertNull(math.evaluate("why is the sky blue"))
        assertNull(math.evaluate("who is ada lovelace"))
        assertNull(math.evaluate("what is loop quantum gravity"))
    }
}
