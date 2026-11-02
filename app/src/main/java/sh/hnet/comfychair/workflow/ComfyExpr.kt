package sh.hnet.comfychair.workflow

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/**
 * Evaluator for the arithmetic subset used by `ComfyMathExpression`.
 *
 * The H3 templates convert a duration in seconds into a frame count with an expression such as
 * `max(5, round(a * 24)) + (5 - (max(5, round(a * 24)) % 17)) % 17`, so "6 秒" must never be
 * shown as 6 frames. Evaluating the workflow's own expression keeps the displayed frame count and
 * the submitted one identical, including Python's modulo sign rule and banker's rounding.
 *
 * Unknown syntax returns null instead of a guess: a wrong number is worse than no number.
 */
object ComfyExpr {

    fun evaluate(expression: String, variables: Map<String, Double> = emptyMap()): Double? =
        runCatching { Parser(expression, variables).parseAll() }.getOrNull()

    /** Frame count produced by a length expression for [seconds], rounded like the server would. */
    fun frames(expression: String?, seconds: Double): Int? {
        if (expression == null) return null
        val value = evaluate(expression, mapOf("a" to seconds)) ?: return null
        return value.toLong().toInt().takeIf { it > 0 }
    }

    private class Parser(private val text: String, private val vars: Map<String, Double>) {
        private var i = 0

        fun parseAll(): Double {
            val v = expr()
            skipSpace()
            require(i == text.length) { "尾随字符" }
            return v
        }

        private fun skipSpace() { while (i < text.length && text[i].isWhitespace()) i++ }

        private fun peek(s: String): Boolean {
            skipSpace()
            return text.startsWith(s, i)
        }

        private fun expr(): Double {
            var v = term()
            while (true) {
                when {
                    peek("+") -> { i++; v += term() }
                    peek("-") -> { i++; v -= term() }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = unary()
            while (true) {
                when {
                    // `**` is tested first: it also starts with `*`.
                    peek("**") -> { i += 2; v = v.pow(unary()) }
                    peek("//") -> { i += 2; v = floor(v / unary()) }
                    peek("*") -> { i++; v *= unary() }
                    peek("/") -> { i++; v /= unary() }
                    peek("%") -> { i++; v = pythonMod(v, unary()) }
                    else -> return v
                }
            }
        }

        private fun unary(): Double {
            if (peek("-")) { i++; return -unary() }
            if (peek("+")) { i++; return unary() }
            return atom()
        }

        private fun atom(): Double {
            skipSpace()
            require(i < text.length) { "表达式不完整" }
            val c = text[i]
            if (c == '(') {
                i++
                val v = expr()
                skipSpace()
                require(i < text.length && text[i] == ')') { "缺少右括号" }
                i++
                return v
            }
            if (c.isDigit() || c == '.') {
                val start = i
                while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                return text.substring(start, i).toDouble()
            }
            if (c.isLetter() || c == '_') {
                val start = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                val name = text.substring(start, i)
                if (peek("(")) {
                    i++
                    val args = mutableListOf<Double>()
                    skipSpace()
                    if (i < text.length && text[i] == ')') i++ else {
                        while (true) {
                            args += expr()
                            skipSpace()
                            if (i < text.length && text[i] == ',') { i++; continue }
                            require(i < text.length && text[i] == ')') { "调用缺少右括号" }
                            i++
                            break
                        }
                    }
                    return call(name, args)
                }
                return vars[name] ?: throw IllegalArgumentException("未知变量 $name")
            }
            throw IllegalArgumentException("无法解析 ${text.substring(i).take(8)}")
        }

        private fun call(name: String, args: List<Double>): Double = when (name) {
            "round" -> Math.rint(args[0])          // Python: ties to even
            "floor" -> floor(args[0])
            "ceil" -> ceil(args[0])
            "int" -> args[0].toLong().toDouble()
            "abs" -> abs(args[0])
            "max" -> args.reduce { a, b -> if (a >= b) a else b }
            "min" -> args.reduce { a, b -> if (a <= b) a else b }
            "sqrt" -> kotlin.math.sqrt(args[0])
            "pow" -> args[0].pow(args[1])
            else -> throw IllegalArgumentException("未知函数 $name")
        }

        /** Python `%`: the sign follows the divisor, unlike Kotlin's `%`. */
        private fun pythonMod(a: Double, b: Double): Double = a - b * floor(a / b)
    }
}
