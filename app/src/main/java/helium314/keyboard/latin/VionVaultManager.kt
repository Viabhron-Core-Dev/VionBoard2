// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.io.File

/**
 * VionVaultManager — in-memory session manager for the unlocked KeePass vault.
 *
 * Lifecycle:
 *  1. Call [init] once from LatinIME.onCreate to register the screen-off receiver.
 *  2. Call [unlock] with the .kdbx file and master password (entered by user in the panel).
 *  3. Entries are cached in memory. [getEntriesForPackage] filters by current app.
 *  4. Session is cleared automatically on screen off, or explicitly via [lock].
 *
 * Biometric integration (Phase 9): the panel UI will gate [unlock] behind the
 * Phase 5b BiometricBridgeActivity before prompting for the master password.
 */
object VionVaultManager {

    private const val PREFS_NAME     = "vion_vault"
    private const val KEY_VAULT_PATH = "vault_path"
    private const val KEY_AUTO_MATCH = "auto_match"

    // ── Session state ─────────────────────────────────────────────────────────

    private var _entries: List<VionVaultEntry> = emptyList()
    private var _isUnlocked = false
    private var screenOffReceiver: BroadcastReceiver? = null

    val isUnlocked: Boolean get() = _isUnlocked

    /** All entries from the currently unlocked vault. Empty when locked. */
    val entries: List<VionVaultEntry> get() = _entries

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (screenOffReceiver != null) return
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) lock()
            }
        }
        context.applicationContext.registerReceiver(
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF)
        )
    }

    // ── Unlock / Lock ─────────────────────────────────────────────────────────

    /**
     * Unlock the vault with [password].
     * Returns true on success, false on wrong password or corrupt file.
     */
    fun unlock(vaultFile: File, password: String): Boolean {
        return try {
            _entries = VionVaultRepository.loadFromFile(vaultFile, password).getOrThrow()
            _isUnlocked = true
            true
        } catch (_: Exception) {
            _isUnlocked = false
            _entries = emptyList()
            false
        }
    }

    fun lock() {
        _isUnlocked = false
        _entries = emptyList()
    }

    // ── Entry lookup ──────────────────────────────────────────────────────────

    fun getEntriesForPackage(packageName: String?): List<VionVaultEntry> {
        if (!_isUnlocked) return emptyList()
        if (packageName == null) return _entries
        val parts = packageName.split(".")
            .filter { it.length > 3 && it !in IGNORED_SEGMENTS }
        if (parts.isEmpty()) return _entries
        return _entries.filter { entry ->
            val url   = entry.url.lowercase()
            val title = entry.title.lowercase()
            parts.any { part -> url.contains(part) || title.contains(part) }
        }
    }

    fun search(query: String): List<VionVaultEntry> {
        if (!_isUnlocked || query.isBlank()) return _entries
        val q = query.trim().lowercase()
        return _entries.filter { entry ->
            entry.title.lowercase().contains(q)
                || entry.username.lowercase().contains(q)
                || entry.url.lowercase().contains(q)
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    fun saveVaultPath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_VAULT_PATH, path).apply()
    }

    fun loadVaultPath(context: Context): String? =
        prefs(context).getString(KEY_VAULT_PATH, null)

    fun loadVaultFile(context: Context): File? {
        val path = loadVaultPath(context) ?: return null
        return File(path).takeIf { it.exists() }
    }

    fun saveAutoMatch(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_MATCH, enabled).apply()
    }

    fun isAutoMatchEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_MATCH, true)

    // ── Private ───────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val IGNORED_SEGMENTS = setOf(
        "android", "google", "com", "org", "net", "app", "www",
        "mobile", "browser", "client", "main", "base",
    )
}
