// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    private const val PREFS_NAME                  = "vion_vault"
    private const val KEY_VAULT_PATH              = "vault_path"
    private const val KEY_AUTO_MATCH              = "auto_match"
    private const val KEY_BIOMETRIC               = "biometric_enabled"
    private const val KEY_AUTO_LOCK_MS            = "auto_lock_ms"
    private const val KEY_CLIPBOARD_CLEAR         = "clipboard_clear_enabled"
    private const val KEY_CLIPBOARD_CLEAR_DELAY   = "clipboard_clear_delay_ms"

    // Auto-lock defaults
    const val AUTO_LOCK_NEVER    = 0L
    const val AUTO_LOCK_30S      = 30_000L
    const val AUTO_LOCK_1MIN     = 60_000L
    const val AUTO_LOCK_5MIN     = 300_000L
    const val AUTO_LOCK_15MIN    = 900_000L

    // Clipboard clear defaults
    const val CLIPBOARD_DELAY_5S  = 5_000L
    const val CLIPBOARD_DELAY_15S = 15_000L
    const val CLIPBOARD_DELAY_30S = 30_000L
    const val CLIPBOARD_DELAY_60S = 60_000L

    // ── Session state ─────────────────────────────────────────────────────────

    private var _entries: List<VionVaultEntry> = emptyList()
    private var _isUnlocked = false
    private var screenOffReceiver: BroadcastReceiver? = null

    val isUnlocked: Boolean get() = _isUnlocked

    /** All entries from the currently unlocked vault. Empty when locked. */
    val entries: List<VionVaultEntry> get() = _entries

    // ── Auto-lock timer ───────────────────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoLockRunnable: Runnable? = null

    /**
     * Resets the auto-lock countdown. Call this whenever the user interacts
     * with the vault (on unlock, on entry tap). No-op if timeout is NEVER.
     */
    fun resetAutoLock(context: Context) {
        val ms = loadAutoLockMs(context)
        if (ms == AUTO_LOCK_NEVER) return
        cancelAutoLock()
        val r = Runnable { lock() }
        autoLockRunnable = r
        mainHandler.postDelayed(r, ms)
    }

    /** Cancels any pending auto-lock. Call when the vault is manually locked. */
    fun cancelAutoLock() {
        autoLockRunnable?.let { mainHandler.removeCallbacks(it) }
        autoLockRunnable = null
    }

    // ── Clipboard clear ───────────────────────────────────────────────────────

    private var clipboardRunnable: Runnable? = null

    /**
     * Schedules clipboard clearing after the configured delay.
     * Only runs if the clipboard-clear setting is enabled.
     * Safe to call before finish() — uses main looper.
     */
    fun scheduleClipboardClear(context: Context) {
        if (!isClipboardClearEnabled(context)) return
        val delayMs = loadClipboardClearDelayMs(context)
        clipboardRunnable?.let { mainHandler.removeCallbacks(it) }
        val appCtx = context.applicationContext
        val r = Runnable {
            try {
                val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    cm.clearPrimaryClip()
                } else {
                    cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (_: Exception) { /* clipboard not available */ }
            clipboardRunnable = null
        }
        clipboardRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

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
        cancelAutoLock()
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

    // ── Persistence — vault path & auto-match ─────────────────────────────────

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

    // ── Persistence — biometric ───────────────────────────────────────────────

    fun saveBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
    }

    fun isBiometricEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BIOMETRIC, false)

    // ── Persistence — auto-lock ───────────────────────────────────────────────

    fun saveAutoLockMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_AUTO_LOCK_MS, ms).apply()
    }

    /** Returns the auto-lock timeout in milliseconds. 0 = never. */
    fun loadAutoLockMs(context: Context): Long =
        prefs(context).getLong(KEY_AUTO_LOCK_MS, AUTO_LOCK_5MIN)

    // ── Persistence — clipboard clear ─────────────────────────────────────────

    fun saveClipboardClearEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLIPBOARD_CLEAR, enabled).apply()
    }

    fun isClipboardClearEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CLIPBOARD_CLEAR, true)

    fun saveClipboardClearDelayMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_CLIPBOARD_CLEAR_DELAY, ms).apply()
    }

    fun loadClipboardClearDelayMs(context: Context): Long =
        prefs(context).getLong(KEY_CLIPBOARD_CLEAR_DELAY, CLIPBOARD_DELAY_30S)

    // ── Clear all vault settings ──────────────────────────────────────────────

    fun clearAllVaultSettings(context: Context) {
        lock()
        prefs(context).edit().clear().apply()
        // Also remove cached vault file from internal storage
        File(context.filesDir, "vault.kdbx").takeIf { it.exists() }?.delete()
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val IGNORED_SEGMENTS = setOf(
        "android", "google", "com", "org", "net", "app", "www",
        "mobile", "browser", "client", "main", "base",
    )
}
