package com.happy.assistant.knowledge

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Arithmetic and unit conversion, entirely on device.
 *
 * Spec section 7: anything with a deterministic answer must never reach a
 * language model. A model that is asked what 17 times 23 is will usually be
 * right, and "usually" is the problem.
 *
 * Input arrives as spoken words, so the operators are words too: "times",
 * "divided by", "percent of". Returns null whenever the text is not confidently
 * arithmetic, which sends it on to the next source rather than guessing.
 */
@Singleton
class MathEvaluator @Inject constructor() {

    /** The spoken answer, or null when this is not a sum. */
    fun evaluate(question: String): String? {
        val lower = question.lowercase().trim()
        if (lower.isEmpty()) return null

        // Both of these run on the raw words. normalise strips every letter, so
        // neither a unit name nor the "of" in "percent of" survives to be matched
        // afterwards.
        conversion(lower)?.let { return it }
        percentOf(lower)?.let { return it }

        val text = normalise(question)
        if (text.isEmpty()) return null

        // Require at least one operator, or "42" alone would be a valid sum and
        // every bare number would stop here instead of reaching Wikipedia.
        if (OPERATORS.none { text.contains(it) }) return null
        val value = try {
            Parser(text).parse()
        } catch (t: Throwable) {
            null
        } ?: return null

        return "That is ${format(value)}."
    }

    /**
     * Unit conversion between the pairs that actually come up out loud.
     *
     * Temperature is handled apart from the rest because it is an offset scale,
     * not a ratio - multiplying celsius by a factor gives nonsense.
     */
    private fun conversion(text: String): String? {
        val match = CONVERT.find(text) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val fromWord = match.groupValues[2].trim()
        val toWord = match.groupValues[3].trim()

        val from = unitOf(fromWord) ?: return null
        val to = unitOf(toWord) ?: return null
        if (from.dimension != to.dimension) return null

        val result = if (from.dimension == TEMPERATURE) {
            temperature(amount, from.name, to.name) ?: return null
        } else {
            amount * from.factor / to.factor
        }
        return "That is ${format(result)} ${to.spoken}."
    }

    private fun temperature(amount: Double, from: String, to: String): Double? = when {
        from == to -> amount
        from == "celsius" && to == "fahrenheit" -> amount * 9.0 / 5.0 + 32.0
        from == "fahrenheit" && to == "celsius" -> (amount - 32.0) * 5.0 / 9.0
        from == "celsius" && to == "kelvin" -> amount + 273.15
        from == "kelvin" && to == "celsius" -> amount - 273.15
        else -> null
    }

    private fun unitOf(word: String): Unit? =
        UNITS.firstOrNull { unit -> unit.aliases.any { it == word } }

    private data class Unit(
        val name: String,
        val dimension: String,
        /** Amount of the dimension's base unit in one of these. */
        val factor: Double,
        val spoken: String,
        val aliases: List<String>,
    )

