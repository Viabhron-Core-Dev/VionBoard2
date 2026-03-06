/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin

import android.text.TextUtils
import com.android.inputmethod.latin.utils.BinaryDictionaryUtils
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.common.StringUtils
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.define.DecoderSpecificConstants.SHOULD_AUTO_CORRECT_USING_NON_WHITE_LISTED_SUGGESTION
import helium314.keyboard.latin.define.DecoderSpecificConstants.SHOULD_REMOVE_PREVIOUSLY_REJECTED_SUGGESTION
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.AutoCorrectionUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SuggestionResults
import java.util.Locale
import kotlin.math.min

/**
 * This class loads a dictionary and provides a list of suggestions for a given sequence of
 * characters. This includes corrections and completions.
 */
class Suggest(private val mDictionaryFacilitator: DictionaryFacilitator) {
    private var mAutoCorrectionThreshold = 0f
    private val mPlausibilityThreshold = 0f
    private val nextWordSuggestionsCache = HashMap<NgramContext, SuggestionResults>()

    // cache cleared whenever LatinIME.loadSettings is called, notably on changing layout and switching input fields
    fun clearNextWordSuggestionsCache() = nextWordSuggestionsCache.clear()

    /**
     * Set the normalized-score threshold for a suggestion to be considered strong enough that we
     * will auto-correct to this.
     * @param threshold the threshold
     */
    fun setAutoCorrectionThreshold(threshold: Float) {
        mAutoCorrectionThreshold = threshold
    }

    // todo: remove when InputLogic is ready
    interface OnGetSuggestedWordsCallback {
        fun onGetSuggestedWords(suggestedWords: SuggestedWords?)
    }

    fun getSuggestedWords(wordComposer: WordComposer, ngramContext: NgramContext, keyboard: Keyboard,
                          settingsValuesForSuggestion: SettingsValuesForSuggestion, isCorrectionEnabled: Boolean,
                          inputStyle: Int, sequenceNumber: Int): SuggestedWords =
        if (wordComposer.isBatchMode) {
            getSuggestedWordsForBatchInput(wordComposer, ngramContext, keyboard, settingsValuesForSuggestion,
                inputStyle, sequenceNumber)
        } else {
            getSuggestedWordsForNonBatchInput(wordComposer, ngramContext, keyboard, settingsValuesForSuggestion,
                inputStyle, isCorrectionEnabled, sequenceNumber)
        }

