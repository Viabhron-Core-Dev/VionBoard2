/*
 * VionBoard — VionProtectedSuggestions.kt
 * Handles protected suggestion display (masked hints) and coordinates
 * the biometric/password auth flow before revealing protected text.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin

import android.content.Context
import android.content.Intent
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.database.ProtectedEntriesDao
import helium314.keyboard.latin.dictionary.Dictionary

/**
 * VionBoard protected suggestions engine.
 *
 * When the user types a trigger that matches a protected entry,
 * a MASKED suggestion appears in the strip. The actual protected
 * text is never shown until authentication succeeds.
 *
 * Auth flow:
 *  1. User types a trigger (e.g. "91")
 *  2. Masked suggestion appears in strip ("91••••••21")
 *  3. User taps the masked suggestion
 *  4. Keyboard calls [handleSuggestionTap] → launches BiometricBridgeActivity
 *  5. System biometric prompt appears
 *  6. On success → [onAuthSuccess] → keyboard inserts plaintext
 *  7. On biometric failure → master password dialog shown inline
 *
 * Plaintext is held in memory only during insertion. Never written to
 * clipboard or logs. Zeroed by GC after callback completes.
 */
object VionProtectedSuggestions {

    private var dao: ProtectedEntriesDao? = null

    // Pending trigger awaiting auth — cleared on success or failure
    @Volatile private var pendingTrigger: String? = null

    /**
     * Callback invoked by BiometricBridgeActivity after successful auth.
     * Set by LatinIME to handle text insertion.
     */
    @Volatile private var _onTextReady: ((plaintext: String) -> Unit)? = null

    /** Sets the callback for text insertion after successful auth. */
    fun setOnTextReady(callback: (String) -> Unit) {
        _onTextReady = callback
    }

    /** Gets the current callback. */
    fun getOnTextReady(): ((String) -> Unit)? = _onTextReady

    /** Must be called once on keyboard init (LatinIME.onCreate). */
    fun init(context: Context) {
        dao = ProtectedEntriesDao.getInstance(context)
    }

    /**
     * Returns a masked SuggestedWordInfo if the typed word matches a protected entry.
     * e.g. typing "91" returns a suggestion showing "91••••••21".
     * Safe to display — no plaintext exposed.
     */
    fun getMaskedSuggestion(typedWord: String): SuggestedWordInfo? {
        if (typedWord.length < 2) return null
        val d = dao ?: return null
        val masked = d.findMasked(typedWord) ?: return null
        return SuggestedWordInfo(
            masked,
            "",
            SuggestedWordInfo.MAX_SCORE - 10, // highest priority in strip
            SuggestedWordInfo.KIND_TYPED,
            Dictionary.DICTIONARY_USER_TYPED,
            SuggestedWordInfo.NOT_AN_INDEX,
            SuggestedWordInfo.NOT_A_CONFIDENCE
        )
    }

    /**
     * Injects a masked protected suggestion at position 0 (top priority).
     * Called from Suggest.kt before shortcuts and number injections.
     */
    fun injectProtectedSuggestion(
        typedWord: String,
        suggestions: ArrayList<SuggestedWordInfo>
    ) {
        val suggestion = getMaskedSuggestion(typedWord) ?: return
        suggestions.add(0, suggestion)
    }

    /**
     * Called when user taps a suggestion. Checks if it's a protected entry
     * and launches auth if so.
     *
     * @param context  keyboard context (LatinIME)
     * @param tappedWord  the text of the tapped suggestion (masked text)
     * @param currentTrigger  what the user typed (the trigger word)
     * @return true if intercepted (protected entry), false to let normal tap proceed
     */
    fun handleSuggestionTap(context: Context, tappedWord: String, currentTrigger: String): Boolean {
        val d = dao ?: return false
        val masked = d.findMasked(currentTrigger) ?: return false
        if (tappedWord != masked) return false
        val authMode = d.findAuthMode(currentTrigger) ?: return false
        pendingTrigger = currentTrigger
        launchBiometricBridge(context, currentTrigger, authMode)
        return true
    }

    private fun launchBiometricBridge(
        context: Context,
        trigger: String,
        authMode: ProtectedEntriesDao.AuthMode
    ) {
        val intent = Intent(context, BiometricBridgeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(BiometricBridgeActivity.EXTRA_TRIGGER, trigger)
            putExtra(BiometricBridgeActivity.EXTRA_AUTH_MODE, authMode.value)
        }
        context.startActivity(intent)
    }

    /**
     * Called by BiometricBridgeActivity after successful authentication.
     * Decrypts the protected text and delivers it to the keyboard for insertion.
     * Plaintext is never stored — it flows directly into the input connection.
     */
    fun onAuthSuccess(trigger: String) {
        if (pendingTrigger != trigger) {
            pendingTrigger = null
            return
        }
        val d = dao ?: run { pendingTrigger = null; return }
        val plaintext = d.revealProtectedText(trigger)
        if (!plaintext.isNullOrEmpty()) {
            getOnTextReady()?.invoke(plaintext)
        }
        pendingTrigger = null
    }

    /**
     * Called by BiometricBridgeActivity on cancellation or failure.
     * Clears pending state — no text is inserted.
     */
    fun onAuthCancelled() {
        pendingTrigger = null
    }

    /**
     * Returns true if the given word is the masked display for this trigger.
     * Used by the suggestion tap handler to identify protected taps.
     */
    fun isMaskedSuggestion(trigger: String, word: String): Boolean {
        val d = dao ?: return false
        return d.findMasked(trigger) == word
    }
}