    private fun normalise(input: String): String {
        var text = input.lowercase().trim()
        for ((from, to) in WORDS) text = text.replace(from, to)
        return text
            .replace(Regex("""[^0-9+\-*/^%(). ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun percentOf(text: String): String? {
        val match = PERCENT_OF.find(text) ?: return null
        val percent = match.groupValues[1].toDoubleOrNull() ?: return null
        val whole = match.groupValues[2].toDoubleOrNull() ?: return null
        return "That is ${format(whole * percent / 100.0)}."
    }

    /** Trims the noise off a double so speech does not read fifteen decimals. */
    fun format(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "undefined"
        val rounded = (value * 1_000_000).roundToLong() / 1_000_000.0
        return if (abs(rounded - rounded.roundToLong()) < 1e-9) {
            rounded.roundToLong().toString()
        } else {
            rounded.toString().trimEnd('0').trimEnd('.')
        }
    }

    /**
     * Recursive descent over the usual precedence. Small enough to read, which is
     * the point: an expression parser nobody can check is worse than no parser.
     */
    private class Parser(private val text: String) {
        private var pos = 0

        fun parse(): Double? {
            val value = expression() ?: return null
            skipSpace()
            // Trailing junk means this was not really an expression.
            return if (pos < text.length) null else value
        }

        private fun expression(): Double? {
            var left = term() ?: return null
            while (true) {
                skipSpace()
                val op = peek() ?: return left
                if (op != '+' && op != '-') return left
                pos++
                val right = term() ?: return null
                left = if (op == '+') left + right else left - right
            }
        }

        private fun term(): Double? {
            var left = power() ?: return null
            while (true) {
                skipSpace()
                val op = peek() ?: return left
                if (op != '*' && op != '/') return left
                pos++
                val right = power() ?: return null
                if (op == '/' && right == 0.0) return null
                left = if (op == '*') left * right else left / right
            }
        }

        private fun power(): Double? {
            val base = unary() ?: return null
            skipSpace()
            if (peek() != '^') return base
            pos++
            val exponent = power() ?: return null
            return base.pow(exponent)
        }

        private fun unary(): Double? {
            skipSpace()
            if (peek() == '-') {
                pos++
                return unary()?.let { -it }
            }
            return atom()
        }

        private fun atom(): Double? {
            skipSpace()
            if (peek() == '(') {
                pos++
                val inner = expression() ?: return null
                skipSpace()
                if (peek() != ')') return null
                pos++
                return inner
            }
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
            if (pos == start) return null
            return text.substring(start, pos).toDoubleOrNull()
        }

        private fun peek(): Char? = text.getOrNull(pos)

        private fun skipSpace() {
            while (pos < text.length && text[pos] == ' ') pos++
        }
    }

    companion object {
        private const val TEMPERATURE = "temperature"

        /** "convert 5 km to miles", "5 km in miles", "5 kilometres to miles". */
        private val CONVERT = Regex(
            """(?:convert |what is |whats )?([0-9.]+) *([a-z ]+?) (?:to|in|into) ([a-z ]+)$"""
        )

        private val UNITS = listOf(
            Unit("metre", "length", 1.0, "metres", listOf("m", "meter", "meters", "metre", "metres")),
            Unit("kilometre", "length", 1000.0, "kilometres", listOf("km", "kilometer", "kilometers", "kilometre", "kilometres")),
            Unit("centimetre", "length", 0.01, "centimetres", listOf("cm", "centimeter", "centimeters", "centimetre", "centimetres")),
            Unit("mile", "length", 1609.344, "miles", listOf("mile", "miles")),
            Unit("foot", "length", 0.3048, "feet", listOf("ft", "foot", "feet")),
            Unit("inch", "length", 0.0254, "inches", listOf("in", "inch", "inches")),
            Unit("kilogram", "mass", 1.0, "kilograms", listOf("kg", "kilo", "kilos", "kilogram", "kilograms")),
            Unit("gram", "mass", 0.001, "grams", listOf("g", "gram", "grams")),
            Unit("pound", "mass", 0.45359237, "pounds", listOf("lb", "lbs", "pound", "pounds")),
            Unit("ounce", "mass", 0.028349523, "ounces", listOf("oz", "ounce", "ounces")),
            Unit("litre", "volume", 1.0, "litres", listOf("l", "litre", "litres", "liter", "liters")),
            Unit("millilitre", "volume", 0.001, "millilitres", listOf("ml", "millilitre", "millilitres", "milliliter", "milliliters")),
            Unit("gallon", "volume", 3.785411784, "gallons", listOf("gallon", "gallons")),
            Unit("celsius", TEMPERATURE, 1.0, "degrees celsius", listOf("c", "celsius", "centigrade", "degrees celsius")),
            Unit("fahrenheit", TEMPERATURE, 1.0, "degrees fahrenheit", listOf("f", "fahrenheit", "degrees fahrenheit")),
            Unit("kelvin", TEMPERATURE, 1.0, "kelvin", listOf("k", "kelvin")),
        )

        private val OPERATORS = listOf("+", "-", "*", "/", "^")

    /** Matches the spoken form, since this runs before the words are stripped. */
        private val PERCENT_OF = Regex("""([0-9.]+) *(?:%|percent|per cent) of ([0-9.]+)""")

        /** Spoken operators, longest first so "multiplied by" beats "by". */
        private val WORDS = listOf(
            "multiplied by" to "*",
            "divided by" to "/",
            "to the power of" to "^",
            "percent of" to "% of",
            "per cent of" to "% of",
            "plus" to "+",
            "minus" to "-",
            "times" to "*",
            "into" to "*",
            "over" to "/",
            "squared" to "^2",
            "cubed" to "^3",
            "percent" to "%",
            "point" to ".",
            "whats" to " ",
            "what is" to " ",
            "calculate" to " ",
            "equals" to " ",
            "x" to "*",
        )
    }
}