    // Retrieves suggestions for non-batch input (typing, recorrection, predictions...)
    // and calls the callback function with the suggestions.
    private fun getSuggestedWordsForNonBatchInput(wordComposer: WordComposer, ngramContext: NgramContext, keyboard: Keyboard,
                      settingsValuesForSuggestion: SettingsValuesForSuggestion, inputStyleIfNotPrediction: Int,
                      isCorrectionEnabled: Boolean, sequenceNumber: Int): SuggestedWords {
        val typedWordString = wordComposer.typedWord
        val resultsArePredictions = !wordComposer.isComposingWord
        val suggestionResults = if (typedWordString.isEmpty())
                getNextWordSuggestions(ngramContext, keyboard, inputStyleIfNotPrediction, settingsValuesForSuggestion)
            else mDictionaryFacilitator.getSuggestionResults(wordComposer.composedDataSnapshot, ngramContext, keyboard,
                settingsValuesForSuggestion, SESSION_ID_TYPING, inputStyleIfNotPrediction)
        val trailingSingleQuotesCount = StringUtils.getTrailingSingleQuotesCount(typedWordString)
        val suggestionsContainer = getTransformedSuggestedWordInfoList(wordComposer, suggestionResults,
            trailingSingleQuotesCount, mDictionaryFacilitator.mainLocale, keyboard)
        val keyboardShiftMode = keyboard.mId.keyboardCapsMode
        val capitalizedTypedWord = capitalize(typedWordString, keyboardShiftMode == WordComposer.CAPS_MODE_MANUAL_SHIFT_LOCKED,
            keyboardShiftMode == WordComposer.CAPS_MODE_MANUAL_SHIFTED, mDictionaryFacilitator.mainLocale)

        // store the original SuggestedWordInfo for typed word, as it will be removed
        // we may want to re-add it in case auto-correction happens, so that the original word can at least be selected
        val typedWordFirstOccurrenceWordInfo = suggestionsContainer.firstOrNull { it.mWord == capitalizedTypedWord }
        val firstOccurrenceOfTypedWordInSuggestions = SuggestedWordInfo.removeDupsAndTypedWord(capitalizedTypedWord, suggestionsContainer)
        makeFirstTwoSuggestionsNonEmoji(suggestionsContainer)

        val (allowsToBeAutoCorrected, hasAutoCorrection) = shouldBeAutoCorrected(
            trailingSingleQuotesCount,
            capitalizedTypedWord,
            suggestionsContainer.firstOrNull(),
            {
                val first = suggestionsContainer.firstOrNull() ?: suggestionResults.first()
                val suggestions = getNextWordSuggestions(ngramContext, keyboard, inputStyleIfNotPrediction, settingsValuesForSuggestion)
                val suggestionForFirstInContainer = suggestions.firstOrNull { it.mWord == first.word }
                val suggestionForTypedWord = suggestions.firstOrNull { it.mWord == capitalizedTypedWord }
                suggestionForFirstInContainer to suggestionForTypedWord
            },
            isCorrectionEnabled,
            wordComposer,
            suggestionResults,
            firstOccurrenceOfTypedWordInSuggestions,
            typedWordFirstOccurrenceWordInfo
        )
         val typedWordInfo = SuggestedWordInfo(capitalizedTypedWord, "", SuggestedWordInfo.MAX_SCORE,
            SuggestedWordInfo.KIND_TYPED, typedWordFirstOccurrenceWordInfo?.mSourceDict ?: Dictionary.DICTIONARY_USER_TYPED,
            SuggestedWordInfo.NOT_AN_INDEX , SuggestedWordInfo.NOT_A_CONFIDENCE)
        if (!TextUtils.isEmpty(capitalizedTypedWord)) {
            suggestionsContainer.add(0, typedWordInfo)
        }

        // VionBoard: inject contextual number suggestions when relevant
        VionSuggestionEngine.injectNumberSuggestions(typedWordString, suggestionsContainer)

        val suggestionsList = if (SuggestionStripView.DEBUG_SUGGESTIONS && suggestionsContainer.isNotEmpty())
                getSuggestionsInfoListWithDebugInfo(capitalizedTypedWord, suggestionsContainer)
            else suggestionsContainer

        val inputStyle = if (resultsArePredictions) {
            if (suggestionResults.mIsBeginningOfSentence) SuggestedWords.INPUT_STYLE_BEGINNING_OF_SENTENCE_PREDICTION
            else SuggestedWords.INPUT_STYLE_PREDICTION
        } else {
            inputStyleIfNotPrediction
        }

        // If there is an incoming autocorrection, make sure typed word is shown, so user is able to override it.
        // Otherwise, if the relevant setting is enabled, show the typed word in the middle.
        val indexOfTypedWord = if (hasAutoCorrection) 2 else 1
        if ((hasAutoCorrection || (Settings.getValues().mCenterSuggestionTextToEnter && !wordComposer.isResumed)
                || capitalizedTypedWord != wordComposer.typedWord)
            && suggestionsList.size >= indexOfTypedWord && !TextUtils.isEmpty(capitalizedTypedWord)) {
            if (typedWordFirstOccurrenceWordInfo != null) {
                addDebugInfo(typedWordFirstOccurrenceWordInfo, capitalizedTypedWord)
                suggestionsList.add(indexOfTypedWord, typedWordFirstOccurrenceWordInfo)
            } else {
                suggestionsList.add(indexOfTypedWord,
                    SuggestedWordInfo(capitalizedTypedWord, "", 0, SuggestedWordInfo.KIND_TYPED,
                        Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
                )
            }
        }
        val isTypedWordValid = firstOccurrenceOfTypedWordInSuggestions > -1 || (!resultsArePredictions && !allowsToBeAutoCorrected)
        return SuggestedWords(suggestionsList, suggestionResults.mRawSuggestions,
            typedWordInfo, isTypedWordValid, hasAutoCorrection, false, inputStyle, sequenceNumber)
    }

    // returns [allowsToBeAutoCorrected, hasAutoCorrection]
    // public for testing
    fun shouldBeAutoCorrected(
        trailingSingleQuotesCount: Int,
        typedWordString: String,
        firstSuggestionInContainer: SuggestedWordInfo?,
        getEmptyWordSuggestions: () -> Pair<SuggestedWordInfo?, SuggestedWordInfo?>,
        isCorrectionEnabled: Boolean,
        wordComposer: WordComposer,
        suggestionResults: SuggestionResults,
        firstOccurrenceOfTypedWordInSuggestions: Int,
        typedWordInfo: SuggestedWordInfo?
    ): Pair<Boolean, Boolean> {
        val consideredWord = typedWordString.dropLast(trailingSingleQuotesCount)
        val firstAndTypedEmptyInfos by lazy { getEmptyWordSuggestions() }

        val scoreLimit = Settings.getValues().mScoreLimitForAutocorrect
        // We allow auto-correction if whitelisting is not required or the word is whitelisted,
        // or if the word had more than one char and was not suggested.
        val allowsToBeAutoCorrected: Boolean
        if (SHOULD_AUTO_CORRECT_USING_NON_WHITE_LISTED_SUGGESTION
                || firstSuggestionInContainer?.isKindOf(SuggestedWordInfo.KIND_WHITELIST) == true
                || (consideredWord.length > 1
                    && typedWordInfo?.mSourceDict == null // more than 1 letter and not in dictionary
                    // if the typed word contains @ or ., the suggestion also needs to contain it
                    // (avoid autocorrecting mail addresses, URLs & similar to something different)
                    && (!typedWordString.contains('@') || firstSuggestionInContainer?.mWord?.contains('@') == true)
                    && (!typedWordString.contains('.') || firstSuggestionInContainer?.mWord?.contains('.') == true)
                    )
            ) {
            allowsToBeAutoCorrected = true
        } else if (firstSuggestionInContainer != null && typedWordString.isNotEmpty()) {
            // maybe allow autocorrect, depending on scores and emptyWordSuggestions
            val first = firstAndTypedEmptyInfos.first
            val typed = firstAndTypedEmptyInfos.second
            allowsToBeAutoCorrected = when {
                firstSuggestionInContainer.mScore > scoreLimit -> true // suggestion has good score, allow
                first == null -> false // no autocorrect if first suggestion unknown in this ngram context
                typed == null -> true // allow autocorrect if typed word not known in this ngram context, todo: this may be too aggressive
                else -> first.mScore - typed.mScore > 20 // autocorrect if suggested word has clearly higher score for empty word suggestions
            }
        } else {
            allowsToBeAutoCorrected = false
        }
        // If correction is not enabled, we never auto-correct. This is for example for when
        // the setting "Auto-correction" is "off": we still suggest, but we don't auto-correct.
        val hasAutoCorrection: Boolean
        if (!isCorrectionEnabled
            || !allowsToBeAutoCorrected
            || !wordComposer.isComposingWord
            || suggestionResults.isEmpty()
            || wordComposer.hasDigits()
            || wordComposer.isMostlyCaps
            || wordComposer.isResumed
            || !mDictionaryFacilitator.hasAtLeastOneInitializedMainDictionary()
        ) {
            hasAutoCorrection = false
        } else {
            val firstSuggestion = firstSuggestionInContainer ?: suggestionResults.first()
            if (suggestionResults.mFirstSuggestionExceedsConfidenceThreshold && firstOccurrenceOfTypedWordInSuggestions != 0) {
                return true to true
            }
            if (!AutoCorrectionUtils.suggestionExceedsThreshold(firstSuggestion, consideredWord, mAutoCorrectionThreshold)) {
                return true to false
            }
            val allowed = isAllowedByAutoCorrectionWithSpaceFilter(firstSuggestion)
            if (allowed && typedWordInfo != null && typedWordInfo.mScore > scoreLimit) {
                val dictLocale = mDictionaryFacilitator.currentLocale
                if (firstSuggestion.mScore < scoreLimit) {
                    return true to false
                }
                if (firstSuggestion.mSourceDict.mLocale !== typedWordInfo.mSourceDict.mLocale) {
                    return true to (dictLocale == firstSuggestion.mSourceDict.mLocale)
                }
                val firstWordBonusScore =
                    ((if (firstSuggestion.isKindOf(SuggestedWordInfo.KIND_WHITELIST)) 20 else 0)
                            + (if (StringUtils.isLowerCaseAscii(typedWordString)) 5 else 0)
                            + if (firstSuggestion.mScore > typedWordInfo.mScore) 5 else 0)
                val firstScoreForEmpty = firstAndTypedEmptyInfos.first?.mScore ?: 0
                val typedScoreForEmpty = firstAndTypedEmptyInfos.second?.mScore ?: 0
                if (firstScoreForEmpty + firstWordBonusScore >= typedScoreForEmpty + 20) {
                    return true to true
                }
                hasAutoCorrection = false
            } else {
                hasAutoCorrection = allowed
            }
        }
        return allowsToBeAutoCorrected to hasAutoCorrection
    }

    // Retrieves suggestions for the batch input
    private fun getSuggestedWordsForBatchInput(
        wordComposer: WordComposer,
        ngramContext: NgramContext, keyboard: Keyboard,
        settingsValuesForSuggestion: SettingsValuesForSuggestion,
        inputStyle: Int, sequenceNumber: Int
    ): SuggestedWords {
        val suggestionResults = mDictionaryFacilitator.getSuggestionResults(
            wordComposer.composedDataSnapshot, ngramContext, keyboard,
            settingsValuesForSuggestion, SESSION_ID_GESTURE, inputStyle
        )
        replaceSingleLetterFirstSuggestion(suggestionResults)

        val locale = mDictionaryFacilitator.mainLocale
        val suggestionsContainer = ArrayList(suggestionResults)
        val suggestionsCount = suggestionsContainer.size
        val keyboardShiftMode = keyboard.mId.keyboardCapsMode
        val shouldMakeSuggestionsOnlyFirstCharCapitalized = wordComposer.wasShiftedNoLock()
            || keyboardShiftMode == WordComposer.CAPS_MODE_MANUAL_SHIFTED
        val shouldMakeSuggestionsAllUpperCase = wordComposer.isAllUpperCase
            || keyboardShiftMode == WordComposer.CAPS_MODE_MANUAL_SHIFT_LOCKED
        if (shouldMakeSuggestionsOnlyFirstCharCapitalized || shouldMakeSuggestionsAllUpperCase) {
            for (i in 0 until suggestionsCount) {
                val wordInfo = suggestionsContainer[i]
                val wordLocale = wordInfo!!.mSourceDict.mLocale
                val transformedWordInfo = getTransformedSuggestedWordInfo(
                    wordInfo, wordLocale ?: locale, shouldMakeSuggestionsAllUpperCase,
                    shouldMakeSuggestionsOnlyFirstCharCapitalized, 0
                )
                suggestionsContainer[i] = transformedWordInfo
            }
        }
        val rejected: SuggestedWordInfo?
        if (SHOULD_REMOVE_PREVIOUSLY_REJECTED_SUGGESTION && suggestionsContainer.size > 1 && TextUtils.equals(
                suggestionsContainer[0]!!.mWord,
                wordComposer.rejectedBatchModeSuggestion
            )
        ) {
            rejected = suggestionsContainer.removeAt(0)
            suggestionsContainer.add(1, rejected)
        } else {
            rejected = null
        }
        SuggestedWordInfo.removeDupsAndTypedWord(null, suggestionsContainer)
        makeFirstTwoSuggestionsNonEmoji(suggestionsContainer)

        for (i in suggestionsContainer.indices.reversed()) {
            if (suggestionsContainer[i]!!.mScore < SUPPRESS_SUGGEST_THRESHOLD) {
                suggestionsContainer.removeAt(i)
            }
        }

        val capitalizedTypedWord = capitalize(wordComposer.typedWord, keyboardShiftMode == WordComposer.CAPS_MODE_MANUAL_SHIFT_LOCKED,
            keyboardShiftMode == WordComposer.CAPS_MODE_MANUAL_SHIFTED, locale)
        if (capitalizedTypedWord != wordComposer.typedWord && suggestionsContainer.drop(1).none { it.mWord == capitalizedTypedWord }) {
            suggestionsContainer.add(min(1, suggestionsContainer.size),
                SuggestedWordInfo(capitalizedTypedWord, "", 0, SuggestedWordInfo.KIND_TYPED,
                    Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
            )
        }

        val pseudoTypedWordInfo = preferNextWordSuggestion(
            suggestionsContainer.firstOrNull(),
            suggestionsContainer, getNextWordSuggestions(ngramContext, keyboard, inputStyle, settingsValuesForSuggestion), rejected
        )
        val suggestionsList = if (SuggestionStripView.DEBUG_SUGGESTIONS && suggestionsContainer.isNotEmpty()) {
            getSuggestionsInfoListWithDebugInfo(suggestionResults.first().mWord, suggestionsContainer)
        } else {
            suggestionsContainer
        }
        return SuggestedWords(suggestionsList, suggestionResults.mRawSuggestions, pseudoTypedWordInfo, true,
            false, false, inputStyle, sequenceNumber)
    }

    /** get suggestions based on the current ngram context, with an empty typed word */
    private fun getNextWordSuggestions(ngramContext: NgramContext, keyboard: Keyboard, inputStyle: Int,
                                       settingsValuesForSuggestion: SettingsValuesForSuggestion): SuggestionResults {
        val cachedResults = nextWordSuggestionsCache[ngramContext]
        if (cachedResults != null) return cachedResults
        val newResults = mDictionaryFacilitator.getSuggestionResults(ComposedData(InputPointers(1),
            false, ""), ngramContext, keyboard, settingsValuesForSuggestion, SESSION_ID_TYPING, inputStyle)
        nextWordSuggestionsCache[ngramContext] = newResults
        return newResults
    }

    companion object {
        private val TAG: String = Suggest::class.java.simpleName

        const val SESSION_ID_TYPING = 0
        const val SESSION_ID_GESTURE = 0

        private const val SUPPRESS_SUGGEST_THRESHOLD = -2000000000

        private const val MAXIMUM_AUTO_CORRECT_LENGTH_FOR_GERMAN = 12
        private val sLanguageToMaximumAutoCorrectionWithSpaceLength = hashMapOf(Locale.GERMAN.language to MAXIMUM_AUTO_CORRECT_LENGTH_FOR_GERMAN)

        private fun getTransformedSuggestedWordInfoList(
            wordComposer: WordComposer, results: SuggestionResults,
            trailingSingleQuotesCount: Int, defaultLocale: Locale, keyboard: Keyboard
        ): ArrayList<SuggestedWordInfo> {
            val keyboardShiftMode = keyboard.mId.keyboardCapsMode
            val shouldMakeSuggestionsAllUpperCase = wordComposer.isAllUpperCase && !wordComposer.isResumed
                || keyboardShiftMode == WordComposer.CAPS_MODE_MANUAL_SHIFT_LOCKED
            val shouldMakeSuggestionsOnlyFirstCharCapitalized = wordComposer.isOrWillBeOnlyFirstCharCapitalized
                || keyboardShiftMode == WordComposer.CAPS_MODE_MANUAL_SHIFTED
            val suggestionsContainer = ArrayList(results)
            val suggestionsCount = suggestionsContainer.size
            if (shouldMakeSuggestionsOnlyFirstCharCapitalized || shouldMakeSuggestionsAllUpperCase || 0 != trailingSingleQuotesCount) {
                for (i in 0 until suggestionsCount) {
                    val wordInfo = suggestionsContainer[i]
                    val wordLocale = wordInfo.mSourceDict.mLocale
                    val transformedWordInfo = getTransformedSuggestedWordInfo(
                        wordInfo, wordLocale ?: defaultLocale,
                        shouldMakeSuggestionsAllUpperCase, shouldMakeSuggestionsOnlyFirstCharCapitalized,
                        trailingSingleQuotesCount
                    )
                    suggestionsContainer[i] = transformedWordInfo
                }
            }
            return suggestionsContainer
        }

        private fun getSuggestionsInfoListWithDebugInfo(
            typedWord: String, suggestions: ArrayList<SuggestedWordInfo>
        ): ArrayList<SuggestedWordInfo> {
            val suggestionsSize = suggestions.size
            val suggestionsList = ArrayList<SuggestedWordInfo>(suggestionsSize)
            for (cur in suggestions) {
                addDebugInfo(cur, typedWord)
                suggestionsList.add(cur)
            }
            return suggestionsList
        }

        @JvmStatic
        fun addDebugInfo(wordInfo: SuggestedWordInfo?, typedWord: String) {
            if (!SuggestionStripView.DEBUG_SUGGESTIONS)
                return
            val normalizedScore = BinaryDictionaryUtils.calcNormalizedScore(typedWord, wordInfo.toString(), wordInfo!!.mScore)
            val scoreInfoString: String
            val dict = wordInfo.mSourceDict.mDictType + ":" + wordInfo.mSourceDict.mLocale
            scoreInfoString = if (normalizedScore > 0) {
                String.format(Locale.ROOT, "%d (%4.2f), %s", wordInfo.mScore, normalizedScore, dict)
            } else {
                String.format(Locale.ROOT, "%d, %s", wordInfo.mScore, dict)
            }
            wordInfo.debugString = scoreInfoString
        }

        private fun isAllowedByAutoCorrectionWithSpaceFilter(info: SuggestedWordInfo): Boolean {
            val locale = info.mSourceDict.mLocale ?: return true
            val maximumLengthForThisLanguage = sLanguageToMaximumAutoCorrectionWithSpaceLength[locale.language]
                ?: return true
            return (info.mWord.length <= maximumLengthForThisLanguage
                    || -1 == info.mWord.indexOf(Constants.CODE_SPACE.toChar()))
        }

        // public for testing
        fun getTransformedSuggestedWordInfo(
            wordInfo: SuggestedWordInfo, locale: Locale, isAllUpperCase: Boolean,
            isOnlyFirstCharCapitalized: Boolean, trailingSingleQuotesCount: Int
        ): SuggestedWordInfo {
            var capitalizedWord = capitalize(wordInfo.mWord, isAllUpperCase, isOnlyFirstCharCapitalized, locale)
            val quotesToAppend = (trailingSingleQuotesCount
                    - if (-1 == wordInfo.mWord.indexOf(Constants.CODE_SINGLE_QUOTE.toChar())) 0 else 1)
            for (i in quotesToAppend - 1 downTo 0) {
                capitalizedWord = "$capitalizedWord'"
            }
            return SuggestedWordInfo(
                capitalizedWord, wordInfo.mPrevWordsContext,
                wordInfo.mScore, wordInfo.mKindAndFlags,
                wordInfo.mSourceDict, wordInfo.mIndexOfTouchPointOfSecondWord,
                wordInfo.mAutoCommitFirstWordConfidence
            )
        }

        private fun capitalize(word: String, isAllUpperCase: Boolean, isOnlyFirstCharCapitalized: Boolean, locale: Locale) =
            if (isAllUpperCase) {
                word.uppercase(locale)
            } else if (isOnlyFirstCharCapitalized) {
                StringUtils.capitalizeFirstCodePoint(word, locale)
            } else {
                word
            }

        private fun makeFirstTwoSuggestionsNonEmoji(words: MutableList<SuggestedWordInfo>) {
            for (i in 0..1) {
                if (words.size > 2 && words[i].isEmoji) {
                    val relativeIndex = words.subList(2, words.size).indexOfFirst { !it.isEmoji }
                    if (relativeIndex < 0) break
                    val firstNonEmojiIndex = relativeIndex + 2
                    if (firstNonEmojiIndex > i) {
                        words.add(i, words.removeAt(firstNonEmojiIndex))
                    }
                }
            }
        }

        private fun replaceSingleLetterFirstSuggestion(suggestionResults: SuggestionResults) {
            if (suggestionResults.size < 2 || suggestionResults.first().mWord.length != 1) return
            val iterator: Iterator<SuggestedWordInfo> = suggestionResults.iterator()
            val first = iterator.next()
            val second = iterator.next()
            if (second.mWord.length > 1 && second.mScore > 0.94 * first.mScore) {
                suggestionResults.remove(first)
                suggestionResults.add(
                    SuggestedWordInfo(
                        first.mWord, first.mPrevWordsContext, (first.mScore * 0.93).toInt(),
                        first.mKindAndFlags, first.mSourceDict, first.mIndexOfTouchPointOfSecondWord, first.mAutoCommitFirstWordConfidence
                    )
                )
                if (DebugFlags.DEBUG_ENABLED)
                    Log.d(TAG, "reduced score of ${first.mWord} from ${first.mScore}, new first: ${suggestionResults.first().mWord} (${suggestionResults.first().mScore})")
            }
        }

        private fun preferNextWordSuggestion(
            pseudoTypedWordInfo: SuggestedWordInfo?,
            suggestionsContainer: ArrayList<SuggestedWordInfo>,
            nextWordSuggestions: SuggestionResults, rejected: SuggestedWordInfo?
        ): SuggestedWordInfo? {
            if (pseudoTypedWordInfo == null || !Settings.getValues().mUsePersonalizedDicts
                || pseudoTypedWordInfo.mSourceDict.mDictType != Dictionary.TYPE_MAIN || suggestionsContainer.size < 2
            ) return pseudoTypedWordInfo
            nextWordSuggestions.removeAll { info: SuggestedWordInfo -> info.mScore < 170 }
            if (nextWordSuggestions.isEmpty()) return pseudoTypedWordInfo

            for (suggestion in suggestionsContainer) {
                if (suggestion.mScore < pseudoTypedWordInfo.mScore * 0.93) break
                if (suggestion === rejected) continue
                for (nextWordSuggestion in nextWordSuggestions) {
                    if (nextWordSuggestion.mWord != suggestion.mWord) continue
                    suggestionsContainer.remove(suggestion)
                    suggestionsContainer.add(0, suggestion)
                    if (DebugFlags.DEBUG_ENABLED)
                        Log.d(TAG, "replaced batch word $pseudoTypedWordInfo with $suggestion")
                    return suggestion
                }
            }
            return pseudoTypedWordInfo
        }
    }
}
