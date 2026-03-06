/*
 * VionBoard — VionSuggestionEngine.kt
 * Handles contextual number suggestions and suggestion augmentation.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin

import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.dictionary.Dictionary
import java.util.Calendar

/**
 * VionBoard's lightweight suggestion engine.
 * Adds contextual number completions into the existing suggestion pipeline.
 * Called from Suggest.kt to inject results before the strip is populated.
 */
object VionSuggestionEngine {

    private val currentYear: Int get() = Calendar.getInstance().get(Calendar.YEAR)

    /**
     * Given the currently typed word, returns a list of number suggestions to inject,
     * or an empty list if the context is not numeric.
     */
    fun getNumberSuggestions(typedWord: String): List<SuggestedWordInfo> {
        if (typedWord.isEmpty()) return emptyList()

        // Only activate when the typed word looks numeric
        if (!looksNumeric(typedWord)) return emptyList()

        val suggestions = mutableListOf<SuggestedWordInfo>()

        when {
            // Year context: "20" → 2025 2026 2027 2028
            typedWord.matches(Regex("^\\d{1,3}$")) && typedWord.toIntOrNull()?.let { it in 0..999 } == true -> {
                val prefix = typedWord
                val year = currentYear
                // Generate years that start with the typed prefix
                val yearCandidates = (year - 1..year + 5).map { it.toString() }
                    .filter { it.startsWith(prefix) && it != prefix }
                yearCandidates.take(4).forEach { yr ->
                    suggestions.add(makeNumberSuggestion(yr, SuggestedWordInfo.MAX_SCORE - 100))
                }
                // Also add decade/century if prefix matches
                if (suggestions.isEmpty()) {
                    // fallback: just show nearby numbers
                    val base = prefix.toInt()
                    listOf(base * 10, base * 10 + 1, base * 100, base * 100 + 1)
                        .filter { it > base }
                        .take(4)
                        .forEach { n ->
                            suggestions.add(makeNumberSuggestion(n.toString(), SuggestedWordInfo.MAX_SCORE - 200))
                        }
                }
            }

            // Month/day context: single digit → 01 02 03...
            typedWord == "0" -> {
                (1..9).take(4).forEach { d ->
                    suggestions.add(makeNumberSuggestion("0$d", SuggestedWordInfo.MAX_SCORE - 150))
                }
            }

            // IP address context: "192.168" → 192.168.0.1  192.168.1.1
            typedWord.matches(Regex("^\\d+\\.\\d+$")) -> {
                val commonSuffixes = listOf(".0.1", ".1.1", ".0.0", ".1.0")
                commonSuffixes.take(4).forEach { suffix ->
                    suggestions.add(makeNumberSuggestion(typedWord + suffix, SuggestedWordInfo.MAX_SCORE - 100))
                }
            }

            // Currency: "$20" → $200  $2000  $20.00  $20.50
            typedWord.matches(Regex("^[\\$€£¥]\\d+$")) -> {
                val symbol = typedWord[0]
                val amount = typedWord.drop(1)
                listOf(
                    "${symbol}${amount}.00",
                    "${symbol}${amount}0",
                    "${symbol}${amount}.50",
                    "${symbol}${amount}00"
                ).take(4).forEach { c ->
                    suggestions.add(makeNumberSuggestion(c, SuggestedWordInfo.MAX_SCORE - 100))
                }
            }

            // Phone number context: typed 3+ digits, suggest adding dash or continuing
            typedWord.matches(Regex("^\\d{3,}$")) -> {
                val n = typedWord.toIntOrNull() ?: return emptyList()
                listOf(
                    "${typedWord}0",
                    "${typedWord}1",
                    "${typedWord}5",
                    "${typedWord}00"
                ).take(4).forEach { ext ->
                    suggestions.add(makeNumberSuggestion(ext, SuggestedWordInfo.MAX_SCORE - 250))
                }
            }
        }

        return suggestions
    }

    /**
     * Injects number suggestions into an existing suggestions list.
     * Number suggestions appear after the typed word (index 1) when relevant,
     * and do not displace existing word suggestions — they are appended.
     */
    fun injectNumberSuggestions(
        typedWord: String,
        suggestions: ArrayList<SuggestedWordInfo>
    ) {
        val numberSuggestions = getNumberSuggestions(typedWord)
        if (numberSuggestions.isEmpty()) return

        // Find insert position: after typed word entry
        val insertAt = minOf(1, suggestions.size)
        suggestions.addAll(insertAt, numberSuggestions)
    }

    private fun looksNumeric(word: String): Boolean {
        if (word.isEmpty()) return false
        // Starts with a digit or a currency symbol followed by digit
        val first = word[0]
        return first.isDigit() || (first in "$€£¥" && word.length > 1 && word[1].isDigit())
    }

    private fun makeNumberSuggestion(word: String, score: Int): SuggestedWordInfo {
        return SuggestedWordInfo(
            word,
            "",
            score,
            SuggestedWordInfo.KIND_COMPLETION,
            Dictionary.DICTIONARY_USER_TYPED,
            SuggestedWordInfo.NOT_AN_INDEX,
            SuggestedWordInfo.NOT_A_CONFIDENCE
        )
    }
}
