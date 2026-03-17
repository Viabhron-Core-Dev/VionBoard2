/*
 * VionBoard — VionPasswordFieldDetector.kt
 * Detects when the user is typing in a password field.
 * Shows vault button only in password contexts.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * VionBoard password field detector.
 *
 * Checks the EditorInfo to determine if the current input field is a password field.
 * Used to show/hide the vault button in the toolbar and suggest password entries.
 */
object VionPasswordFieldDetector {

    /**
     * Returns true if the current input field is a password field.
     * Checks for TYPE_TEXT_VARIATION_PASSWORD, TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
     * and TYPE_TEXT_VARIATION_WEB_PASSWORD.
     */
    fun isPasswordField(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false
        val inputType = editorInfo.inputType
        val variation = InputType.TYPE_MASK_VARIATION and inputType

        return when (variation) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> true
            else -> false
        }
    }

    /**
     * Returns true if the field is likely a username/email field.
     * Checks for TYPE_TEXT_VARIATION_EMAIL_ADDRESS and TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS.
     */
    fun isUsernameField(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false
        val inputType = editorInfo.inputType
        val variation = InputType.TYPE_MASK_VARIATION and inputType

        return when (variation) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> true
            else -> false
        }
    }

    /**
     * Returns true if the field is likely to benefit from vault suggestions.
     * This includes password fields, username fields, and general text fields in login contexts.
     */
    fun shouldShowVaultSuggestions(editorInfo: EditorInfo?): Boolean {
        return isPasswordField(editorInfo) || isUsernameField(editorInfo)
    }
}
