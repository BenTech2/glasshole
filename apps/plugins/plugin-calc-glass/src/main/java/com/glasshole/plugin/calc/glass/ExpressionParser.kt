package com.glasshole.plugin.calc.glass

/**
 * Standalone recursive descent expression parser.
 * Converts spoken math into numeric results.
 * Compatible with API 19 (no javax.script).
 *
 * Grammar:
 *   expression → term (('+' | '-') term)*
 *   term       → power (('*' | '/') power)*
 *   power      → factor ('^' factor)?
 *   factor     → NUMBER | '(' expression ')' | '-' factor
 */
object ExpressionParser {

    /**
     * Converts spoken words to a math expression string.
     * E.g., "two plus three" → "2+3", "five squared" → "5^2"
     */
    fun preprocess(spoken: String): String {
        var s = spoken.lowercase().trim()

        // Word numbers to digits
        val wordNumbers = mapOf(
            "zero" to "0", "one" to "1", "two" to "2", "three" to "3",
            "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
            "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11",
            "twelve" to "12", "thirteen" to "13", "fourteen" to "14",
            "fifteen" to "15", "sixteen" to "16", "seventeen" to "17",
            "eighteen" to "18", "nineteen" to "19", "twenty" to "20",
            "thirty" to "30", "forty" to "40", "fifty" to "50",
            "sixty" to "60", "seventy" to "70", "eighty" to "80",
            "ninety" to "90", "hundred" to "100", "thousand" to "1000",
            "million" to "1000000"
        )

        // Multi-word operator replacements (order matters — longest first)
        s = s.replace("multiplied by", "*")
        s = s.replace("divided by", "/")
        s = s.replace("to the power of", "^")
        s = s.replace("percent of", "* 0.01 *")

        // Single-word operator replacements
        s = s.replace("plus", "+")
        s = s.replace("add", "+")
        s = s.replace("minus", "-")
        s = s.replace("subtract", "-")
        s = s.replace("times", "*")
        s = s.replace("over", "/")
        s = s.replace("point", ".")

        // Postfix operators
        s = s.replace("squared", "^2")
        s = s.replace("cubed", "^3")

        // Replace word numbers with digits
        for ((word, digit) in wordNumbers) {
            s = s.replace(word, digit)
        }

        // Strip anything that isn't a digit, operator, decimal point, or parenthesis
        s = s.replace(Regex("[^0-9+\\-*/^().\\s]"), "")

        // Collapse whitespace and remove spaces around operators
        s = s.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("\\s*([+\\-*/^()])\\s*"), "$1")

        return s
    }

    /**
     * Evaluates a math expression string and returns the result.
     * @throws IllegalArgumentException if the expression is invalid.
     */
    fun evaluate(expr: String): Double {
        val parser = Parser(expr.replace(" ", ""))
        val result = parser.parseExpression()
        if (parser.pos < parser.input.length) {
            throw IllegalArgumentException("Unexpected character at position ${parser.pos}: '${parser.input[parser.pos]}'")
        }
        return result
    }

    private class Parser(val input: String) {
        var pos = 0

        fun parseExpression(): Double {
            var left = parseTerm()
            while (pos < input.length && (input[pos] == '+' || input[pos] == '-')) {
                val op = input[pos]
                pos++
                val right = parseTerm()
                left = if (op == '+') left + right else left - right
            }
            return left
        }

        private fun parseTerm(): Double {
            var left = parsePower()
            while (pos < input.length && (input[pos] == '*' || input[pos] == '/')) {
                val op = input[pos]
                pos++
                val right = parsePower()
                left = if (op == '*') left * right else left / right
            }
            return left
        }

        private fun parsePower(): Double {
            var base = parseFactor()
            if (pos < input.length && input[pos] == '^') {
                pos++
                val exponent = parseFactor()
                base = Math.pow(base, exponent)
            }
            return base
        }

        private fun parseFactor(): Double {
            // Unary minus
            if (pos < input.length && input[pos] == '-') {
                pos++
                return -parseFactor()
            }

            // Parenthesized expression
            if (pos < input.length && input[pos] == '(') {
                pos++ // consume '('
                val result = parseExpression()
                if (pos < input.length && input[pos] == ')') {
                    pos++ // consume ')'
                } else {
                    throw IllegalArgumentException("Missing closing parenthesis")
                }
                return result
            }

            // Number
            val start = pos
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) {
                pos++
            }
            if (pos == start) {
                throw IllegalArgumentException(
                    if (pos < input.length) "Unexpected character: '${input[pos]}'"
                    else "Unexpected end of expression"
                )
            }
            return input.substring(start, pos).toDouble()
        }
    }
}
