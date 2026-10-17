package com.nuvio.tv.core.streams.ranking

import com.florianingerl.util.regex.Pattern
import com.florianingerl.util.regex.PatternSyntaxException

/** Ordered preference tiers, independent of Android, streams, repositories, and playback. */
class RankedRegexRules private constructor(private val rules: List<Pattern>) {
    /**
     * Only matching items are returned. The earliest matching rule wins; ties retain input order.
     * null means evaluation failed, so callers must show manual selection rather than pick a
     * lower tier on incomplete evidence. Never infer exclusions by rewriting a user's regex.
     */
    fun <T> rank(
        items: List<T>,
        checkCancelled: () -> Unit = {},
        nanoTime: () -> Long = System::nanoTime,
        text: (T) -> String
    ): List<T>? {
        val started = nanoTime()
        val checkpoint = {
            checkCancelled()
            if (nanoTime() - started >= MAX_DURATION_NANOS) throw EvaluationLimit()
        }
        val tiers = List(rules.size) { ArrayList<T>() }
        return try {
            for (item in items) {
                checkpoint()
                val value = text(item)
                if (value.length > MAX_TEXT_LENGTH) throw EvaluationLimit()
                // A large batch must not consume one shared read allowance: each candidate
                // gets the same protection, while the deadline bounds the complete pass.
                val bounded = BoundedText(value, 0, value.length, ReadBudget(checkpoint))
                val tier = rules.indexOfFirst { it.matcher(bounded).find() }
                if (tier >= 0) tiers[tier].add(item)
            }
            checkpoint()
            tiers.flatten()
        } catch (_: EvaluationLimit) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    companion object {
        const val MAX_RULES = 20
        const val MAX_PATTERN_LENGTH = 2048
        private const val MAX_TEXT_LENGTH = 32_768
        private const val MAX_DURATION_NANOS = 2_000_000_000L

        fun isValidPattern(pattern: String): Boolean =
            pattern.isNotBlank() && pattern.length <= MAX_PATTERN_LENGTH && compile(pattern) != null

        fun create(patterns: List<String>): RankedRegexRules? {
            if (patterns.isEmpty() || patterns.size > MAX_RULES) return null
            val compiled = patterns.map { pattern ->
                if (pattern.isBlank() || pattern.length > MAX_PATTERN_LENGTH) return null
                compile(pattern) ?: return null
            }
            return RankedRegexRules(compiled)
        }

        private fun compile(pattern: String): Pattern? = try {
            Pattern.compile(pattern.trim(), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        } catch (_: PatternSyntaxException) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    // Use a pure Java matcher: Android's native matcher converts CharSequence to String,
    // bypassing read limits. Character-read and elapsed-time checks interrupt
    // pathological patterns during matching, without leaving an uncancellable worker behind.
    private class EvaluationLimit : RuntimeException()
    private class ReadBudget(val checkpoint: () -> Unit, var remaining: Int = 1_000_000) {
        fun read() {
            if (--remaining < 0) throw EvaluationLimit()
            if (remaining and 1023 == 0) checkpoint()
        }
    }
    private class BoundedText(
        private val value: String,
        private val start: Int,
        private val end: Int,
        private val budget: ReadBudget
    ) : CharSequence {
        override val length: Int get() = end - start
        override fun get(index: Int): Char {
            budget.read()
            if (index !in 0 until length) throw IndexOutOfBoundsException()
            return value[start + index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            require(startIndex in 0..endIndex && endIndex <= length)
            return BoundedText(value, start + startIndex, start + endIndex, budget)
        }
        override fun toString(): String = value.substring(start, end)
    }
}
